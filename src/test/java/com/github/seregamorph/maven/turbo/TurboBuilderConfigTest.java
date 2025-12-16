package com.github.seregamorph.maven.turbo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.graph.DefaultProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * Tests for TurboBuilderConfig signal phase detection and configuration.
 * 
 * @author Sergey Chernov
 */
class TurboBuilderConfigTest {

    @Test
    void shouldUseDefaultPackagePhaseWithoutConfig() {
        MavenSession session = createSession(false, null);
        MavenProject project = createProject("test-module");
        session.setProjects(Collections.singletonList(project));
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        assertEquals("package", config.getSignalPhase(project));
        assertEquals(false, config.isTurboTestCompile());
    }

    @Test
    void shouldUseTestCompilePhaseWithTurboTestCompile() {
        MavenSession session = createSession(true, null);
        MavenProject project = createProject("test-module");
        session.setProjects(Collections.singletonList(project));
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        assertEquals("test-compile", config.getSignalPhase(project));
        assertEquals(true, config.isTurboTestCompile());
    }

    @Test
    void shouldUseGlobalTurboSignalPhaseProperty() {
        MavenSession session = createSession(false, "compile");
        MavenProject project = createProject("test-module");
        session.setProjects(Collections.singletonList(project));
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        assertEquals("compile", config.getSignalPhase(project));
    }

    @Test
    void shouldAutoDetectCompilePhaseWhenNoDownstreamConsumers() {
        MavenProject project = createProject("test-module");
        MavenSession session = createSessionWithGraph(
            false, null, Collections.singletonList(project));
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // No downstream consumers -> signal at compile
        assertEquals("compile", config.getSignalPhase(project));
    }

    @Test
    void shouldAutoDetectCompilePhaseWhenOnlyCompileScopeConsumers() {
        MavenProject project = createProject("test-module");
        MavenProject downstream = createProject("downstream-module");
        
        Dependency dep = new Dependency();
        dep.setGroupId("com.test");
        dep.setArtifactId("test-module");
        dep.setScope("compile");
        downstream.getDependencies().add(dep);
        
        List<MavenProject> projects = new ArrayList<>();
        projects.add(project);
        projects.add(downstream);
        
        MavenSession session = createSessionWithGraph(false, null, projects);
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // Only compile-scope consumers -> signal at compile
        assertEquals("compile", config.getSignalPhase(project));
    }

    @Test
    void shouldAutoDetectPackagePhaseWhenTestScopeConsumers() {
        MavenProject project = createProject("test-module");
        MavenProject downstream = createProject("downstream-module");
        
        Dependency dep = new Dependency();
        dep.setGroupId("com.test");
        dep.setArtifactId("test-module");
        dep.setScope("test");
        downstream.getDependencies().add(dep);
        
        List<MavenProject> projects = new ArrayList<>();
        projects.add(project);
        projects.add(downstream);
        
        MavenSession session = createSessionWithGraph(false, null, projects);
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // Test-scope consumers -> signal at package
        assertEquals("package", config.getSignalPhase(project));
    }

    @Test
    void shouldAutoDetectTestCompilePhaseWhenTestJarConsumers() {
        MavenProject project = createProject("test-module");
        MavenProject downstream = createProject("downstream-module");
        
        Dependency dep = new Dependency();
        dep.setGroupId("com.test");
        dep.setArtifactId("test-module");
        dep.setClassifier("test-jar");
        downstream.getDependencies().add(dep);
        
        List<MavenProject> projects = new ArrayList<>();
        projects.add(project);
        projects.add(downstream);
        
        MavenSession session = createSessionWithGraph(false, null, projects);
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // Test-jar consumers -> signal at test-compile
        assertEquals("test-compile", config.getSignalPhase(project));
    }

    @Test
    void shouldUsePerModulePropertyOverAutoDetection() {
        MavenProject project = createProject("test-module");
        
        // Configure per-module property
        project.getProperties().setProperty("turboSignalPhase", "test-compile");
        
        MavenSession session = createSessionWithGraph(
            false, null, Collections.singletonList(project));
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // Per-module property should override auto-detection (which would be "compile")
        assertEquals("test-compile", config.getSignalPhase(project));
    }

    @Test
    void shouldHandleMultipleModulesWithDifferentPhases() {
        MavenProject moduleA = createProject("module-a");
        MavenProject moduleB = createProject("module-b");
        MavenProject moduleC = createProject("module-c");
        
        // Module A is consumed by Module B (compile scope)
        Dependency depA = new Dependency();
        depA.setGroupId("com.test");
        depA.setArtifactId("module-a");
        depA.setScope("compile");
        moduleB.getDependencies().add(depA);
        
        // Module B is consumed by Module C (test-jar)
        Dependency depB = new Dependency();
        depB.setGroupId("com.test");
        depB.setArtifactId("module-b");
        depB.setClassifier("test-jar");
        moduleC.getDependencies().add(depB);
        
        List<MavenProject> projects = new ArrayList<>();
        projects.add(moduleA);
        projects.add(moduleB);
        projects.add(moduleC);
        
        MavenSession session = createSessionWithGraph(false, null, projects);
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // Module A: only compile-scope consumer -> compile
        assertEquals("compile", config.getSignalPhase(moduleA));
        
        // Module B: test-jar consumer -> test-compile
        assertEquals("test-compile", config.getSignalPhase(moduleB));
        
        // Module C: no downstream consumers -> compile
        assertEquals("compile", config.getSignalPhase(moduleC));
    }

    @Test
    void shouldPreferGlobalPropertyOverAutoDetection() {
        MavenProject project = createProject("test-module");
        MavenSession session = createSessionWithGraph(
            false, "package", Collections.singletonList(project));
        
        TurboBuilderConfig config = TurboBuilderConfig.fromSession(session);
        
        // Global property should be used (auto-detection would return "compile")
        assertEquals("package", config.getSignalPhase(project));
    }

    private MavenSession createSession(boolean turboTestCompile, String turboSignalPhase) {
        Properties systemProperties = new Properties();
        Properties userProperties = new Properties();
        
        if (turboTestCompile) {
            systemProperties.setProperty("turboTestCompile", "true");
        }
        if (turboSignalPhase != null) {
            systemProperties.setProperty("turboSignalPhase", turboSignalPhase);
        }
        
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        return new MavenSession(null, request, new DefaultMavenExecutionResult(), 
            Collections.emptyList()) {
            @Override
            public Properties getSystemProperties() {
                return systemProperties;
            }
            
            @Override
            public Properties getUserProperties() {
                return userProperties;
            }
        };
    }

    private MavenSession createSessionWithGraph(boolean turboTestCompile, 
                                                 String turboSignalPhase,
                                                 List<MavenProject> projects) {
        MavenSession session = createSession(turboTestCompile, turboSignalPhase);
        session.setProjects(projects);
        
        try {
            // Create a dependency graph from the projects
            DefaultProjectDependencyGraph graph = new DefaultProjectDependencyGraph(projects);
            session.setProjectDependencyGraph(graph);
        } catch (Exception e) {
            // If graph creation fails, continue with null graph
            // Auto-detection will fall back to default behavior
        }
        
        return session;
    }

    private MavenProject createProject(String artifactId) {
        MavenProject project = new MavenProject();
        project.setArtifactId(artifactId);
        project.setGroupId("com.test");
        project.setVersion("1.0.0");
        return project;
    }
}
