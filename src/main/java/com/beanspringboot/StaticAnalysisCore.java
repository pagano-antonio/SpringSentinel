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
 * Orchestrates POM, Properties, and Java Source scans.
 */
public class StaticAnalysisCore {
    private final Log log;
    private final MavenProject project;
    private final List<AuditIssue> issues = new ArrayList<>();

    // Configurable parameters from Maven Plugin configuration
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

    public void setMaxDependencies(int maxDependencies) {
        this.maxDependencies = maxDependencies;
    }

    public void setSecretPattern(String secretPattern) {
        this.secretPattern = secretPattern;
    }

    /**
     * Entry point for the analysis process.
     */
    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        AnalysisRules rules = new AnalysisRules(this.issues::add);

        // 1. Holistic POM/Project Analysis
        if (project != null) {
            log.info("Starting holistic project analysis (POM and dependencies)...");
            rules.runProjectChecks(project);
        }

        // 2. Properties Analysis
        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues);

        // 3. Java Source Code Analysis
        if (Files.exists(javaPath)) {
            log.info("Scanning Java source files for best practices and security...");
            try (Stream<Path> paths = Files.walk(javaPath)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        // Run all rules including new REST and architectural checks
                        rules.runAllChecks(cu, path.getFileName().toString(), props, maxDependencies, secretPattern);
                    } catch (IOException e) {
                        log.error("Failed to parse Java file: " + path);
                    }
                });
            }
        }

        // 4. Report Generation
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
                    "Sensitive data found in properties. Move to environment variables or a secure Vault."));
            }
        });
    }

    private void checkCriticalProperties(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.h2.console.enabled"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Security", "H2 Console Enabled", 
                "H2 Console is active. Ensure it is disabled in production environments."));
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

    /**
     * Data class representing a found issue.
     */
    public static class AuditIssue {
        public final String file, type, reason, suggestion;
        public final int line;
        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f; this.line = l; this.type = t; this.reason = r; this.suggestion = s;
        }
    }
}