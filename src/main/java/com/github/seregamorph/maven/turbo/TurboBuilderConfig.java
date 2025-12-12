package com.github.seregamorph.maven.turbo;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;

/**
 * @author Sergey Chernov
 */
public class TurboBuilderConfig {

    private final boolean turboTestCompile;
    private final boolean sequentialTestsByGroupId;

    static TurboBuilderConfig fromSession(MavenSession session) {
        String turboTestCompile = MavenPropertyUtils.getProperty(session, "turboTestCompile");
        String sequentialTestsByGroupId = MavenPropertyUtils.getProperty(session, "sequentialTestsByGroupId");
        
        return new TurboBuilderConfig(
            MavenPropertyUtils.isTrue(turboTestCompile),
            MavenPropertyUtils.isTrue(sequentialTestsByGroupId));
    }

    /**
     * Creates a config for a specific project, checking if it produces a test-jar.
     * If the project has test-jar goal configured, turboTestCompile is automatically enabled for that project.
     */
    static TurboBuilderConfig fromSessionAndProject(MavenSession session, MavenProject project) {
        String turboTestCompile = MavenPropertyUtils.getProperty(session, "turboTestCompile");
        String sequentialTestsByGroupId = MavenPropertyUtils.getProperty(session, "sequentialTestsByGroupId");
        
        boolean enableTurboTestCompile = MavenPropertyUtils.isTrue(turboTestCompile);
        
        // If not explicitly set, check if THIS project has test-jar
        if (!enableTurboTestCompile) {
            enableTurboTestCompile = hasTestJarGoal(project);
        }
        
        return new TurboBuilderConfig(
            enableTurboTestCompile,
            MavenPropertyUtils.isTrue(sequentialTestsByGroupId));
    }

    /**
     * Checks if the given project has test-jar goal configured in maven-jar-plugin.
     */
    private static boolean hasTestJarGoal(MavenProject project) {
        List<Plugin> jarPlugins = project.getBuildPlugins().stream()
            .filter(plugin ->
                "org.apache.maven.plugins".equals(plugin.getGroupId())
                    && "maven-jar-plugin".equals(plugin.getArtifactId()))
            .collect(Collectors.toList());
        
        for (Plugin jarPlugin : jarPlugins) {
            for (PluginExecution pluginExecution : jarPlugin.getExecutions()) {
                if (pluginExecution.getGoals().contains("test-jar")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Public static version for use by TurboBuilder in Maven 3 global detection.
     */
    static boolean hasTestJarGoalStatic(MavenProject project) {
        return hasTestJarGoal(project);
    }

    TurboBuilderConfig(boolean turboTestCompile, boolean sequentialTestsByGroupId) {
        this.turboTestCompile = turboTestCompile;
        this.sequentialTestsByGroupId = sequentialTestsByGroupId;
    }

    public boolean isTurboTestCompile() {
        return turboTestCompile;
    }

    public boolean isSequentialTestsByGroupId() {
        return sequentialTestsByGroupId;
    }

    @Override
    public String toString() {
        return "TurboBuilderConfig{" +
            "turboTestCompile=" + turboTestCompile +
            ", sequentialTestsByGroupId=" + sequentialTestsByGroupId +
            '}';
    }
}
