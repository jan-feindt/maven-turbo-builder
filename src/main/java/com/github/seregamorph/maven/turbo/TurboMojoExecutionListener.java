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
            
            String currentPhase = MojoUtils.getMojoPhase(event.getExecution());
            
            // Check if we should signal at this phase based on configuration
            if (!execution.signaled && execution.configuredSignalPhase != null) {
                if (shouldSignalAtPhase(currentPhase, execution.configuredSignalPhase)) {
                    execution.signaled = true;
                    SignalingExecutorCompletionService.signal(event.getProject());
                    logger.info("Module {} signaled downstream dependencies after phase: {}", 
                               event.getProject().getArtifactId(), currentPhase);
                    return;
                }
            }
            
            // Existing logic for package phase tracking
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
        });
    }
    
    private boolean shouldSignalAtPhase(String currentPhase, String targetPhase) {
        // Signal when we complete the target phase or its immediate successor
        if ("compile".equals(targetPhase)) {
            return "compile".equals(currentPhase) || "process-classes".equals(currentPhase);
        } else if ("test-compile".equals(targetPhase)) {
            return "test-compile".equals(currentPhase) || "process-test-classes".equals(currentPhase);
        } else if ("package".equals(targetPhase)) {
            return "package".equals(currentPhase);
        }
        return false;
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
    }
}
