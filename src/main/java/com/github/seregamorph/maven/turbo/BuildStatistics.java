package com.github.seregamorph.maven.turbo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks build performance statistics including timing, parallelism, and critical path analysis.
 * Thread-safe for concurrent access from multiple build threads.
 *
 * @author Sergey Chernov
 */
class BuildStatistics {

    private static final Logger logger = LoggerFactory.getLogger(BuildStatistics.class);

    private final long buildStartTime;
    private final Map<MavenProject, Long> moduleStartTimes;
    private final Map<MavenProject, Long> moduleDurations;
    private final AtomicInteger currentActiveBuilds;
    private final AtomicInteger maxParallelism;
    private final AtomicLong totalThreadTimeNanos;
    private final AtomicLong lastUpdateNanos;
    private final Set<MavenProject> criticalPathProjects;

    BuildStatistics() {
        this.buildStartTime = System.nanoTime();
        this.moduleStartTimes = new ConcurrentHashMap<>();
        this.moduleDurations = new ConcurrentHashMap<>();
        this.currentActiveBuilds = new AtomicInteger(0);
        this.maxParallelism = new AtomicInteger(0);
        this.totalThreadTimeNanos = new AtomicLong(0);
        this.lastUpdateNanos = new AtomicLong(buildStartTime);
        this.criticalPathProjects = ConcurrentHashMap.newKeySet();
    }

    /**
     * Record when a module build starts.
     */
    void recordModuleStart(MavenProject project) {
        long now = System.nanoTime();
        moduleStartTimes.put(project, now);
        
        int active = currentActiveBuilds.incrementAndGet();
        updateMaxParallelism(active);
        updateAverageParallelism(now);
        
        logger.debug("Module {} started building (active builds: {})", project.getArtifactId(), active);
    }

    /**
     * Record when a module build ends.
     */
    void recordModuleEnd(MavenProject project) {
        long now = System.nanoTime();
        Long startTime = moduleStartTimes.get(project);
        if (startTime != null) {
            long duration = now - startTime;
            moduleDurations.put(project, duration);
            logger.debug("Module {} finished building in {}s", 
                project.getArtifactId(), duration / 1_000_000_000.0);
        }
        
        int active = currentActiveBuilds.decrementAndGet();
        updateAverageParallelism(now);
        
        logger.debug("Module {} completed (active builds: {})", project.getArtifactId(), active);
    }

    private void updateMaxParallelism(int currentActive) {
        maxParallelism.updateAndGet(max -> Math.max(max, currentActive));
    }

    private void updateAverageParallelism(long now) {
        long lastUpdate = lastUpdateNanos.getAndSet(now);
        long elapsedNanos = now - lastUpdate;
        if (elapsedNanos > 0) {
            int active = currentActiveBuilds.get();
            totalThreadTimeNanos.addAndGet(active * elapsedNanos);
        }
    }

    /**
     * Calculate the critical path after all builds complete.
     * The critical path is the longest chain of dependent modules considering actual build times.
     */
    void calculateCriticalPath(ProjectDependencyGraph dependencyGraph) {
        if (moduleDurations.isEmpty()) {
            return;
        }

        // Build a map of project to its longest path time (including dependencies)
        Map<MavenProject, Long> longestPathToProject = new HashMap<>();
        Map<MavenProject, MavenProject> pathPredecessor = new HashMap<>();

        // Get all projects in topological order (dependencies first)
        List<MavenProject> sortedProjects = dependencyGraph.getSortedProjects();

        // Calculate longest path to each project
        for (MavenProject project : sortedProjects) {
            long projectDuration = moduleDurations.getOrDefault(project, 0L);
            long maxPredecessorPath = 0L;
            MavenProject criticalPredecessor = null;

            // Find the predecessor with the longest path
            List<MavenProject> upstreamProjects = dependencyGraph.getUpstreamProjects(project, false);
            for (MavenProject upstream : upstreamProjects) {
                long upstreamPath = longestPathToProject.getOrDefault(upstream, 0L);
                if (upstreamPath > maxPredecessorPath) {
                    maxPredecessorPath = upstreamPath;
                    criticalPredecessor = upstream;
                }
            }

            long totalPath = maxPredecessorPath + projectDuration;
            longestPathToProject.put(project, totalPath);
            if (criticalPredecessor != null) {
                pathPredecessor.put(project, criticalPredecessor);
            }
        }

        // Find the project with the longest total path (end of critical path)
        MavenProject criticalEnd = null;
        long maxPathLength = 0L;
        for (Map.Entry<MavenProject, Long> entry : longestPathToProject.entrySet()) {
            if (entry.getValue() > maxPathLength) {
                maxPathLength = entry.getValue();
                criticalEnd = entry.getKey();
            }
        }

        // Trace back the critical path
        if (criticalEnd != null) {
            criticalPathProjects.add(criticalEnd);
            MavenProject current = criticalEnd;
            while (pathPredecessor.containsKey(current)) {
                current = pathPredecessor.get(current);
                criticalPathProjects.add(current);
            }
        }
    }

    /**
     * Log a comprehensive build summary with timing statistics, parallelism metrics, and critical path.
     */
    void logBuildSummary() {
        long totalBuildTime = System.nanoTime() - buildStartTime;
        double totalSeconds = totalBuildTime / 1_000_000_000.0;

        // Calculate average parallelism
        double averageParallelism = 0.0;
        if (totalBuildTime > 0) {
            averageParallelism = totalThreadTimeNanos.get() / (double) totalBuildTime;
        }

        logger.info("------------------------------------------------------------------------");
        logger.info("Reactor Summary - Turbo Builder Performance:");
        logger.info("------------------------------------------------------------------------");
        logger.info("Total build time: {}", formatDuration(totalBuildTime));
        logger.info("Max parallelism used: {} threads", maxParallelism.get());
        logger.info("Average parallelism: {} threads", Math.round(averageParallelism * 10.0) / 10.0);
        logger.info("");

        // Log critical path
        if (!criticalPathProjects.isEmpty()) {
            List<MavenProject> criticalPathList = new ArrayList<>(criticalPathProjects);
            // Sort by duration descending to show bottlenecks first
            criticalPathList.sort(Comparator.comparing(
                p -> moduleDurations.getOrDefault(p, 0L)).reversed());

            long criticalPathTime = criticalPathList.stream()
                .mapToLong(p -> moduleDurations.getOrDefault(p, 0L))
                .sum();

            logger.info("Critical Path (bottleneck modules):");
            for (MavenProject project : criticalPathList) {
                long duration = moduleDurations.getOrDefault(project, 0L);
                logger.info("  {} {}", 
                    padRight(project.getArtifactId(), 50, '.'),
                    formatDuration(duration));
            }
            logger.info("Critical path time: {}", formatDuration(criticalPathTime));
            logger.info("");
        }

        // Log all module build times sorted by duration
        List<Map.Entry<MavenProject, Long>> sortedModules = new ArrayList<>(moduleDurations.entrySet());
        sortedModules.sort(Map.Entry.<MavenProject, Long>comparingByValue().reversed());

        logger.info("Module Build Times:");
        for (Map.Entry<MavenProject, Long> entry : sortedModules) {
            MavenProject project = entry.getKey();
            long duration = entry.getValue();
            String marker = criticalPathProjects.contains(project) ? " [CRITICAL PATH]" : "";
            logger.info("  {} {}{}",
                padRight(project.getArtifactId(), 50, '.'),
                formatDuration(duration),
                marker);
        }
        logger.info("------------------------------------------------------------------------");
    }

    private static String formatDuration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds >= 60) {
            int minutes = (int) (seconds / 60);
            double remainingSeconds = seconds - (minutes * 60);
            return String.format("%dm %.1fs", minutes, remainingSeconds);
        } else {
            return String.format("%.1fs", seconds);
        }
    }

    private static String padRight(String str, int length, char padChar) {
        if (str.length() >= length) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < length) {
            sb.append(padChar);
        }
        return sb.toString();
    }
}
