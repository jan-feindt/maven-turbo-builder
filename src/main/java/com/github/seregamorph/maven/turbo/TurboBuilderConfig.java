package com.github.seregamorph.maven.turbo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

/**
 * @author Sergey Chernov
 */
public class TurboBuilderConfig {

    private final boolean turboTestCompile;
    private final Map<String, String> moduleSignalPhases;
    private final String defaultSignalPhase;

    static TurboBuilderConfig fromSession(MavenSession session) {
        String turboTestCompile = MavenPropertyUtils.getProperty(session, "turboTestCompile");
        boolean turboTestCompileFlag = MavenPropertyUtils.isTrue(turboTestCompile);
        
        Map<String, String> moduleSignalPhases = new HashMap<>();
        
        // Determine global default signal phase
        String defaultSignalPhase = MavenPropertyUtils.getProperty(session, "turboSignalPhase");
        if (defaultSignalPhase == null) {
            // Backward compatibility: use turboTestCompile flag to determine default
            defaultSignalPhase = turboTestCompileFlag ? "test-compile" : "package";
        }
        
        // Per-module configuration with auto-detection (if projects are available)
        if (session.getProjects() != null) {
            for (MavenProject project : session.getProjects()) {
                String modulePhase = MavenPropertyUtils.getProperty(session, project, "turboSignalPhase");
                
                if (modulePhase == null) {
                    modulePhase = MavenPropertyUtils.getProperty(session, project, 
                        project.getArtifactId() + ".turboSignalPhase");
                }
                
                if (modulePhase == null) {
                    // Auto-detect optimal phase
                    modulePhase = detectOptimalSignalPhase(project, session, defaultSignalPhase);
                }
                
                moduleSignalPhases.put(project.getArtifactId(), modulePhase);
            }
        }
        
        return new TurboBuilderConfig(turboTestCompileFlag, moduleSignalPhases, defaultSignalPhase);
    }

    TurboBuilderConfig(boolean turboTestCompile, Map<String, String> moduleSignalPhases, String defaultSignalPhase) {
        this.turboTestCompile = turboTestCompile;
        this.moduleSignalPhases = moduleSignalPhases;
        this.defaultSignalPhase = defaultSignalPhase;
    }
    
    // Constructor for backward compatibility (tests)
    TurboBuilderConfig(boolean turboTestCompile) {
        this.turboTestCompile = turboTestCompile;
        this.moduleSignalPhases = new HashMap<>();
        this.defaultSignalPhase = turboTestCompile ? "test-compile" : "package";
    }

    public boolean isTurboTestCompile() {
        return turboTestCompile;
    }
    
    public String getSignalPhase(MavenProject project) {
        return moduleSignalPhases.getOrDefault(project.getArtifactId(), defaultSignalPhase);
    }
    
    /**
     * Auto-detect optimal signal phase by analyzing downstream dependencies.
     */
    private static String detectOptimalSignalPhase(MavenProject project, MavenSession session, 
                                                    String defaultSignalPhase) {
        List<MavenProject> downstreamProjects = 
            session.getProjectDependencyGraph().getDownstreamProjects(project, false);
        
        // If no downstream consumers, signal immediately at compile
        if (downstreamProjects.isEmpty()) {
            return "compile";
        }
        
        boolean hasTestJarConsumers = false;
        boolean hasTestScopeConsumers = false;
        
        for (MavenProject downstream : downstreamProjects) {
            for (Dependency dep : downstream.getDependencies()) {
                if (project.getArtifactId().equals(dep.getArtifactId()) &&
                    project.getGroupId().equals(dep.getGroupId())) {
                    if ("test-jar".equals(dep.getClassifier())) {
                        hasTestJarConsumers = true;
                    }
                    if ("test".equals(dep.getScope())) {
                        hasTestScopeConsumers = true;
                    }
                }
            }
        }
        
        // Priority order:
        if (hasTestJarConsumers) {
            return "test-compile"; // wait for test-jar artifact
        } else if (hasTestScopeConsumers) {
            return "package"; // wait for main artifact, test dependencies need it
        } else {
            return "compile"; // only compile-scope consumers, signal early!
        }
    }

    @Override
    public String toString() {
        return "TurboBuilderConfig{" +
            "turboTestCompile=" + turboTestCompile +
            ", moduleSignalPhases=" + moduleSignalPhases +
            '}';
    }
}
