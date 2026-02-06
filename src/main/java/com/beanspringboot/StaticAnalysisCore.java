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
 * v1.2.0: Robust Java 21+ support for unnamed variables and modern syntax.
 */
public class StaticAnalysisCore {
    private final Log log;
    private final MavenProject project;
    private final List<AuditIssue> issues = new ArrayList<>();

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

    /**
     * Configures the parser level. 
     * Uses dynamic resolution to avoid compilation errors if JAVA_21 is not yet in the classpath.
     */
    private void configureJavaParser() {
        ParserConfiguration config = new ParserConfiguration();
        try {
            // Tentativo di caricare JAVA_21 per supportare le unnamed variables (_)
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf("JAVA_21"));
            log.info("JavaParser configured for Language Level: JAVA_21");
        } catch (IllegalArgumentException e) {
            // Fallback a JAVA_17 se la costante non esiste nella versione corrente della lib
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            log.warn("JAVA_21 not found in JavaParser constants. Falling back to JAVA_17.");
        }
        StaticJavaParser.setConfiguration(config);
    }

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

        // 1. Holistic POM Analysis
        if (project != null) {
            log.info("Starting holistic project analysis...");
            rules.runProjectChecks(project);
        }

        // 2. Properties Analysis
        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues);

        // 3. Java Source Code Analysis
        if (Files.exists(javaPath)) {
            log.info("Scanning Java source files...");
            try (Stream<Path> paths = Files.walk(javaPath)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        rules.runAllChecks(cu, path.getFileName().toString(), props, maxDependencies, secretPattern);
                    } catch (Exception e) {
                        // Risolve la Issue #2: se un file fallisce (es. Java 25), il plugin continua
                        log.error("Parsing failed for " + path.getFileName() + ": " + e.getMessage());
                    }
                });
            }
        }

        // 4. Multi-format Report Generation (HTML, JSON, SARIF)
        new ReportGenerator().generateReports(outputDir, issues);
    }

    public void executeAnalysisWithPropsOnly(Properties props, List<AuditIssue> issuesList) {
        checkOSIV(props, issuesList);
        checkPropertiesSecrets(props, issuesList, secretPattern);
        checkCriticalProperties(props, issuesList);
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
                    "Sensitive data found in properties. Move to environment variables."));
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