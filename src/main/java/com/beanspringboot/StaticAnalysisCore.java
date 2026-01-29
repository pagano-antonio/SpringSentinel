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
        checkBeanScopesAndThreadSafety(cu, fileName);
        checkFatComponents(cu, fileName);        // Nuovo
        checkLazyInjectionSmell(cu, fileName);   // Nuovo
    }

    // --- NUOVI CONTROLLI: GRAPH & ARCHITECTURE ---

    protected void checkFatComponents(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            // Conta field injection
            long injectedFields = clazz.findAll(FieldDeclaration.class).stream()
                    .filter(this::isInjectedField).count();
            
            // Conta constructor injection
            int constructorParams = clazz.getConstructors().stream()
                    .mapToInt(c -> c.getParameters().size())
                    .max().orElse(0);

            int totalDeps = (int) injectedFields + constructorParams;

            if (totalDeps > 7) {
                addIssue(f, clazz.getBegin().map(p -> p.line).orElse(0), "Architecture",
                        "Fat Component Detected",
                        "This class has " + totalDeps + " dependencies. Consider refactoring into smaller services to improve startup time and maintainability.");
            }
        });
    }

    protected void checkLazyInjectionSmell(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Lazy") && field.isAnnotationPresent("Autowired")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Design Smell",
                        "Lazy Injection Detected",
                        "Using @Lazy on @Autowired fields often hides Circular Dependencies. Try to decouple your beans instead.");
            }
        });
    }

    // --- CONTROLLO SCOPES E THREAD SAFETY ---

    protected void checkBeanScopesAndThreadSafety(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (isSpringComponent(clazz)) {
                clazz.findAll(FieldDeclaration.class).forEach(field -> {
                    if (!field.isFinal() && !isInjectedField(field) && !field.isStatic()) {
                        addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Thread Safety",
                                "Mutable state in Singleton",
                                "Fields in Singletons (@Service, @RestController) should be final. If the bean must hold state, consider @Scope(\"prototype\").");
                    }
                });
            }

            clazz.getAnnotationByName("Scope").ifPresent(anno -> {
                String val = anno.toString().toLowerCase();
                if ((val.contains("prototype") || val.contains("request") || val.contains("session"))
                        && !val.contains("proxymode")) {
                    addIssue(f, clazz.getBegin().map(p -> p.line).orElse(0), "Architecture",
                            "Unsafe Scoped Bean Injection",
                            "Short-lived scopes require proxyMode = ScopedProxyMode.TARGET_CLASS when injected into Singletons.");
                }
            });
        });
    }

    // --- METODI DI SUPPORTO ---

    private boolean isSpringComponent(ClassOrInterfaceDeclaration clazz) {
        return clazz.isAnnotationPresent("Service") || clazz.isAnnotationPresent("RestController") ||
               clazz.isAnnotationPresent("Component") || clazz.isAnnotationPresent("Repository");
    }

    private boolean isInjectedField(FieldDeclaration field) {
        return field.isAnnotationPresent("Autowired") || field.isAnnotationPresent("Value") ||
               field.isAnnotationPresent("Resource") || field.isAnnotationPresent("Inject");
    }

    // --- METODI DI CHECK ESISTENTI (SINTETIZZATI) ---

    protected void checkJPAEager(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getAnnotations().forEach(anno -> {
                if (anno.toString().contains("FetchType.EAGER")) {
                    addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "JPA Performance", "EAGER Fetching detected", "Switch to LAZY fetching.");
                }
            });
        });
    }

    protected void checkNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(loop -> {
            loop.findAll(MethodCallExpr.class).forEach(call -> {
                if (call.getNameAsString().startsWith("get") && (call.getNameAsString().endsWith("s") || call.getNameAsString().endsWith("List"))) {
                    addIssue(f, call.getBegin().map(p -> p.line).orElse(0), "Database", "Potential N+1 Query", "Use JOIN FETCH.");
                }
            });
        });
    }

    protected void checkBlockingTransactional(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).stream().filter(m -> m.isAnnotationPresent("Transactional")).forEach(m -> {
            m.findAll(MethodCallExpr.class).forEach(call -> {
                if (BLOCKING_CALLS.stream().anyMatch(b -> call.toString().toLowerCase().contains(b))) {
                    addIssue(f, call.getBegin().map(p -> p.line).orElse(0), "Concurrency", "Blocking call in Transaction", "Move I/O out of @Transactional.");
                }
            });
        });
    }

    protected void checkManualThreads(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread")) {
                addIssue(f, n.getBegin().map(p -> p.line).orElse(0), "Resource Mgmt", "Manual Thread creation", "Use @Async.");
            }
        });
    }

    protected void checkCacheTTL(CompilationUnit cu, String f, Properties p) {
        if (!cu.findAll(MethodDeclaration.class).stream().anyMatch(m -> m.isAnnotationPresent("Cacheable"))) return;
        boolean hasTTL = p.keySet().stream().anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));
        if (!hasTTL) addIssue(f, 0, "Caching", "Cache missing TTL", "Define expiration in application.properties.");
    }

    protected void checkOSIV(Properties p) {
        if ("true".equals(p.getProperty("spring.jpa.open-in-view", "true"))) {
            addIssue("application.properties", 0, "Architecture", "OSIV is Enabled", "Set spring.jpa.open-in-view=false.");
        }
    }

    protected void checkTransactionTimeout(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.getAnnotationByName("Transactional").ifPresent(a -> {
                if (!a.toString().contains("timeout")) addIssue(f, m.getBegin().map(p -> p.line).orElse(0), "Resilience", "Missing Transaction Timeout", "Add a timeout value.");
            });
        });
    }

    protected void checkMissingRepositoryAnnotation(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream().filter(c -> c.getNameAsString().endsWith("Repository")).forEach(c -> {
            if (!c.isAnnotationPresent("Repository")) addIssue(f, c.getBegin().map(p -> p.line).orElse(0), "Best Practice", "Missing @Repository", "Add @Repository annotation.");
        });
    }

    protected void checkAutowiredFieldInjection(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Autowired")) addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Architecture", "Field Injection", "Use constructor injection.");
        });
    }

    protected void checkHardcodedSecrets(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            String name = field.getVariable(0).getNameAsString().toLowerCase();
            if (name.contains("password") || name.contains("secret") || name.contains("apikey")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Security", "Hardcoded Secret", "Use env variables.");
            }
        });
    }

    protected void checkCrossOriginWildcard(CompilationUnit cu, String f) {
        cu.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).stream().filter(a -> a.getNameAsString().equals("CrossOrigin")).forEach(a -> {
            if (a.toString().equals("@CrossOrigin") || a.toString().contains("\"*\"")) {
                addIssue(f, a.getBegin().map(p -> p.line).orElse(0), "Security", "Insecure @CrossOrigin policy", "Specify allowed origins explicitly.");
            }
        });
    }

    protected void checkMissingResponseEntity(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream().filter(c -> c.isAnnotationPresent("RestController")).forEach(controller -> {
            controller.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("Mapping")))
                    .filter(m -> !m.getType().asString().startsWith("ResponseEntity"))
                    .forEach(m -> addIssue(f, m.getBegin().map(p -> p.line).orElse(0), "Best Practice", "Missing ResponseEntity", "Use ResponseEntity for full control."));
        });
    }

    protected void checkPropertiesSecrets(Properties p) {
        p.forEach((key, value) -> {
            String k = key.toString().toLowerCase();
            if ((k.contains("password") || k.contains("secret") || k.contains("apikey")) && !value.toString().matches("\\$\\{.*\\}")) {
                addIssue("application.properties", 0, "Security", "Hardcoded Secret in properties", "Use environment variables.");
            }
        });
    }

    // --- GENERAZIONE REPORT & UTILITY ---

    private void generateJsonReport(File outputDir) throws IOException {
        Path reportDir = outputDir.toPath().resolve("spring-sentinel-reports");
        Files.createDirectories(reportDir);
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.json").toFile())) {
            writer.write("{\n  \"totalIssues\": " + issues.size() + ",\n  \"issues\": [\n");
            for (int i = 0; i < issues.size(); i++) {
                AuditIssue issue = issues.get(i);
                writer.write(String.format("    {\"file\":\"%s\",\"line\":%d,\"type\":\"%s\",\"reason\":\"%s\",\"suggestion\":\"%s\"}%s\n",
                        issue.file, issue.line, issue.type, issue.reason, issue.suggestion, (i < issues.size() - 1 ? "," : "")));
            }
            writer.write("  ]\n}");
        }
    }

    private void generateHtmlReport(File outputDir) throws IOException {
        Path reportDir = outputDir.toPath().resolve("spring-sentinel-reports");
        Files.createDirectories(reportDir);
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.html").toFile())) {
            writer.write("<html><head><style>body{font-family:sans-serif;background:#f4f7f6;padding:20px;}.card{background:#fff;padding:15px;margin-bottom:10px;border-left:5px solid #e74c3c;box-shadow:0 1px 3px rgba(0,0,0,0.1);}.tag{background:#34495e;color:#fff;padding:2px 5px;border-radius:3px;font-size:11px;}</style></head><body>");
            writer.write("<h1>🛡️ Spring Sentinel Report</h1><p>Issues: <b>" + issues.size() + "</b></p>");
            for (AuditIssue i : issues) {
                writer.write(String.format("<div class='card'><span class='tag'>%s</span><h3>%s</h3><p>Location: %s (L:%d)</p><p>Fix: %s</p></div>",
                        i.type, i.reason, i.file, i.line, i.suggestion));
            }
            writer.write("</body></html>");
        }
    }

    private void addIssue(String f, int l, String t, String r, String s) {
        if (issues.stream().noneMatch(i -> i.file.equals(f) && i.reason.equals(r) && i.line == l)) {
            issues.add(new AuditIssue(f, l, t, r, s));
        }
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
        public final String file, type, reason, suggestion; public final int line;
        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f; this.line = l; this.type = t; this.reason = r; this.suggestion = s;
        }
    }
}