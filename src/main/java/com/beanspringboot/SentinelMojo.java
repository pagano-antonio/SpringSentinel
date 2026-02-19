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
 * v1.2.0: Supporto a profili XML e configurazione flessibile.
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

    // --- NUOVI PARAMETRI PER PROFILI E REGOLE (Feedback Reddit & Oliver Gierke) ---

    /**
     * Profilo di analisi da utilizzare. 
     * Opzioni predefinite: 'security-only', 'standard', 'strict'.
     */
    @Parameter(property = "sentinel.profile", defaultValue = "strict")
    private String profile;

    /**
     * File XML opzionale fornito dall'utente per definire regole personalizzate.
     */
    @Parameter(property = "sentinel.customRules")
    private File customRules;

    // --- PARAMETRI DI TUNING ---

    /**
     * Numero massimo di dipendenze iniettate prima di segnalare un "Fat Component".
     */
    @Parameter(property = "sentinel.maxDependencies", defaultValue = "7")
    private int maxDependencies;

    /**
     * Regex per identificare variabili che potrebbero contenere segreti hardcoded.
     */
    @Parameter(property = "sentinel.secretPattern", defaultValue = ".*(password|secret|apikey|pwd|token).*")
    private String secretPattern;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("🛡️ SpringSentinel: Avvio analisi (Profilo: " + profile + ")...");

        try {
            // 1. Inizializziamo il core
            StaticAnalysisCore core = new StaticAnalysisCore(getLog(), project);

            // 2. Passiamo le configurazioni dei profili
            core.setSelectedProfile(profile);
            core.setCustomRulesFile(customRules);

            // 3. Passiamo i parametri di tuning
            core.setMaxDependencies(maxDependencies);
            core.setSecretPattern(secretPattern);

            // 4. Eseguiamo l'analisi nel report directory (target/spring-sentinel-reports)
            File sentinelOutputDir = new File(outputDir, "spring-sentinel-reports");
            core.executeAnalysis(baseDir, sentinelOutputDir);

            getLog().info("✅ Analisi completata. Report generato in: " + sentinelOutputDir);
            
        } catch (Exception e) {
            getLog().error("❌ Errore durante l'esecuzione di SpringSentinel", e);
            throw new MojoExecutionException("Errore nel processo di analisi statica: " + e.getMessage(), e);
        }
    }
}