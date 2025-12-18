package com.github.seregamorph.maven.turbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
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
}
