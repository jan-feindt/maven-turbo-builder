package com.github.seregamorph.maven.turbo;

import static com.github.seregamorph.maven.turbo.PhaseOrderPatcher.isPackage;

import java.util.stream.Collectors;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sergey Chernov
 */
@Named
@Singleton
public class TurboProjectExecutionListener implements ProjectExecutionListener {

    private static final Logger logger = LoggerFactory.getLogger(TurboProjectExecutionListener.class);

    @Override
    public void beforeProjectExecution(ProjectExecutionEvent event) {
    }

    @Override
    public void beforeProjectLifecycleExecution(ProjectExecutionEvent event) {
        CurrentProjectExecution.ifPresent(execution -> {
            execution.packageMojos = event.getExecutionPlan().stream()
                .filter(mojo -> {
                    String lifecyclePhase = mojo.getLifecyclePhase();
                    return lifecyclePhase != null && isPackage(lifecyclePhase);
                })
                .collect(Collectors.toList());

            if (isReorderPhases()) {
                // Create config for THIS specific project
                TurboBuilderConfig config = TurboBuilderConfig.fromSessionAndProject(
                    event.getSession(), event.getProject());
                
                if (config.isTurboTestCompile()) {
                    logger.info("Project {} has test-jar goal - using turboTestCompile mode "
                        + "(package after test-compile)", event.getProject().getArtifactId());
                }
                
                PhaseOrderPatcher.reorderPhases(config, event.getExecutionPlan(), MojoUtils::getMojoPhase);
            }
        });
    }

    boolean isReorderPhases() {
        // opposite to ordering on the bootstrap - order during the mojo execution
        return !PhaseOrderPatcher.isReorderOnBootstrap();
    }

    @Override
    public void afterProjectExecutionSuccess(ProjectExecutionEvent event) {
    }

    @Override
    public void afterProjectExecutionFailure(ProjectExecutionEvent event) {
    }
}
