package com.beanspringboot;

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
 * Mojo principale per l'esecuzione dell'audit di SpringSentinel.
 * v1.3.0: Aggiunto supporto al Build Breaker (failOnError).
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

    // --- NUOVO PARAMETRO BUILD BREAKER ---

    /**
     * Se impostato a true, la build Maven fallirà se vengono trovati degli errori.
     */
    @Parameter(property = "sentinel.failOnError", defaultValue = "false")
    private boolean failOnError;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
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

            // 5. Logica Build Breaker
            List<StaticAnalysisCore.AuditIssue> issues = core.getIssues();
            
            if (failOnError && !issues.isEmpty()) {
                getLog().error("🚨 SpringSentinel ha trovato " + issues.size() + " violazioni nel codice!");
                getLog().error("🚨 Controlla il report HTML in " + sentinelOutputDir + " per i dettagli.");
                
                // Questa eccezione dice a Maven di stampare "BUILD FAILURE"
                throw new MojoFailureException("Build interrotta a causa di " + issues.size() + " violazioni rilevate da SpringSentinel.");
            }

        } catch (MojoFailureException e) {
            // Rilanciamo l'eccezione di fallimento così Maven la gestisce correttamente come BUILD FAILURE
            throw e;
        } catch (Exception e) {
            getLog().error("❌ Errore durante l'esecuzione di SpringSentinel", e);
            throw new MojoExecutionException("Errore nel processo di analisi statica: " + e.getMessage(), e);
        }
    }
}