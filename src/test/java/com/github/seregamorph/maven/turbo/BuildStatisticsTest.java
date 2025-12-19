package com.github.seregamorph.maven.turbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * @author Sergey Chernov
 */
class BuildStatisticsTest {

    @Test
    void shouldTrackModuleTimings() throws Exception {
        BuildStatistics stats = new BuildStatistics();
        MavenProject project = createMockProject("test-module");

        stats.recordModuleStart(project);
        Thread.sleep(50); // Simulate build time
        stats.recordModuleEnd(project);

        // Verify that module duration was recorded (should be at least 50ms)
        Map<MavenProject, Long> durations = getModuleDurations(stats);
        assertTrue(durations.containsKey(project));
        assertTrue(durations.get(project) >= 50_000_000L); // 50ms in nanos
    }

    @Test
    void shouldTrackParallelism() throws Exception {
        BuildStatistics stats = new BuildStatistics();
        MavenProject project1 = createMockProject("module1");
        MavenProject project2 = createMockProject("module2");

        // Start first module
        stats.recordModuleStart(project1);
        int maxParallelism1 = getMaxParallelism(stats);
        assertTrue(maxParallelism1 >= 1);

        // Start second module (should increase parallelism)
        stats.recordModuleStart(project2);
        int maxParallelism2 = getMaxParallelism(stats);
        assertTrue(maxParallelism2 >= 2);

        // End first module
        stats.recordModuleEnd(project1);
        
        // Max parallelism should still be 2
        assertEquals(2, getMaxParallelism(stats));

        // End second module
        stats.recordModuleEnd(project2);
    }

    @Test
    void shouldLogBuildSummary() throws InterruptedException {
        BuildStatistics stats = new BuildStatistics();
        MavenProject project = createMockProject("test-module");

        stats.recordModuleStart(project);
        Thread.sleep(50);
        stats.recordModuleEnd(project);

        // This should not throw an exception (logging without critical path calculation)
        // Pass null for dependency graph since we're not testing critical path here
        stats.logBuildSummary(null);
    }

    @Test
    void shouldHandleMultipleParallelFinalModules() throws Exception {
        BuildStatistics stats = new BuildStatistics();
        
        // Create a build graph with multiple parallel final modules:
        // module-a (root, 100ms) -> module-c (50ms) [total: 150ms]
        // module-b (root, 80ms) -> module-d (60ms) [total: 140ms]
        MavenProject moduleA = createMockProject("module-a");
        MavenProject moduleB = createMockProject("module-b");
        MavenProject moduleC = createMockProject("module-c");
        MavenProject moduleD = createMockProject("module-d");

        // Simulate build times
        stats.recordModuleStart(moduleA);
        Thread.sleep(100);
        stats.recordModuleEnd(moduleA);

        stats.recordModuleStart(moduleB);
        Thread.sleep(80);
        stats.recordModuleEnd(moduleB);

        stats.recordModuleStart(moduleC);
        Thread.sleep(50);
        stats.recordModuleEnd(moduleC);

        stats.recordModuleStart(moduleD);
        Thread.sleep(60);
        stats.recordModuleEnd(moduleD);

        // Create a mock dependency graph
        ProjectDependencyGraph graph = new ProjectDependencyGraph() {
            @Override
            public List<MavenProject> getSortedProjects() {
                return Arrays.asList(moduleA, moduleB, moduleC, moduleD);
            }

            @Override
            public List<MavenProject> getUpstreamProjects(MavenProject project, boolean transitive) {
                if (project == moduleC) {
                    return Collections.singletonList(moduleA);
                } else if (project == moduleD) {
                    return Collections.singletonList(moduleB);
                }
                return Collections.emptyList();
            }

            @Override
            public List<MavenProject> getDownstreamProjects(MavenProject project, boolean transitive) {
                if (project == moduleA) {
                    return Collections.singletonList(moduleC);
                } else if (project == moduleB) {
                    return Collections.singletonList(moduleD);
                }
                return Collections.emptyList();
            }

            @Override
            public List<MavenProject> getAllProjects() {
                return Arrays.asList(moduleA, moduleB, moduleC, moduleD);
            }
        };

        // Calculate critical path
        stats.calculateCriticalPath(graph);

        // Verify critical path includes the longest path (module-a -> module-c)
        Set<MavenProject> criticalPath = getCriticalPathProjects(stats);
        assertTrue(criticalPath.contains(moduleA), "Critical path should include module-a");
        assertTrue(criticalPath.contains(moduleC), "Critical path should include module-c");
        
        // The implementation correctly identifies the LONGEST critical path
        // module-b and module-d may or may not be in the critical path depending on implementation
        // The key is that the longest path (a->c at 150ms) is correctly identified
        
        // Verify build summary can be generated without errors
        stats.logBuildSummary(graph);
    }

    private MavenProject createMockProject(String artifactId) {
        MavenProject project = new MavenProject();
        project.setGroupId("com.example");
        project.setArtifactId(artifactId);
        project.setVersion("1.0.0");
        return project;
    }

    @SuppressWarnings("unchecked")
    private Map<MavenProject, Long> getModuleDurations(BuildStatistics stats) throws Exception {
        Field field = BuildStatistics.class.getDeclaredField("moduleDurations");
        field.setAccessible(true);
        return (Map<MavenProject, Long>) field.get(stats);
    }

    private int getMaxParallelism(BuildStatistics stats) throws Exception {
        Field field = BuildStatistics.class.getDeclaredField("maxParallelism");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicInteger) field.get(stats)).get();
    }

    @SuppressWarnings("unchecked")
    private Set<MavenProject> getCriticalPathProjects(BuildStatistics stats) throws Exception {
        Field field = BuildStatistics.class.getDeclaredField("criticalPathProjects");
        field.setAccessible(true);
        return (Set<MavenProject>) field.get(stats);
    }
}
