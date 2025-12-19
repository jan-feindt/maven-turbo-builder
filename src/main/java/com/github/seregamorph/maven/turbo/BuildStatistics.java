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
    private static final int MODULE_NAME_PADDING = 45;
    private static final int TREE_NAME_PADDING = 40;

    private final long buildStartTime;
    private final Map<MavenProject, Long> moduleStartTimes;
    private final Map<MavenProject, Long> moduleEndTimes;
    private final Map<MavenProject, Long> moduleDurations;
    private final Map<MavenProject, Long> moduleWaitTimes;
    private final AtomicInteger currentActiveBuilds;
    private final AtomicInteger maxParallelism;
    private final AtomicLong totalThreadTimeNanos;
    private final AtomicLong lastUpdateNanos;
    private final Set<MavenProject> criticalPathProjects;
    private final Map<MavenProject, MavenProject> pathPredecessor;
    private final Map<MavenProject, Long> longestPathToProject;

    BuildStatistics() {
        this.buildStartTime = System.nanoTime();
        this.moduleStartTimes = new ConcurrentHashMap<>();
        this.moduleEndTimes = new ConcurrentHashMap<>();
        this.moduleDurations = new ConcurrentHashMap<>();
        this.moduleWaitTimes = new ConcurrentHashMap<>();
        this.currentActiveBuilds = new AtomicInteger(0);
        this.maxParallelism = new AtomicInteger(0);
        this.totalThreadTimeNanos = new AtomicLong(0);
        this.lastUpdateNanos = new AtomicLong(buildStartTime);
        this.criticalPathProjects = ConcurrentHashMap.newKeySet();
        this.pathPredecessor = new ConcurrentHashMap<>();
        this.longestPathToProject = new ConcurrentHashMap<>();
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
        moduleEndTimes.put(project, now);
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

    /**
     * Calculate wait time for a module based on when its dependencies completed.
     */
    void calculateWaitTime(MavenProject project, ProjectDependencyGraph dependencyGraph) {
        Long startTime = moduleStartTimes.get(project);
        if (startTime == null) {
            return;
        }

        // Find the latest end time of all upstream dependencies
        List<MavenProject> upstreamProjects = dependencyGraph.getUpstreamProjects(project, false);
        long maxDependencyEndTime = buildStartTime;
        
        for (MavenProject upstream : upstreamProjects) {
            Long upstreamEndTime = moduleEndTimes.get(upstream);
            if (upstreamEndTime != null && upstreamEndTime > maxDependencyEndTime) {
                maxDependencyEndTime = upstreamEndTime;
            }
        }

        // Wait time is the time between when dependencies finished and when this module started
        if (maxDependencyEndTime < startTime) {
            long waitTime = startTime - maxDependencyEndTime;
            moduleWaitTimes.put(project, waitTime);
        } else {
            moduleWaitTimes.put(project, 0L);
        }
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

        // Calculate wait times for all modules
        for (MavenProject project : moduleDurations.keySet()) {
            calculateWaitTime(project, dependencyGraph);
        }

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
    void logBuildSummary(ProjectDependencyGraph dependencyGraph) {
        long totalBuildTime = System.nanoTime() - buildStartTime;
        double totalSeconds = totalBuildTime / 1_000_000_000.0;

        // Calculate average parallelism
        double averageParallelism = 0.0;
        if (totalBuildTime > 0) {
            averageParallelism = totalThreadTimeNanos.get() / (double) totalBuildTime;
        }

        // Calculate efficiency metrics
        long totalModuleBuildTime = moduleDurations.values().stream().mapToLong(Long::longValue).sum();
        double efficiency = 0.0;
        if (totalBuildTime > 0 && averageParallelism > 0) {
            efficiency = (totalModuleBuildTime / (double) totalBuildTime) / averageParallelism * 100.0;
        }

        logger.info("------------------------------------------------------------------------");
        logger.info("Reactor Summary - Turbo Builder Performance:");
        logger.info("------------------------------------------------------------------------");
        logger.info("Total build time: {}", formatDuration(totalBuildTime));
        logger.info("Max parallelism used: {} threads", maxParallelism.get());
        logger.info("Average parallelism: {} threads", Math.round(averageParallelism * 10.0) / 10.0);
        logger.info("Parallelization efficiency: {}%", Math.round(efficiency));
        logger.info("");

        // Find the module that finished last (actual build end)
        MavenProject actualBuildEnd = moduleEndTimes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        // Log critical path with tree visualization
        MavenProject criticalPathEnd = null;
        if (!criticalPathProjects.isEmpty() && dependencyGraph != null) {
            // Find the root of the critical path (project with no critical predecessor)
            MavenProject criticalRoot = null;
            for (MavenProject project : criticalPathProjects) {
                if (!pathPredecessor.containsKey(project)) {
                    criticalRoot = project;
                    break;
                }
            }

            if (criticalRoot != null) {
                criticalPathEnd = findCriticalPathEnd();
                long criticalPathTime = longestPathToProject.getOrDefault(criticalPathEnd, 0L);
                double criticalPathPercent = totalBuildTime > 0 
                    ? (criticalPathTime / (double) totalBuildTime) * 100.0 : 0.0;

                logger.info("Critical Path (longest dependency chain):");
                logger.info("Total critical path time: {} ({}% of total build)",
                    formatDuration(criticalPathTime),
                    Math.round(criticalPathPercent));
                logger.info("");
                logDependencyTree(criticalRoot, dependencyGraph, "", true, new HashSet<>());
                logger.info("");
            }
        }

        // Log actual build end information
        if (actualBuildEnd != null) {
            long actualEndTime = moduleEndTimes.getOrDefault(actualBuildEnd, 0L);
            long actualStartTime = moduleStartTimes.getOrDefault(actualBuildEnd, 0L);
            long actualDuration = moduleDurations.getOrDefault(actualBuildEnd, 0L);
            long actualWaitTime = moduleWaitTimes.getOrDefault(actualBuildEnd, 0L);

            boolean sameAsCriticalPath = actualBuildEnd.equals(criticalPathEnd);

            logger.info("Actual Build End (last module to complete):");
            if (sameAsCriticalPath) {
                logger.info("  Module: {} (same as critical path end) ✓", actualBuildEnd.getArtifactId());
                logger.info("  This indicates optimal scheduling - the critical path determined build time.");
            } else {
                logger.info("  Module: {}", actualBuildEnd.getArtifactId());
                logger.info("  Finished at: {} (actual build end)", formatDuration(actualEndTime - buildStartTime));
                logger.info("  Build duration: {}", formatDuration(actualDuration));
                logger.info("  Started at: {}", formatDuration(actualStartTime - buildStartTime));
                if (actualWaitTime > 0) {
                    logger.info("  Wait time: {} (scheduling/thread availability delay)", formatDuration(actualWaitTime));
                }
                
                if (dependencyGraph != null) {
                    // Calculate when dependencies were ready
                    List<MavenProject> upstreamProjects = dependencyGraph.getUpstreamProjects(actualBuildEnd, false);
                    long dependenciesReadyAt = buildStartTime;
                    for (MavenProject upstream : upstreamProjects) {
                        Long upstreamEndTime = moduleEndTimes.get(upstream);
                        if (upstreamEndTime != null && upstreamEndTime > dependenciesReadyAt) {
                            dependenciesReadyAt = upstreamEndTime;
                        }
                    }
                    if (dependenciesReadyAt > buildStartTime) {
                        logger.info("  Dependencies ready at: {}", formatDuration(dependenciesReadyAt - buildStartTime));
                    }
                }
                
                logger.info("");
                logger.info("⚠️  Note: {} finished AFTER the critical path completed.", actualBuildEnd.getArtifactId());
                logger.info("    This indicates thread starvation or scheduling inefficiency.");
                logger.info("    Consider increasing parallelism or optimizing module order.");
            }
            logger.info("");
        }

        // Log all module build times sorted by duration
        List<Map.Entry<MavenProject, Long>> sortedModules = new ArrayList<>(moduleDurations.entrySet());
        sortedModules.sort(Map.Entry.<MavenProject, Long>comparingByValue().reversed());

        logger.info("Module Build Times:");
        for (Map.Entry<MavenProject, Long> entry : sortedModules) {
            MavenProject project = entry.getKey();
            long duration = entry.getValue();
            long waitTime = moduleWaitTimes.getOrDefault(project, 0L);
            boolean isCritical = criticalPathProjects.contains(project);
            boolean isBottleneck = isBottleneck(project);
            
            String marker = "";
            if (isCritical) {
                marker = " [CRITICAL PATH]";
            }
            if (isBottleneck) {
                marker += " [BOTTLENECK]";
            }
            
            String waitInfo = waitTime > 0 ? String.format(" (wait: %s)", formatDuration(waitTime)) : "";
            logger.info("  {} {}{}{}",
                padRight(project.getArtifactId(), MODULE_NAME_PADDING, '.'),
                formatDuration(duration),
                waitInfo,
                marker);
        }
        logger.info("------------------------------------------------------------------------");
    }

    /**
     * Find the end of the critical path (project with longest total path).
     */
    private MavenProject findCriticalPathEnd() {
        MavenProject criticalEnd = null;
        long maxPathLength = 0L;
        for (Map.Entry<MavenProject, Long> entry : longestPathToProject.entrySet()) {
            if (entry.getValue() > maxPathLength) {
                maxPathLength = entry.getValue();
                criticalEnd = entry.getKey();
            }
        }
        return criticalEnd;
    }

    /**
     * Log the dependency tree in a tree-style format with └─> and │ characters.
     */
    private void logDependencyTree(MavenProject project, ProjectDependencyGraph dependencyGraph, 
                                   String prefix, boolean isRoot, Set<MavenProject> visited) {
        if (visited.contains(project)) {
            logger.warn("Circular dependency detected for project: {}", project.getArtifactId());
            return;
        }
        visited.add(project);

        long duration = moduleDurations.getOrDefault(project, 0L);
        long waitTime = moduleWaitTimes.getOrDefault(project, 0L);
        boolean isBottleneck = isBottleneck(project);
        
        String marker = isBottleneck ? " [BOTTLENECK]" : "";
        String waitInfo = waitTime > 0 ? String.format(" (wait: %s)", formatDuration(waitTime)) : "";
        
        if (isRoot) {
            logger.info("  {} {} {}{}{}",
                "└─>",
                padRight(project.getArtifactId(), TREE_NAME_PADDING, '.'),
                formatDuration(duration),
                waitInfo,
                marker);
        } else {
            logger.info("{}  {} {} {}{}{}",
                prefix,
                "└─>",
                padRight(project.getArtifactId(), TREE_NAME_PADDING, '.'),
                formatDuration(duration),
                waitInfo,
                marker);
        }

        // Find the next project in the critical path
        MavenProject nextInPath = null;
        for (Map.Entry<MavenProject, MavenProject> entry : pathPredecessor.entrySet()) {
            if (entry.getValue().equals(project) && criticalPathProjects.contains(entry.getKey())) {
                nextInPath = entry.getKey();
                break;
            }
        }

        if (nextInPath != null) {
            String newPrefix = isRoot ? "    │" : prefix + "    │";
            logDependencyTree(nextInPath, dependencyGraph, newPrefix, false, visited);
        }
    }

    /**
     * Determine if a module is a bottleneck (significantly impacts build time).
     * A module is considered a bottleneck if its duration is > 10% of total build time
     * or > 20% of the critical path time.
     */
    private boolean isBottleneck(MavenProject project) {
        long duration = moduleDurations.getOrDefault(project, 0L);
        long totalBuildTime = System.nanoTime() - buildStartTime;
        
        // Check if duration is > 10% of total build time
        if (duration > totalBuildTime * 0.1) {
            return true;
        }

        // Check if duration is > 20% of critical path time
        MavenProject criticalEnd = findCriticalPathEnd();
        if (criticalEnd != null) {
            long criticalPathTime = longestPathToProject.getOrDefault(criticalEnd, 0L);
            if (criticalPathTime > 0 && duration > criticalPathTime * 0.2) {
                return true;
            }
        }

        return false;
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
