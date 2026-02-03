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

    // Costruttore per il Mojo (con MavenProject per analisi olistica)
    public StaticAnalysisCore(Log log, MavenProject project) {
        this.log = log;
        this.project = project;
        configureJavaParser();
    }

    // Costruttore per i Test Unitari (Project può essere null)
    public StaticAnalysisCore(Log log) {
        this(log, null);
    }

    private void configureJavaParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        StaticJavaParser.setConfiguration(config);
    }

    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        // 1. Analisi del POM (Analisi Olistica)
        AnalysisRules rules = new AnalysisRules(this.issues::add);
        if (project != null) {
            log.info("Analisi olistica del file pom.xml...");
            rules.runProjectChecks(project);
        }

        // 2. Analisi delle Properties
        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues);

        // 3. Analisi del Codice Java
        if (Files.exists(javaPath)) {
            try (Stream<Path> paths = Files.walk(javaPath)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(path);
                        rules.runAllChecks(cu, path.getFileName().toString(), props);
                    } catch (IOException e) {
                        log.error("Error parsing: " + path);
                    }
                });
            }
        }

        // 4. Generazione Report
        new ReportGenerator().generateReports(outputDir, issues);
    }

    public void executeAnalysisWithPropsOnly(Properties props, List<AuditIssue> issuesList) {
        checkOSIV(props, issuesList);
        checkPropertiesSecrets(props, issuesList);
        checkCriticalProperties(props, issuesList);
    }

    private void checkOSIV(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.jpa.open-in-view", "true"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Architecture", "OSIV is Enabled", "Set spring.jpa.open-in-view=false."));
        }
    }

    private void checkPropertiesSecrets(Properties p, List<AuditIssue> issuesList) {
        p.forEach((key, value) -> {
            String k = key.toString().toLowerCase();
            if ((k.contains("password") || k.contains("secret") || k.contains("apikey")) && !value.toString().matches("\\$\\{.*\\}")) {
                issuesList.add(new AuditIssue("application.properties", 0, "Security", "Hardcoded Secret", "Use env variables."));
            }
        });
    }

    private void checkCriticalProperties(Properties p, List<AuditIssue> issuesList) {
        // Controllo H2 Console in produzione (Olistico)
        if ("true".equals(p.getProperty("spring.h2.console.enabled"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Security", "H2 Console Enabled", "Disable H2 console in production for security."));
        }
    }

    private Properties loadProperties(Path resPath) {
        Properties props = new Properties();
        Path p = resPath.resolve("application.properties");
        if (Files.exists(p)) {
            try (var is = Files.newInputStream(p)) { 
                props.load(is); 
            } catch (IOException e) { 
                log.error("Props error"); 
            }
        }
        return props;
    }

    public List<AuditIssue> getIssues() {
        return new ArrayList<>(issues);
    }

    public static class AuditIssue {
        public final String file, type, reason, suggestion;
        public final int line;
        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f; this.line = l; this.type = t; this.reason = r; this.suggestion = s;
        }
    }
}