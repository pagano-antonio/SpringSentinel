package com.beanspringboot;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

@Mojo(name = "audit", defaultPhase = LifecyclePhase.VERIFY)
public class SentinelMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File projectBaseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("🛡️  Spring Sentinel Audit is starting...");
        
        try {
            // Passiamo il controllo al core dell'analizzatore
            StaticAnalysisCore core = new StaticAnalysisCore(getLog());
            core.executeAnalysis(projectBaseDir, outputDirectory);
            
            getLog().info("✅ Audit complete! Reports generated in: " + outputDirectory.getAbsolutePath() + "/spring-sentinel-reports/");
        } catch (Exception e) {
            throw new MojoExecutionException("Error during Spring Sentinel audit execution", e);
        }
    }
}