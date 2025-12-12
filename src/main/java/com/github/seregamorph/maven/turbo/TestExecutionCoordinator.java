package com.github.seregamorph.maven.turbo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates test execution to ensure modules with the same groupId run tests sequentially.
 * This prevents parallel test execution that might interfere with each other when modules
 * from the same groupId share resources or have test dependencies.
 *
 * @author Sergey Chernov
 */
class TestExecutionCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(TestExecutionCoordinator.class);

    private final Map<String, Semaphore> groupIdSemaphores = new ConcurrentHashMap<>();
    private final boolean enabled;

    TestExecutionCoordinator(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Acquires the test execution lock for the given groupId.
     * If sequential tests by groupId is enabled, this will block until any other module
     * with the same groupId has completed its tests.
     *
     * @param groupId the Maven groupId of the module
     */
    void acquireTestLock(String groupId) {
        if (!enabled) {
            return;
        }
        
        Semaphore semaphore = groupIdSemaphores.computeIfAbsent(groupId, k -> new Semaphore(1));
        logger.debug("Module with groupId {} is waiting to acquire test execution lock", groupId);
        try {
            semaphore.acquire();
            logger.debug("Module with groupId {} acquired test execution lock", groupId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for test execution lock for groupId {}", groupId, e);
        }
    }

    /**
     * Releases the test execution lock for the given groupId.
     *
     * @param groupId the Maven groupId of the module
     */
    void releaseTestLock(String groupId) {
        if (!enabled) {
            return;
        }
        
        Semaphore semaphore = groupIdSemaphores.get(groupId);
        if (semaphore != null) {
            semaphore.release();
            logger.debug("Module with groupId {} released test execution lock", groupId);
        }
    }

    boolean isEnabled() {
        return enabled;
    }
}
