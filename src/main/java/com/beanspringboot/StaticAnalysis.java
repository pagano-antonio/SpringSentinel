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
 * 🛡️ SPRING SENTINEL - Version 4.3
 * High-Performance Static Analysis & Audit Tool for Spring Boot
 */
public class StaticAnalysis {

    private static final List<AuditIssue> report = new ArrayList<>();
    private static final List<String> BLOCKING_INDICATORS = List.of("resttemplate", "webclient", "feignclient", "httpclient", "execute", "thread.sleep");

    public static void main(String[] args) throws Exception {
        
        // --- 1. VALIDAZIONE ARGOMENTI ---
        if (args.length == 0) {
            System.out.println("❌ Error: You must specify the path of the project to analyze.");
            System.out.println("Usage: java -jar spring-sentinel.jar /path/to/your/project");
            return;
        }

        String baseDir = args[0]; 
        if (!baseDir.endsWith("/") && !baseDir.endsWith("\\")) {
            baseDir += File.separator;
        }

        // --- 2. VERIFICA PERCORSI (STRUTTURA MAVEN) ---
        Path rootPath = Paths.get(baseDir);
        Path javaPath = rootPath.resolve("src").resolve("main").resolve("java");
        Path resPath = rootPath.resolve("src").resolve("main").resolve("resources");

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            System.err.println("❌ CRITICAL ERROR: Path does not exist: " + baseDir);
            return;
        }

        if (!Files.exists(javaPath)) {
            System.err.println("❌ ERROR: 'src/main/java' not found in " + baseDir);
            return;
        }

        // --- 3. CONFIGURAZIONE ---
        setupParser();
        System.out.println("==============================================");
        System.out.println("🛡️  SPRING SENTINEL - Version 4.3");
        System.out.println("==============================================\n");

        Properties tempProps = new Properties();
        if (Files.exists(resPath)) {
            tempProps = loadProperties(resPath.toString());
        } else {
            System.out.println("⚠️  Note: 'resources' folder not found. Some checks will be skipped.");
        }
        
        final Properties appProps = tempProps; 
        String outputDir = "./spring-sentinel-reports/"; 

        // --- 4. SCANSIONE SORGENTI CON LOG ---
        System.out.println("🔍 Scanning source code...");
        Files.walk(javaPath)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(path -> {
                System.out.println("👉 Checking: " + path.getFileName()); // Log del file in analisi
                try {
                    CompilationUnit cu = StaticJavaParser.parse(path);
                    runAllChecks(cu, path.toString(), appProps);
                } catch (Exception e) {
                    System.err.println("⚠️  Could not parse: " + path.getFileName());
                }
            });

        // --- 5. AUDIT CONFIGURAZIONE ---
        checkConfigAudit(appProps);

        // --- 6. GENERAZIONE REPORT ---
        Path outPath = Paths.get(outputDir);
        if (!Files.exists(outPath)) Files.createDirectories(outPath);

        writeJsonReport(outPath.resolve("sentinel-audit.json").toAbsolutePath().toString());
        writeHtmlReport(outPath.resolve("sentinel-audit.html").toAbsolutePath().toString());
        
        System.out.println("\n✅ ANALYSIS COMPLETE.");
        System.out.println("📂 Reports generated in: " + outPath.toAbsolutePath());
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

    // --- LOGICA DI AUDIT ---

    private static void checkCacheEfficiency(CompilationUnit cu, String f, Properties p) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            if (m.isAnnotationPresent("Cacheable")) {
                boolean hasTTL = p.keySet().stream().anyMatch(k -> 
                    k.toString().toLowerCase().matches(".*(ttl|expire|time-to-live).*")
                );
                
                // Corretto: Se non c'è TTL, segnala SEMPRE (anche se p è vuoto)
                if (!hasTTL) {
                    addIssue(f, m.getBegin().get().line, m.getNameAsString(), 
                        "Cache without TTL", "Metodo @Cacheable rilevato ma nessun TTL trovato in application.properties.");
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
                addIssue(f, call.getBegin().get().line, call.getNameAsString(), "Self-invocation @Transactional", "Proxy bypass: Transaction will not start.");
            }
        });
    }

    private static void checkTransactionalEfficiency(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            if (m.isAnnotationPresent("Transactional")) {
                m.findAll(MethodCallExpr.class).forEach(c -> {
                    String callStr = c.toString().toLowerCase();
                    if (BLOCKING_INDICATORS.stream().anyMatch(callStr::contains))
                        addIssue(f, c.getBegin().get().line, m.getNameAsString(), "I/O in transaction", "Move blocking call out of @Transactional.");
                });
            }
        });
    }

    private static void checkJPAPerformance(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getAnnotations().forEach(anno -> {
                if (List.of("OneToMany", "ManyToMany").contains(anno.getNameAsString()) && anno.toString().contains("FetchType.EAGER"))
                    addIssue(f, field.getBegin().get().line, field.getVariable(0).getNameAsString(), "EAGER fetching", "Use LAZY fetching.");
            });
        });
    }

    private static void checkStaticNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.findAll(ForEachStmt.class).forEach(l -> scanN1(l.getBody(), m.getNameAsString(), f));
        });
    }

    private static void scanN1(Node b, String m, String f) {
        b.findAll(MethodCallExpr.class).forEach(c -> {
            String name = c.getNameAsString();
            if (name.startsWith("get") && (name.endsWith("s") || name.endsWith("List")))
                addIssue(f, c.getBegin().get().line, m, "Potential N+1 Query", "Use 'JOIN FETCH' in Repository.");
        });
    }

    private static void checkCartesianProductRisk(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cl -> {
            long eagerCount = cl.findAll(FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotations().stream()
                    .anyMatch(a -> List.of("OneToMany", "ManyToMany").contains(a.getNameAsString()) && a.toString().contains("FetchType.EAGER")))
                .count();
            if (eagerCount > 1) addIssue(f, cl.getBegin().get().line, cl.getNameAsString(), "Cartesian Product Risk", "Multiple EAGER collections.");
        });
    }

    private static void checkMissingPagination(CompilationUnit cu, String f) {
        cu.findAll(MethodCallExpr.class).forEach(c -> {
            if (List.of("findAll", "listAll").contains(c.getNameAsString())) {
                if (c.getArguments().stream().noneMatch(a -> a.toString().toLowerCase().contains("page")))
                    addIssue(f, c.getBegin().get().line, c.getNameAsString(), "Missing Pagination", "Use Pageable.");
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
                            addIssue(f, field.getBegin().get().line, field.getVariable(0).getNameAsString(), "Prototype-in-Singleton", "Use ObjectProvider.");
                    }
                });
            }
        });
    }

    private static void checkManualThreadCreation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread")) addIssue(f, n.getBegin().get().line, "new Thread()", "Manual Thread", "Use @Async.");
        });
    }

    private static void checkLoggingInLoops(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(l -> {
            l.findAll(MethodCallExpr.class).forEach(call -> {
                if (List.of("info", "error", "debug").contains(call.getNameAsString()) && call.toString().contains("log"))
                    addIssue(f, call.getBegin().get().line, "log", "Logging in loop", "Move log outside.");
            });
        });
    }

    private static void checkConfigAudit(Properties p) {
        if (p.isEmpty()) return;
        if (p.getProperty("spring.jpa.open-in-view", "true").equals("true"))
            addIssue("application.properties", 0, "OSIV Enabled", "Open-In-View is active", "Set to false.");
        
        int t = Integer.parseInt(p.getProperty("server.tomcat.threads.max", "200"));
        int h = Integer.parseInt(p.getProperty("spring.datasource.hikari.maximum-pool-size", "10"));
        if ((double)t/h > 10.0)
            addIssue("application.properties", 0, "Pool Imbalance", "Thread/DB Pool Mismatch", "Check pool sizes.");
    }

    // --- UTILITIES ---

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
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Report</title><style>body{font-family:sans-serif;background:#f4f7f6;padding:40px}table{width:100%;border-collapse:collapse;background:white}th,td{padding:12px;border-bottom:1px solid #ddd;text-align:left}th{background:#2c3e50;color:white}.crit{color:#e74c3c;font-weight:bold}</style></head><body><h1>🛡️ Spring Sentinel Report</h1><table><tr><th>File</th><th>Line</th><th>Target</th><th>Issue</th><th>Fix</th></tr>");
        for (AuditIssue i : report) h.append("<tr><td>"+i.file+"</td><td>"+i.line+"</td><td><code>"+i.target+"</code></td><td class='crit'>"+i.reason+"</td><td>"+i.suggestion+"</td></tr>");
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