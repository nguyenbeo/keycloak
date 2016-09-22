/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.services.managers;


import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.services.ServicesLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A single thread will log failures.  This is so that we can avoid concurrent writes as we want an accurate failure count
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class DefaultBruteForceProtector implements Runnable, BruteForceProtector {
    protected static ServicesLogger logger = ServicesLogger.ROOT_LOGGER;

    protected volatile boolean run = true;
    protected int maxDeltaTimeSeconds = 60 * 60 * 12; // 12 hours
    protected KeycloakSessionFactory factory;
    protected CountDownLatch shutdownLatch = new CountDownLatch(1);

    protected volatile long failures;
    protected volatile long lastFailure;
    protected volatile long totalTime;

    protected LinkedBlockingQueue<LoginEvent> queue = new LinkedBlockingQueue<LoginEvent>();
    public static final int TRANSACTION_SIZE = 20;

    public static volatile int markNotBefore = -1;
    public static volatile int markCheck = -1;
    public static volatile int testCount = 0;
    public static volatile int clearCount = 0;
    public static volatile int nullFailureCount = 0;
    public static volatile int logFailureCalled = 0;
    public static volatile int logFailure = 0;
    public static volatile int didntWait = 0;
    public static volatile int exception = 0;
    public static volatile Exception exceptionObject = null;
    public static volatile String notFoundUserId = null;


    protected abstract class LoginEvent implements Comparable<LoginEvent> {
        protected final String realmId;
        protected final String userId;
        protected final String ip;

        protected LoginEvent(String realmId, String userId, String ip) {
            this.realmId = realmId;
            this.userId = userId;
            this.ip = ip;
        }

        @Override
        public int compareTo(LoginEvent o) {
            return userId.compareTo(o.userId);
        }
    }

    protected class ShutdownEvent extends LoginEvent {
        public ShutdownEvent() {
            super(null, null, null);
        }
    }

    protected class FailedLogin extends LoginEvent {
        protected final CountDownLatch latch = new CountDownLatch(1);

        public FailedLogin(String realmId, String userId, String ip) {
            super(realmId, userId, ip);
        }
    }

    public DefaultBruteForceProtector(KeycloakSessionFactory factory) {
        this.factory = factory;
    }

    public void failure(KeycloakSession session, LoginEvent event) {
        logger.debug("failure");
        logFailureCalled++;
        RealmModel realm = getRealmModel(session, event);
        logFailure(event);

        String userId = event.userId;
        UserModel user = session.users().getUserById(userId, realm);
        UserLoginFailureModel userLoginFailure = getUserModel(session, event);
        if (user != null) {
            logFailure++;
            if (userLoginFailure == null) {
                userLoginFailure = session.sessions().addUserLoginFailure(realm, userId);
            }
            userLoginFailure.setLastIPFailure(event.ip);
            long currentTime = Time.currentTimeMillis();
            long last = userLoginFailure.getLastFailure();
            long deltaTime = 0;
            if (last > 0) {
                deltaTime = currentTime - last;
            }
            userLoginFailure.setLastFailure(currentTime);
            if (deltaTime > 0) {
                // if last failure was more than MAX_DELTA clear failures
                if (deltaTime > (long) realm.getMaxDeltaTimeSeconds() * 1000L) {
                    clearCount++;
                    userLoginFailure.clearFailures();
                }
            }
            userLoginFailure.incrementFailures();
            logger.debugv("new num failures: {0}", userLoginFailure.getNumFailures());

            int waitSeconds = realm.getWaitIncrementSeconds() *  (userLoginFailure.getNumFailures() / realm.getFailureFactor());
            logger.debugv("waitSeconds: {0}", waitSeconds);
            logger.debugv("deltaTime: {0}", deltaTime);

            if (waitSeconds == 0) {
                if (last > 0 && deltaTime < realm.getQuickLoginCheckMilliSeconds()) {
                    logger.debugv("quick login, set min wait seconds");
                    waitSeconds = realm.getMinimumQuickLoginWaitSeconds();
                }
            }
            if (waitSeconds > 0) {
                waitSeconds = Math.min(realm.getMaxFailureWaitSeconds(), waitSeconds);
                int notBefore = (int) (currentTime / 1000) + waitSeconds;
                logger.debugv("set notBefore: {0}", notBefore);
                markNotBefore = notBefore;
                userLoginFailure.setFailedLoginNotBefore(notBefore);
            }
        } else {
            notFoundUserId = event.userId;

        }
    }


    protected UserLoginFailureModel getUserModel(KeycloakSession session, LoginEvent event) {
        RealmModel realm = getRealmModel(session, event);
        if (realm == null) return null;
        UserLoginFailureModel user = session.sessions().getUserLoginFailure(realm, event.userId);
        if (user == null) return null;
        return user;
    }

    protected RealmModel getRealmModel(KeycloakSession session, LoginEvent event) {
        RealmModel realm = session.realms().getRealm(event.realmId);
        if (realm == null) return null;
        return realm;
    }

    public void start() {
        new Thread(this, "Brute Force Protector").start();
    }

    public void shutdown() {
        run = false;
        try {
            queue.offer(new ShutdownEvent());
            shutdownLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {
        final ArrayList<LoginEvent> events = new ArrayList<LoginEvent>(TRANSACTION_SIZE + 1);
        try {
            while (run) {
                try {
                    LoginEvent take = queue.poll(2, TimeUnit.SECONDS);
                    if (take == null) {
                        continue;
                    }
                    try {
                        events.add(take);
                        queue.drainTo(events, TRANSACTION_SIZE);
                        Collections.sort(events); // we sort to avoid deadlock due to ordered updates.  Maybe I'm overthinking this.
                        KeycloakSession session = factory.create();
                        session.getTransactionManager().begin();
                        try {
                            for (LoginEvent event : events) {
                                if (event instanceof FailedLogin) {
                                    failure(session, event);
                                } else if (event instanceof ShutdownEvent) {
                                    run = false;
                                }
                            }
                            session.getTransactionManager().commit();
                        } catch (Exception e) {
                            exception++;
                            exceptionObject = e;
                            session.getTransactionManager().rollback();
                            throw e;
                        } finally {
                            for (LoginEvent event : events) {
                                if (event instanceof FailedLogin) {
                                    ((FailedLogin) event).latch.countDown();
                                }
                            }
                            events.clear();
                            session.close();
                        }
                    } catch (Exception e) {
                        logger.failedProcessingType(e);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            shutdownLatch.countDown();
        }
    }

    protected void logFailure(LoginEvent event) {
        logger.loginFailure(event.userId, event.ip);
        failures++;
        long delta = 0;
        if (lastFailure > 0) {
            delta = Time.currentTimeMillis() - lastFailure;
            if (delta > (long)maxDeltaTimeSeconds * 1000L) {
                totalTime = 0;

            } else {
                totalTime += delta;
            }
        }
    }

    @Override
    public void failedLogin(RealmModel realm, UserModel user, ClientConnection clientConnection) {
        try {
            FailedLogin event = new FailedLogin(realm.getId(), user.getId(), clientConnection.getRemoteAddr());
            queue.offer(event);
            // wait a minimum of seconds for type to process so that a hacker
            // cannot flood with failed logins and overwhelm the queue and not have notBefore updated to block next requests
            // todo failure HTTP responses should be queued via async HTTP
            boolean awaited =
                    event.latch.await(5, TimeUnit.SECONDS);
            if (!awaited) {
                    didntWait++;
            }
        } catch (InterruptedException e) {
            didntWait++;
        }
        logger.trace("sent failure event");
    }

    @Override
    public boolean isTemporarilyDisabled(KeycloakSession session, RealmModel realm, UserModel user) {
        UserLoginFailureModel failure = session.sessions().getUserLoginFailure(realm, user.getId());

        if (failure != null) {
            int currTime = markCheck = (int) (Time.currentTimeMillis() / 1000);
            int failedLoginNotBefore = failure.getFailedLoginNotBefore();
            testCount++;
            if (currTime < failedLoginNotBefore) {
                logger.debugv("Current: {0} notBefore: {1}", currTime, failedLoginNotBefore);
                return true;
            } else if (failedLoginNotBefore > 0){
                logger.debugv("failedLoginNotBefore > 0 Current: {0} notBefore: {1}", currTime, failedLoginNotBefore);
            }
        } else {
            logger.debugv("failure was null");
            nullFailureCount++;

        }


        return false;
    }
    @Override
    public void close() {

    }
}
