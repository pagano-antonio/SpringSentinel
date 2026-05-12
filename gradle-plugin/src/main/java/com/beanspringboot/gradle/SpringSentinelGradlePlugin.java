package com.beanspringboot.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class SpringSentinelGradlePlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "springSentinel";
    public static final String TASK_NAME = "springSentinelAudit";

    @Override
    public void apply(Project project) {
        SpringSentinelExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, SpringSentinelExtension.class);

        extension.getProfile().convention("strict");
        extension.getMaxDependencies().convention(7);
        extension.getSecretPattern().convention(".*(password|secret|apikey|pwd|token).*");
        extension.getFailOnError().convention(false);

        project.getTasks().register(TASK_NAME, SpringSentinelTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs SpringSentinel static analysis.");
            task.getBaseDir().convention(project.getLayout().getProjectDirectory());
            task.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("spring-sentinel-reports"));
            task.getProfile().convention(extension.getProfile());
            task.getCustomRules().convention(extension.getCustomRules());
            task.getMaxDependencies().convention(extension.getMaxDependencies());
            task.getSecretPattern().convention(extension.getSecretPattern());
            task.getFailOnError().convention(extension.getFailOnError());
        });
    }
}
