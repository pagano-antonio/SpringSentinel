package com.beanspringboot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

/**
 * 🛡️ SPRING SENTINEL - Version 4.5
 * Performance & Stability Static Analyzer for Spring Boot
 */
public class StaticAnalysis {

    private static final List<AuditIssue> report = new ArrayList<>();
    private static final List<String> BLOCKING_INDICATORS = List.of("resttemplate", "webclient", "feignclient", "httpclient", "execute", "thread.sleep");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("❌ Error: You must specify the project path.");
            System.out.println("Usage: java -jar spring-sentinel.jar /path/to/project");
            return;
        }

        String baseDir = args[0]; 
        if (!baseDir.endsWith("/") && !baseDir.endsWith("\\")) baseDir += File.separator;

        Path rootPath = Paths.get(baseDir);
        Path javaPath = rootPath.resolve("src").resolve("main").resolve("java");
        Path resPath = rootPath.resolve("src").resolve("main").resolve("resources");

        if (!Files.exists(javaPath)) {
            System.err.println("❌ ERROR: Maven structure not found. Missing src/main/java");
            return;
        }

        setupParser();
        System.out.println("==============================================");
        System.out.println("🛡️  SPRING SENTINEL - Version 4.5");
        System.out.println("==============================================\n");

        Properties tempProps = new Properties();
        if (Files.exists(resPath)) tempProps = loadProperties(resPath.toString());
        
        final Properties appProps = tempProps; 
        String outputDir = "./spring-sentinel-reports/"; 

        System.out.println("🔍 Scanning source code...");
        Files.walk(javaPath)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(path -> {
                System.out.println("👉 Checking: " + path.getFileName());
                try {
                    CompilationUnit cu = StaticJavaParser.parse(path);
                    runAllChecks(cu, path.toString(), appProps);
                } catch (Exception e) {}
            });

        checkConfigAudit(appProps);

        Path outPath = Paths.get(outputDir);
        if (!Files.exists(outPath)) Files.createDirectories(outPath);

        writeJsonReport(outPath.resolve("sentinel-audit.json").toString());
        writeHtmlReport(outPath.resolve("sentinel-audit.html").toString());
        
        System.out.println("\n✅ ANALYSIS COMPLETE. Reports generated in: " + outPath.toAbsolutePath());
    }

    private static void runAllChecks(CompilationUnit cu, String f, Properties p) {
        checkTransactionalEfficiency(cu, f);
        checkInternalTransactionalCalls(cu, f);
        checkJPAPerformance(cu, f);
        checkStaticNPlusOne(cu, f);
        checkCartesianProductRisk(cu, f);
        checkMissingPagination(cu, f);
        checkPrototypeInjection(cu, f);
        checkCacheEfficiency(cu, f, p);
        checkManualThreadCreation(cu, f);
        checkLoggingInLoops(cu, f);
    }

    // --- AUDIT LOGIC ---

    private static void checkCacheEfficiency(CompilationUnit cu, String f, Properties p) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            if (m.isAnnotationPresent("Cacheable")) {
                boolean hasTTL = p.keySet().stream().anyMatch(k -> k.toString().toLowerCase().matches(".*(ttl|expire|time-to-live).*"));
                if (!hasTTL) {
                    addIssue(f, m.getBegin().get().line, m.getNameAsString(), 
                        "Cache without TTL", "No TTL/expiration found in application.properties. Potential memory leak.");
                }
            }
        });
    }

    private static void checkInternalTransactionalCalls(CompilationUnit cu, String f) {
        Set<String> txMethods = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.isAnnotationPresent("Transactional"))
                .map(m -> m.getNameAsString()).collect(Collectors.toSet());
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (txMethods.contains(call.getNameAsString()) && call.getScope().isEmpty()) {
                addIssue(f, call.getBegin().get().line, call.getNameAsString(), 
                    "Self-invocation @Transactional", "Internal call bypasses Spring Proxy. Transaction will not start.");
            }
        });
    }

    private static void checkTransactionalEfficiency(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            if (m.isAnnotationPresent("Transactional")) {
                m.findAll(MethodCallExpr.class).forEach(c -> {
                    if (BLOCKING_INDICATORS.stream().anyMatch(c.toString().toLowerCase()::contains))
                        addIssue(f, c.getBegin().get().line, m.getNameAsString(), 
                            "Blocking I/O in Transaction", "Move blocking calls (REST/Threads) outside of @Transactional methods.");
                });
            }
        });
    }

    private static void checkJPAPerformance(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getAnnotations().forEach(anno -> {
                if (List.of("OneToMany", "ManyToMany").contains(anno.getNameAsString()) && anno.toString().contains("FetchType.EAGER"))
                    addIssue(f, field.getBegin().get().line, field.getVariable(0).getNameAsString(), 
                        "EAGER Fetching Detected", "Switch to LAZY fetching to improve performance.");
            });
        });
    }

    private static void checkStaticNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(l -> {
            l.findAll(MethodCallExpr.class).forEach(c -> {
                if (c.getNameAsString().startsWith("get") && (c.getNameAsString().endsWith("s") || c.getNameAsString().endsWith("List")))
                    addIssue(f, c.getBegin().get().line, "Loop Query", "Potential N+1 Query", "Use 'JOIN FETCH' in your repository.");
            });
        });
    }

    private static void checkCartesianProductRisk(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cl -> {
            long eagerCount = cl.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotations().stream()
                    .anyMatch(a -> List.of("OneToMany", "ManyToMany").contains(a.getNameAsString()) && a.toString().contains("FetchType.EAGER")))
                .count();
            if (eagerCount > 1) addIssue(f, cl.getBegin().get().line, cl.getNameAsString(), 
                "Cartesian Product Risk", "Multiple EAGER collections found in one entity.");
        });
    }

    private static void checkMissingPagination(CompilationUnit cu, String f) {
        cu.findAll(MethodCallExpr.class).forEach(c -> {
            if (List.of("findAll", "listAll").contains(c.getNameAsString())) {
                if (c.getArguments().stream().noneMatch(a -> a.toString().toLowerCase().contains("page")))
                    addIssue(f, c.getBegin().get().line, c.getNameAsString(), 
                        "Missing Pagination", "Use Pageable to limit database result sets.");
            }
        });
    }

    private static void checkPrototypeInjection(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cl -> {
            if (cl.getAnnotations().stream().anyMatch(a -> List.of("Service", "Component", "RestController").contains(a.getNameAsString()))) {
                cl.findAll(FieldDeclaration.class).forEach(field -> {
                    if (field.isAnnotationPresent("Autowired")) {
                        String type = field.getElementType().toString();
                        if (!type.contains("ObjectFactory") && !type.contains("ObjectProvider") && Character.isUpperCase(type.charAt(0)) && !type.equals("String"))
                            addIssue(f, field.getBegin().get().line, field.getVariable(0).getNameAsString(), 
                                "Prototype-in-Singleton Injection", "Inject ObjectProvider<T> instead of the bean directly.");
                    }
                });
            }
        });
    }

    private static void checkManualThreadCreation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread")) 
                addIssue(f, n.getBegin().get().line, "new Thread()", "Manual Thread Creation", "Use @Async or a managed ExecutorService.");
        });
    }

    private static void checkLoggingInLoops(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(l -> {
            l.findAll(MethodCallExpr.class).forEach(call -> {
                if (List.of("info", "error", "debug").contains(call.getNameAsString()) && call.toString().contains("log"))
                    addIssue(f, call.getBegin().get().line, "Loop Logging", "Move high-frequency logging outside the loop.", "Avoid performance overhead.");
            });
        });
    }

    private static void checkConfigAudit(Properties p) {
        if (p.isEmpty()) return;
        if (p.getProperty("spring.jpa.open-in-view", "true").equals("true"))
            addIssue("application.properties", 0, "OSIV Enabled", "Open-In-View is active", "Set to false to prevent connection pool exhaustion.");
        
        int t = Integer.parseInt(p.getProperty("server.tomcat.threads.max", "200"));
        int h = Integer.parseInt(p.getProperty("spring.datasource.hikari.maximum-pool-size", "10"));
        if ((double)t/h > 10.0)
            addIssue("application.properties", 0, "Pool Imbalance", "Critical Thread-to-DB Pool ratio", "Increase DB pool size.");
    }

    // --- REPORTERS ---

    private static void setupParser() {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        StaticJavaParser.setConfiguration(new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver)));
    }

    private static Properties loadProperties(String resPath) {
        Properties props = new Properties();
        Path p = Paths.get(resPath, "application.properties");
        if (Files.exists(p)) { 
            try (InputStream is = Files.newInputStream(p)) { props.load(is); } catch (Exception e) {} 
        }
        return props;
    }

    private static void addIssue(String f, int l, String t, String r, String s) {
        report.add(new AuditIssue(f, l, t, r, s));
        System.err.println("🛡️  DETECTED: " + r);
    }

    private static void writeHtmlReport(String fullPath) {
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Spring Sentinel Report</title><style>body{font-family:'Segoe UI',sans-serif;background:#f4f7f6;padding:40px}table{width:100%;border-collapse:collapse;background:white;box-shadow:0 4px 10px rgba(0,0,0,0.1)}th,td{padding:15px;border-bottom:1px solid #eee;text-align:left}th{background:#1a2a6c;color:white}.crit{color:#e74c3c;font-weight:bold}.fix{color:#27ae60;font-style:italic}</style></head><body><h1>🛡️ Spring Sentinel Audit Report</h1><table><tr><th>File</th><th>Line</th><th>Target</th><th>Issue</th><th>Technical Suggestion</th></tr>");
        for (AuditIssue i : report) h.append("<tr><td>"+i.file+"</td><td>"+i.line+"</td><td><code>"+i.target+"</code></td><td class='crit'>"+i.reason+"</td><td class='fix'>"+i.suggestion+"</td></tr>");
        h.append("</table></body></html>");
        try (FileWriter w = new FileWriter(fullPath)) { w.write(h.toString()); } catch (IOException e) {}
    }

    private static void writeJsonReport(String fullPath) {
        try (FileWriter w = new FileWriter(fullPath)) {
            w.write("[\n");
            for (int i = 0; i < report.size(); i++) {
                w.write(report.get(i).toJson() + (i < report.size() - 1 ? "," : "") + "\n");
            }
            w.write("]");
        } catch (IOException e) {}
    }

    static class AuditIssue {
        String file, target, reason, suggestion; int line;
        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f.replace("\\", "/"); this.line = l; this.target = t; this.reason = r; this.suggestion = s;
        }
        public String toJson() {
            return String.format("  {\"file\":\"%s\",\"line\":%d,\"target\":\"%s\",\"reason\":\"%s\",\"suggestion\":\"%s\"}", file, line, target, reason, suggestion);
        }
    }
}