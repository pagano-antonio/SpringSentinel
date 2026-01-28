package com.beanspringboot;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

public class StaticAnalysisCore {
    private final Log log;
    private final List<AuditIssue> issues = new ArrayList<>();
    private static final List<String> BLOCKING_CALLS = List.of("resttemplate", "webclient", "feignclient", "httpclient",
            "thread.sleep");

    public StaticAnalysisCore(Log log) {
        this.log = log;
    }

    /**
     * Getter per permettere ai test JUnit di verificare le anomalie trovate.
     */
    public List<AuditIssue> getIssues() {
        return new ArrayList<>(issues);
    }

    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        if (!Files.exists(javaPath)) {
            log.warn("Source folder src/main/java not found. Skipping analysis.");
            return;
        }

        Properties props = loadProperties(resPath);
        checkOSIV(props);
        checkPropertiesSecrets(props);

        try (Stream<Path> paths = Files.walk(javaPath)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(path);
                            String fileName = path.getFileName().toString();
                            runFileChecks(cu, fileName, props);
                        } catch (IOException e) {
                            log.error("Error parsing file: " + path.getFileName());
                        }
                    });
        }

        generateHtmlReport(outputDir);
        generateJsonReport(outputDir);
    }

    /**
     * Metodo visibile per i test che raggruppa tutti i controlli su un singolo
     * file.
     */
    protected void runFileChecks(CompilationUnit cu, String fileName, Properties props) {
        checkJPAEager(cu, fileName);
        checkNPlusOne(cu, fileName);
        checkBlockingTransactional(cu, fileName);
        checkManualThreads(cu, fileName);
        checkCacheTTL(cu, fileName, props);
        checkTransactionTimeout(cu, fileName);
        checkMissingRepositoryAnnotation(cu, fileName);
        checkAutowiredFieldInjection(cu, fileName);
        checkHardcodedSecrets(cu, fileName);
        checkCrossOriginWildcard(cu, fileName);
        checkMissingResponseEntity(cu, fileName);
    }

    // --- METODI DI CHECK (Resi protected per il testing) ---

    protected void checkJPAEager(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getAnnotations().forEach(anno -> {
                if (anno.toString().contains("FetchType.EAGER")) {
                    addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "JPA Performance",
                            "EAGER Fetching detected", "Switch to LAZY fetching.");
                }
            });
        });
    }

    protected void checkNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(loop -> {
            loop.findAll(MethodCallExpr.class).forEach(call -> {
                if (call.getNameAsString().startsWith("get")
                        && (call.getNameAsString().endsWith("s") || call.getNameAsString().endsWith("List"))) {
                    addIssue(f, call.getBegin().map(p -> p.line).orElse(0), "Database", "Potential N+1 Query",
                            "Use JOIN FETCH.");
                }
            });
        });
    }

    protected void checkBlockingTransactional(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.isAnnotationPresent("Transactional"))
                .forEach(m -> {
                    m.findAll(MethodCallExpr.class).forEach(call -> {
                        if (BLOCKING_CALLS.stream().anyMatch(b -> call.toString().toLowerCase().contains(b))) {
                            addIssue(f, call.getBegin().map(p -> p.line).orElse(0), "Concurrency",
                                    "Blocking call in Transaction", "Move I/O out of @Transactional.");
                        }
                    });
                });
    }

    protected void checkManualThreads(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread")) {
                addIssue(f, n.getBegin().map(p -> p.line).orElse(0), "Resource Mgmt", "Manual Thread creation",
                        "Use @Async.");
            }
        });
    }

    protected void checkCacheTTL(CompilationUnit cu, String f, Properties p) {
        if (!cu.findAll(MethodDeclaration.class).stream().filter(m -> m.isAnnotationPresent("Cacheable")).toList()
                .isEmpty()) {
            boolean hasTTL = p.keySet().stream()
                    .anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));
            if (!hasTTL) {
                addIssue(f, 0, "Caching", "Cache missing TTL", "Define expiration in application.properties.");
            }
        }
    }

    protected void checkOSIV(Properties p) {
        String osiv = p.getProperty("spring.jpa.open-in-view", "true");
        if ("true".equals(osiv)) {
            addIssue("application.properties", 0, "Architecture", "OSIV is Enabled",
                    "Set spring.jpa.open-in-view=false.");
        }
    }

    protected void checkTransactionTimeout(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.getAnnotationByName("Transactional").ifPresent(a -> {
                if (!a.toString().contains("timeout")) {
                    addIssue(f, m.getBegin().map(p -> p.line).orElse(0), "Resilience", "Missing Transaction Timeout",
                            "Add a timeout value.");
                }
            });
        });
    }

    protected void checkMissingRepositoryAnnotation(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().endsWith("Repository"))
                .forEach(c -> {
                    if (!c.isAnnotationPresent("Repository")) {
                        addIssue(f, c.getBegin().map(p -> p.line).orElse(0), "Best Practice", "Missing @Repository",
                                "Add @Repository annotation.");
                    }
                });
    }

    protected void checkAutowiredFieldInjection(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Autowired")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Architecture", "Field Injection",
                        "Use constructor injection.");
            }
        });
    }

    protected void checkHardcodedSecrets(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            String name = field.getVariable(0).getNameAsString().toLowerCase();
            if (name.contains("password") || name.contains("secret") || name.contains("apikey")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Security", "Hardcoded Secret",
                        "Use env variables.");
            }
        });
    }

    protected void checkCrossOriginWildcard(CompilationUnit cu, String f) {
        cu.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream()
                .filter(a -> a.getNameAsString().equals("CrossOrigin"))
                .forEach(a -> {
                    String annotationStr = a.toString();
                    // Copre i casi: @CrossOrigin, @CrossOrigin(), @CrossOrigin("*"),
                    // @CrossOrigin(origins="*")
                    if (annotationStr.equals("@CrossOrigin") || annotationStr.equals("@CrossOrigin()")
                            || annotationStr.contains("\"*\"")) {
                        addIssue(f, a.getBegin().map(p -> p.line).orElse(0), "Security",
                                "Insecure @CrossOrigin policy",
                                "Avoid using wildcard '*' and specify allowed origins explicitly.");
                    }
                });
    }

    protected void checkMissingResponseEntity(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.isAnnotationPresent("RestController"))
                .forEach(controller -> {
                    controller.findAll(MethodDeclaration.class).stream()
                            .filter(m -> m.getAnnotations().stream()
                                    .anyMatch(a -> a.getNameAsString().endsWith("Mapping")))
                            .filter(m -> !m.getType().asString().startsWith("ResponseEntity"))
                            .forEach(m -> addIssue(f, m.getBegin().map(p -> p.line).orElse(0), "Best Practice",
                                    "Missing ResponseEntity",
                                    "Use ResponseEntity to have full control over the HTTP response."));
                });
    }

    protected void checkPropertiesSecrets(Properties p) {
        p.forEach((key, value) -> {
            String keyStr = key.toString().toLowerCase();
            String valueStr = value.toString();
            if ((keyStr.contains("password") || keyStr.contains("secret") || keyStr.contains("apikey"))
                    && !valueStr.matches("\\$\\{.*\\}")) {
                addIssue("application.properties", 0, "Security", "Hardcoded Secret in properties",
                        "Do not store secrets in application.properties. Use environment variables or a secret manager.");
            }
        });
    }

    // --- GENERAZIONE REPORT ---

    private void generateJsonReport(File outputDir) throws IOException {
        Path reportDir = outputDir.toPath().resolve("spring-sentinel-reports");
        Files.createDirectories(reportDir);
        File jsonFile = reportDir.resolve("report.json").toFile();

        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write("{\n  \"totalIssues\": " + issues.size() + ",\n  \"issues\": [\n");
            for (int i = 0; i < issues.size(); i++) {
                AuditIssue issue = issues.get(i);
                writer.write("    {\n");
                writer.write("      \"file\": \"" + issue.file + "\",\n");
                writer.write("      \"line\": " + issue.line + ",\n");
                writer.write("      \"type\": \"" + issue.type + "\",\n");
                writer.write("      \"reason\": \"" + issue.reason + "\",\n");
                writer.write("      \"suggestion\": \"" + issue.suggestion + "\"\n");
                writer.write("    }" + (i < issues.size() - 1 ? "," : "") + "\n");
            }
            writer.write("  ]\n}");
        }
        log.info("JSON Report generated: " + jsonFile.getAbsolutePath());
    }

    private void generateHtmlReport(File outputDir) throws IOException {
        Path reportDir = outputDir.toPath().resolve("spring-sentinel-reports");
        Files.createDirectories(reportDir);
        File htmlFile = reportDir.resolve("report.html").toFile();

        try (FileWriter writer = new FileWriter(htmlFile)) {
            writer.write("<html><head><title>Spring Sentinel Report</title><style>");
            writer.write("body{font-family:'Segoe UI',sans-serif;background:#f4f7f6;padding:30px;}");
            writer.write(
                    ".card{background:white;padding:20px;border-radius:8px;margin-bottom:15px;border-left:5px solid #e74c3c;box-shadow:0 2px 5px rgba(0,0,0,0.1);}");
            writer.write(
                    "h1{color:#2c3e50;} .tag{background:#34495e;color:white;padding:3px 8px;border-radius:4px;font-size:12px;}");
            writer.write("</style></head><body><h1>🛡️ Spring Sentinel Audit Report</h1>");
            writer.write("<p>Total issues found: <b>" + issues.size() + "</b></p>");
            for (AuditIssue i : issues) {
                writer.write("<div class='card'><span class='tag'>" + i.type + "</span>");
                writer.write("<h3>" + i.reason + "</h3><p><b>Location:</b> " + i.file + " (Line: " + i.line + ")</p>");
                writer.write("<p><b>Fix:</b> " + i.suggestion + "</p></div>");
            }
            writer.write("</body></html>");
        }
    }

    private void addIssue(String f, int l, String t, String r, String s) {
        boolean isDuplicate = issues.stream().anyMatch(i -> i.file.equals(f) && i.reason.equals(r) && i.line == l);
        if (!isDuplicate) {
            issues.add(new AuditIssue(f, l, t, r, s));
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

    /**
     * Classe interna per rappresentare un'anomalia trovata.
     */
    public static class AuditIssue {
        public final String file, type, reason, suggestion;
        public final int line;

        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f;
            this.line = l;
            this.type = t;
            this.reason = r;
            this.suggestion = s;
        }
    }
}