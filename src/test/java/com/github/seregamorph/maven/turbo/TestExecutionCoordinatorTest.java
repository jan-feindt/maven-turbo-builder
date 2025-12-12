package com.github.seregamorph.maven.turbo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * @author Sergey Chernov
 */
class TestExecutionCoordinatorTest {

    @Test
    void shouldSequentiallyExecuteTestsForSameGroupId() throws InterruptedException {
        TestExecutionCoordinator coordinator = new TestExecutionCoordinator(true);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);

        // Simulate three modules from the same groupId
        String groupId = "com.example";

        // Module 1
        executor.submit(() -> {
            try {
                coordinator.acquireTestLock(groupId);
                executionOrder.add("module1-start");
                Thread.sleep(50); // Simulate test execution
                executionOrder.add("module1-end");
                coordinator.releaseTestLock(groupId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // Module 2
        executor.submit(() -> {
            try {
                Thread.sleep(10); // Small delay to ensure module1 starts first
                coordinator.acquireTestLock(groupId);
                executionOrder.add("module2-start");
                Thread.sleep(50); // Simulate test execution
                executionOrder.add("module2-end");
                coordinator.releaseTestLock(groupId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // Module 3
        executor.submit(() -> {
            try {
                Thread.sleep(20); // Small delay to ensure module1 and module2 start first
                coordinator.acquireTestLock(groupId);
                executionOrder.add("module3-start");
                Thread.sleep(50); // Simulate test execution
                executionOrder.add("module3-end");
                coordinator.releaseTestLock(groupId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify sequential execution: each module must complete before the next starts
        assertTrue(executionOrder.indexOf("module1-end") < executionOrder.indexOf("module2-start"),
            "Module 1 should complete before module 2 starts");
        assertTrue(executionOrder.indexOf("module2-end") < executionOrder.indexOf("module3-start"),
            "Module 2 should complete before module 3 starts");
    }

    @Test
    void shouldAllowParallelExecutionForDifferentGroupIds() throws InterruptedException {
        TestExecutionCoordinator coordinator = new TestExecutionCoordinator(true);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        // Module from groupId1
        executor.submit(() -> {
            try {
                coordinator.acquireTestLock("com.example.group1");
                executionOrder.add("group1-start");
                Thread.sleep(100); // Simulate longer test execution
                executionOrder.add("group1-end");
                coordinator.releaseTestLock("com.example.group1");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // Module from groupId2
        executor.submit(() -> {
            try {
                Thread.sleep(10); // Small delay to ensure group1 starts first
                coordinator.acquireTestLock("com.example.group2");
                executionOrder.add("group2-start");
                Thread.sleep(50); // Simulate test execution
                executionOrder.add("group2-end");
                coordinator.releaseTestLock("com.example.group2");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify parallel execution: group2 should start and complete before group1 ends
        assertTrue(executionOrder.indexOf("group2-start") < executionOrder.indexOf("group1-end"),
            "Group 2 should start while group 1 is still running");
        assertTrue(executionOrder.indexOf("group2-end") < executionOrder.indexOf("group1-end"),
            "Group 2 should complete before group 1 completes");
    }

    @Test
    void shouldNotBlockWhenDisabled() throws InterruptedException {
        TestExecutionCoordinator coordinator = new TestExecutionCoordinator(false);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        String groupId = "com.example";

        // Module 1
        executor.submit(() -> {
            try {
                coordinator.acquireTestLock(groupId);
                executionOrder.add("module1-start");
                Thread.sleep(100); // Simulate longer test execution
                executionOrder.add("module1-end");
                coordinator.releaseTestLock(groupId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        // Module 2
        executor.submit(() -> {
            try {
                Thread.sleep(10); // Small delay to ensure module1 starts first
                coordinator.acquireTestLock(groupId);
                executionOrder.add("module2-start");
                Thread.sleep(50); // Simulate test execution
                executionOrder.add("module2-end");
                coordinator.releaseTestLock(groupId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // When disabled, module 2 should start and complete while module 1 is still running
        assertTrue(executionOrder.indexOf("module2-start") < executionOrder.indexOf("module1-end"),
            "Module 2 should start while module 1 is still running when coordinator is disabled");
        assertTrue(executionOrder.indexOf("module2-end") < executionOrder.indexOf("module1-end"),
            "Module 2 should complete before module 1 completes when coordinator is disabled");
    }
}
