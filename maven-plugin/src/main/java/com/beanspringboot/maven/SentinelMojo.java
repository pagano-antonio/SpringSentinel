package com.beanspringboot.maven;

import com.beanspringboot.ProjectInfo;
import com.beanspringboot.StaticAnalysisCore;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.List;

/**
 * Maven entry point for SpringSentinel.
 */
@Mojo(name = "audit", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class SentinelMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File outputDir;

    @Parameter(property = "sentinel.profile", defaultValue = "strict")
    private String profile;

    @Parameter(property = "sentinel.customRules")
    private File customRules;

    @Parameter(property = "sentinel.maxDependencies", defaultValue = "7")
    private int maxDependencies;

    @Parameter(property = "sentinel.secretPattern", defaultValue = ".*(password|secret|apikey|pwd|token).*")
    private String secretPattern;

    @Parameter(property = "sentinel.language", defaultValue = "it")
    private String language;

    @Parameter(property = "sentinel.failOnError", defaultValue = "false")
    private boolean failOnError;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File sentinelOutputDir = new File(outputDir, "spring-sentinel-reports");
        getLog().info("SpringSentinel: starting analysis with profile '" + profile + "'");

        try {
            StaticAnalysisCore core = new StaticAnalysisCore(getLog()::info);
            core.setProjectInfo(toProjectInfo(project));
            core.setSelectedProfile(profile);
            core.setCustomRulesFile(customRules);
            core.setMaxDependencies(maxDependencies);
            core.setSecretPattern(secretPattern);
            core.setLanguage(language);
            core.executeAnalysis(baseDir, sentinelOutputDir);

            List<StaticAnalysisCore.AuditIssue> issues = core.getIssues();
            getLog().info("SpringSentinel: report generated in " + sentinelOutputDir);

            if (failOnError && !issues.isEmpty()) {
                throw new MojoFailureException(
                        "SpringSentinel found " + issues.size() + " issue(s). See " + sentinelOutputDir + ".");
            }
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("SpringSentinel analysis failed: " + e.getMessage(), e);
        }
    }

    private static ProjectInfo toProjectInfo(MavenProject project) {
        ProjectInfo info = new ProjectInfo();
        info.dependencies = project.getDependencies().stream()
                .map(Dependency::getArtifactId)
                .toList();
        info.plugins = project.getBuildPlugins().stream()
                .map(Plugin::getArtifactId)
                .toList();
        return info;
    }
}
