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

/**
 * Core engine for SpringSentinel static analysis.
 * v1.3.0: Integrated ResolvedConfig for hierarchical parameter management.
 */
public class StaticAnalysisCore {
    private final Log log;
    private final MavenProject project;
    private final List<AuditIssue> issues = new ArrayList<>();

    // Parametri dal Maven Mojo
    private String selectedProfile = "strict"; 
    private File customRulesFile;
    private int maxDependencies = 7; // Default Mojo
    private String secretPattern = ".*(password|secret|apikey|pwd|token).*"; // Default Mojo

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
        try {
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf("JAVA_21"));
            log.info("JavaParser configured for Language Level: JAVA_21");
        } catch (IllegalArgumentException e) {
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            log.warn("JAVA_21 not found in constants. Falling back to JAVA_17.");
        }
        StaticJavaParser.setConfiguration(config);
    }

    // Setter per parametri dal Maven Mojo
    public void setSelectedProfile(String profile) { this.selectedProfile = profile; }
    public void setCustomRulesFile(File file) { this.customRulesFile = file; }
    public void setMaxDependencies(int maxDependencies) { this.maxDependencies = maxDependencies; }
    public void setSecretPattern(String secretPattern) { this.secretPattern = secretPattern; }

    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        // 1. Caricamento della configurazione risolta (XML + Gerarchia Profili)
        log.info("Loading analysis profile: " + selectedProfile);
        RuleConfigLoader configLoader = new RuleConfigLoader(log);
        ResolvedConfig config = configLoader.loadActiveRules(customRulesFile, selectedProfile);

        // 2. APPLICA OVERRIDE DA POM.XML (Se l'utente ha configurato il plugin nel POM)
        // Se i valori sono diversi dai default del Mojo, l'utente vuole forzarli
        if (maxDependencies != 7) {
            log.info("Applying POM override: maxDependencies = " + maxDependencies);
            config.overrideParameter("ARCH-003", "maxDependencies", String.valueOf(maxDependencies));
        }
        if (!secretPattern.equals(".*(password|secret|apikey|pwd|token).*")) {
            log.info("Applying POM override: secretPattern custom detected");
            config.overrideParameter("SEC-001", "pattern", secretPattern);
        }

        // Inizializza AnalysisRules con la configurazione risolta
        AnalysisRules rules = new AnalysisRules(this.issues::add, config);

        // 3. Holistic POM Analysis
        if (project != null) {
            log.info("Starting project-level audit...");
            rules.runProjectChecks(project);
        }

        // 4. Properties Analysis
        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues, config);

        // 5. Java Source Code Analysis
        if (Files.exists(javaPath)) {
            log.info("Scanning Java source files with " + config.getActiveRules().size() + " active rules...");
            try (Stream<Path> paths = Files.walk(javaPath)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        // runAllChecks ora è più pulito, non serve passare maxDeps e pattern
                        rules.runAllChecks(cu, path.getFileName().toString(), props);
                    } catch (Exception e) {
                        log.error("Parsing failed for " + path.getFileName() + ": " + e.getMessage());
                    }
                });
            }
        }

        // 6. Report Generation
        new ReportGenerator().generateReports(outputDir, issues, selectedProfile);
    }

    public void executeAnalysisWithPropsOnly(Properties props, List<AuditIssue> issuesList, ResolvedConfig config) {
        // Recupera il pattern (potrebbe essere quello di default, quello del profilo o l'override del POM)
        String pattern = config.getParameter("SEC-001", "pattern", secretPattern);

        if (config.getActiveRules().contains("ARCH-OSIV")) checkOSIV(props, issuesList);
        if (config.getActiveRules().contains("SEC-001")) checkPropertiesSecrets(props, issuesList, pattern);
        if (config.getActiveRules().contains("SEC-H2")) checkCriticalProperties(props, issuesList);
    }

    private void checkOSIV(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.jpa.open-in-view", "true"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Architecture", "OSIV is Enabled", 
                "Disable 'spring.jpa.open-in-view' to prevent the Open Session In View anti-pattern."));
        }
    }

    private void checkPropertiesSecrets(Properties p, List<AuditIssue> issuesList, String pattern) {
        p.forEach((key, value) -> {
            String k = key.toString().toLowerCase();
            if (k.matches(pattern) && !value.toString().matches("\\$\\{.*\\}")) {
                issuesList.add(new AuditIssue("application.properties", 0, "Security", "Hardcoded Secret", 
                    "Sensitive data found in properties key '" + k + "'. Move to environment variables."));
            }
        });
    }

    private void checkCriticalProperties(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.h2.console.enabled"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Security", "H2 Console Enabled", 
                "H2 Console is active. Disable it in production."));
        }
    }

    private Properties loadProperties(Path resPath) {
        Properties props = new Properties();
        Path p = resPath.resolve("application.properties");
        if (Files.exists(p)) {
            try (var is = Files.newInputStream(p)) { 
                props.load(is); 
            } catch (IOException e) { 
                log.error("Could not load application.properties"); 
            }
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