package com.beanspringboot;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import java.io.File;

@Mojo(name = "audit", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class SentinelMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDir;

    public void execute() throws MojoExecutionException {
        getLog().info("🛡️ Spring Sentinel: Inizio analisi statica delle performance...");
        try {
            StaticAnalysisCore core = new StaticAnalysisCore(getLog());
            core.executeAnalysis(baseDir, outputDir);
        } catch (Exception e) {
            throw new MojoExecutionException("Errore durante l'analisi: " + e.getMessage(), e);
        }
    }
}