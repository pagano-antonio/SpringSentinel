package com.beanspringboot.gradle;

import com.beanspringboot.ProjectInfo;
import com.beanspringboot.StaticAnalysisCore;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@DisableCachingByDefault(because = "Static analysis reads project source files and build metadata dynamically.")
public abstract class SpringSentinelTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getBaseDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getProfile();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCustomRules();

    @Input
    public abstract Property<Integer> getMaxDependencies();

    @Input
    public abstract Property<String> getSecretPattern();

    @Input
    public abstract Property<Boolean> getFailOnError();

    @TaskAction
    public void audit() {
        File baseDir = getBaseDir().get().getAsFile();
        File outputDir = getOutputDir().get().getAsFile();

        try {
            StaticAnalysisCore core = new StaticAnalysisCore(message -> getLogger().lifecycle(message));
            core.setProjectInfo(toProjectInfo(getProject()));
            core.setSelectedProfile(getProfile().get());
            if (getCustomRules().isPresent()) {
                core.setCustomRulesFile(getCustomRules().get().getAsFile());
            }
            core.setMaxDependencies(getMaxDependencies().get());
            core.setSecretPattern(getSecretPattern().get());
            core.executeAnalysis(baseDir, outputDir);

            List<StaticAnalysisCore.AuditIssue> issues = core.getIssues();
            getLogger().lifecycle("SpringSentinel: report generated in {}", outputDir);

            if (getFailOnError().get() && !issues.isEmpty()) {
                throw new GradleException(
                        "SpringSentinel found " + issues.size() + " issue(s). See " + outputDir + ".");
            }
        } catch (GradleException e) {
            throw e;
        } catch (Exception e) {
            throw new GradleException("SpringSentinel analysis failed: " + e.getMessage(), e);
        }
    }

    private static ProjectInfo toProjectInfo(Project project) {
        ProjectInfo info = new ProjectInfo();
        info.dependencies = dependencyArtifactIds(project);
        info.plugins = pluginNames(project.getPlugins());
        return info;
    }

    private static List<String> dependencyArtifactIds(Project project) {
        Set<String> artifactIds = new LinkedHashSet<>();

        for (Configuration configuration : project.getConfigurations()) {
            for (Dependency dependency : configuration.getDependencies()) {
                if (dependency.getName() != null) {
                    artifactIds.add(dependency.getName());
                }
            }
        }

        return new ArrayList<>(artifactIds);
    }

    private static List<String> pluginNames(PluginContainer plugins) {
        Set<String> names = new LinkedHashSet<>();

        plugins.forEach(plugin -> {
            String className = plugin.getClass().getName();
            String normalizedClassName = className.toLowerCase(Locale.ROOT);
            names.add(className);

            if (normalizedClassName.contains("springboot") || normalizedClassName.contains("spring.boot")) {
                names.add("spring-boot");
            }
        });

        return new ArrayList<>(names);
    }
}
