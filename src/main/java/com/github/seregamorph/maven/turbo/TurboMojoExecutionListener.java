package com.github.seregamorph.maven.turbo;

import static com.github.seregamorph.maven.turbo.PhaseOrderPatcher.isAnyTest;

import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sergey Chernov
 */
@Named
@Singleton
public class TurboMojoExecutionListener implements MojoExecutionListener {

    private static final Logger logger = LoggerFactory.getLogger(TurboMojoExecutionListener.class);

    private static final ThreadLocal<Boolean> testLockAcquired = ThreadLocal.withInitial(() -> false);

    @Override
    public void beforeMojoExecution(MojoExecutionEvent event) {
        CurrentProjectExecution.ifPresent(execution -> {
            if (execution.packageMojos == null) {
                logger.warn("packageMojos not initialized in TurboProjectExecutionListener");
                return;
            }
            if (!execution.signaled && execution.packageMojos.isEmpty()) {
                String phase = MojoUtils.getMojoPhase(event.getExecution());
                if (phase != null && isAnyTest(phase)) {
                    // Acquire test lock before running tests if sequential tests by groupId is enabled
                    if (execution.testCoordinator != null && execution.testCoordinator.isEnabled() 
                            && !testLockAcquired.get()) {
                        String groupId = event.getProject().getGroupId();
                        logger.info("Acquiring test execution lock for groupId: {}", groupId);
                        execution.testCoordinator.acquireTestLock(groupId);
                        testLockAcquired.set(true);
                    }
                    
                    execution.signaled = true;
                    // signal before tests
                    SignalingExecutorCompletionService.signal(event.getProject());
                }
            }
        });
    }

    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent event) {
        CurrentProjectExecution.ifPresent(execution -> {
            if (execution.packageMojos == null) {
                logger.warn("packageMojos not initialized in TurboProjectExecutionListener");
                return;
            }
            if (!execution.signaled) {
                if (execution.packageMojos.contains(event.getExecution())) {
                    execution.executedPackageMojos.add(event.getExecution());
                    if (execution.packageMojos.equals(execution.executedPackageMojos)) {
                        execution.signaled = true;
                        // signal after package
                        SignalingExecutorCompletionService.signal(event.getProject());
                    }
                }
            }
            
            // Release test lock after test phase completes
            releaseTestLockIfNeeded(event, execution);
        });
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
        // Release test lock even on failure to prevent deadlock
        CurrentProjectExecution.ifPresent(execution -> {
            releaseTestLockIfNeeded(event, execution);
        });
    }

    private void releaseTestLockIfNeeded(MojoExecutionEvent event, CurrentProjectExecution execution) {
        if (testLockAcquired.get()) {
            String phase = MojoUtils.getMojoPhase(event.getExecution());
            // Release after test phase (but not integration-test which runs later)
            if (phase != null && "test".equals(phase)) {
                if (execution.testCoordinator != null && execution.testCoordinator.isEnabled()) {
                    String groupId = event.getProject().getGroupId();
                    logger.info("Releasing test execution lock for groupId: {}", groupId);
                    execution.testCoordinator.releaseTestLock(groupId);
                    testLockAcquired.set(false);
                }
            }
        }
    }
}
