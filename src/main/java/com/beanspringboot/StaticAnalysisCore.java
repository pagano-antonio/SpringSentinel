package com.beanspringboot;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.apache.maven.plugin.logging.Log;

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
    private final List<AuditIssue> issues = new ArrayList<>();

    public StaticAnalysisCore(Log log) {
        this.log = log;
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        StaticJavaParser.setConfiguration(config);
    }

    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        if (!Files.exists(javaPath)) return;

        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues);

        AnalysisRules rules = new AnalysisRules(this.issues::add);

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

        new ReportGenerator().generateReports(outputDir, issues);
    }

    public void executeAnalysisWithPropsOnly(Properties props, List<AuditIssue> issuesList) {
        checkOSIV(props, issuesList);
        checkPropertiesSecrets(props, issuesList);
    }

    private void checkOSIV(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.jpa.open-in-view", "true"))) {
            issuesList.add(new AuditIssue("application.properties", 0, "Architecture", "OSIV is Enabled", "Set spring.jpa.open-in-view=false."));
        }
    }

    private void checkPropertiesSecrets(Properties p, List<AuditIssue> issuesList) {
        p.forEach((key, value) -> {
            String k = key.toString().toLowerCase();
            if ((k.contains("password") || k.contains("secret")) && !value.toString().matches("\\$\\{.*\\}")) {
                issuesList.add(new AuditIssue("application.properties", 0, "Security", "Hardcoded Secret", "Use env variables."));
            }
        });
    }

    private Properties loadProperties(Path resPath) {
        Properties props = new Properties();
        Path p = resPath.resolve("application.properties");
        if (Files.exists(p)) {
            try (var is = Files.newInputStream(p)) { props.load(is); } catch (IOException e) { log.error("Props error"); }
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