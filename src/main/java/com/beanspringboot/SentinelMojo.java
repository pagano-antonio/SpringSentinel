package com.beanspringboot;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;

/**
 * Mojo principale per l'esecuzione dell'audit di SpringSentinel.
 * Marcato come threadSafe per supportare build parallele.
 */
@Mojo(name = "audit", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class SentinelMojo extends AbstractMojo {

    // --- PARAMETRI DI PROGETTO ---

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDir;

    // --- PARAMETRI CONFIGURABILI (Feedback RepulsiveGoat3411) ---

    /**
     * Numero massimo di dipendenze iniettate consentite prima di segnalare un "Fat Component".
     */
    @Parameter(property = "sentinel.maxDependencies", defaultValue = "7")
    private int maxDependencies;

    /**
     * Regex per identificare nomi di variabili che potrebbero contenere segreti hardcoded.
     */
    @Parameter(property = "sentinel.secretPattern", defaultValue = ".*(password|secret|apikey|pwd|token).*")
    private String secretPattern;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("🛡️ SpringSentinel: Avvio analisi olistica del progetto...");

        try {
            // Inizializziamo il core passando log e progetto Maven
            StaticAnalysisCore core = new StaticAnalysisCore(getLog(), project);

            // Passiamo le configurazioni personalizzate dell'utente
            core.setMaxDependencies(maxDependencies);
            core.setSecretPattern(secretPattern);

            // Eseguiamo l'analisi
            core.executeAnalysis(baseDir, outputDir);
            getLog().info("✅ Analisi completata con successo. Report generato in: " + outputDir);
            
        } catch (Exception e) {
            getLog().error("❌ Errore durante l'esecuzione di SpringSentinel", e);
            throw new MojoExecutionException("Errore nel processo di analisi statica: " + e.getMessage(), e);
        }
    }
}