package com.beanspringboot;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

public class StaticAnalysisCore {
    private final Log log;
    private final MavenProject project;
    private final List<AuditIssue> issues = new ArrayList<>();

    // Parametri configurabili via Mojo (Feedback RepulsiveGoat3411)
    private int maxDependencies = 7;
    private String secretPattern = ".*(password|secret|apikey|pwd|token).*";

    public StaticAnalysisCore(Log log, MavenProject project) {
        this.log = log;
        this.project = project;
        configureJavaParser();
    }

    public StaticAnalysisCore(Log log) {
        this(log, null);
    }

    private void configureJavaParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        StaticJavaParser.setConfiguration(config);
    }

    // Setter per la configurazione dinamica dal Mojo
    public void setMaxDependencies(int maxDependencies) {
        this.maxDependencies = maxDependencies;
    }

    public void setSecretPattern(String secretPattern) {
        this.secretPattern = secretPattern;
    }

    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        AnalysisRules rules = new AnalysisRules(this.issues::add);

        // 1. Analisi Olistica del POM
        if (project != null) {
            log.info("Analisi olistica delle dipendenze Maven...");
            rules.runProjectChecks(project);
        }

        // 2. Analisi delle Properties
        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues);

        // 3. Analisi del Codice Java con parametri flessibili
        if (Files.exists(javaPath)) {
            try (Stream<Path> paths = Files.walk(javaPath)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        // Passiamo i parametri configurabili alle regole
                        rules.runAllChecks(cu, path.getFileName().toString(), props, maxDependencies, secretPattern);
                    } catch (IOException e) {
                        log.error("Errore nel parsing del file: " + path);
                    }
                });
            }
        }

        new ReportGenerator().generateReports(outputDir, issues);
    }

    public void executeAnalysisWithPropsOnly(Properties props, List<AuditIssue> issuesList) {
        checkOSIV(props, issuesList);
        checkPropertiesSecrets(props, issuesList, secretPattern);
        checkCriticalProperties(props, issuesList);
    }

    private void checkOSIV(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.jpa.open-in-view", "true"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Architecture", "OSIV is Enabled", "Disabilita spring.jpa.open-in-view per evitare problemi di performance."));
        }
    }

    private void checkPropertiesSecrets(Properties p, List<AuditIssue> issuesList, String pattern) {
        p.forEach((key, value) -> {
            String k = key.toString().toLowerCase();
            // Applichiamo la Regex flessibile anche alle properties
            if (k.matches(pattern) && !value.toString().matches("\\$\\{.*\\}")) {
                issuesList.add(new AuditIssue("application.properties", 0, "Security", "Hardcoded Secret", "Non scrivere segreti in chiaro. Usa le variabili d'ambiente."));
            }
        });
    }

    private void checkCriticalProperties(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.h2.console.enabled"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Security", "H2 Console Enabled", "Disabilita la console H2 in produzione."));
        }
    }

    private Properties loadProperties(Path resPath) {
        Properties props = new Properties();
        Path p = resPath.resolve("application.properties");
        if (Files.exists(p)) {
            try (var is = Files.newInputStream(p)) { props.load(is); } catch (IOException e) { log.error("Errore caricamento properties"); }
        }
        return props;
    }

    public static class AuditIssue {
        public final String file, type, reason, suggestion;
        public final int line;
        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f; this.line = l; this.type = t; this.reason = r; this.suggestion = s;
        }
    }
}