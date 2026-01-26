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
	import com.github.javaparser.ast.stmt.ForStmt;
	import com.github.javaparser.symbolsolver.JavaSymbolSolver;
	import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
	import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

	public class StaticAnalysis {

	    private static final List<AuditIssue> report = new ArrayList<>();
	    private static final List<String> BLOCKING_INDICATORS = List.of("restTemplate", "webClient", "feignClient", "httpClient", "execute");

	    public static void main(String[] args) throws Exception {
	        
	        // --- 1. CONFIGURAZIONE PERCORSI ---
	    	// Controlliamo se l'utente ha passato un argomento
	        if (args.length == 0) {
	            System.out.println("❌ Errore: Devi specificare il percorso del progetto da analizzare.");
	            System.out.println("Uso: java -jar spring-sentinel.jar /percorso/del/tuo/progetto");
	            return;
	        }

	        // Il primo argomento sarà la cartella base del progetto da analizzare
	        String baseDir = args[0]; 
	        
	        // Assicuriamoci che il path finisca con lo slash corretto
	        if (!baseDir.endsWith("/") && !baseDir.endsWith("\\")) {
	            baseDir += File.separator;
	        }
	        
	        String projectPath = baseDir + "java";
	        String resourcesPath = baseDir + "resources";

	        // --- 2. SCEGLI QUI DOVE SALVARE I REPORT ---
	        String outputDir = "./audit-reports/"; 
	        String jsonName = "audit-report.json";
	        String htmlName = "audit-report.html";

	        setupParser();

	        System.out.println("==============================================");
	        System.out.println("🚀 SPRING PERFORMANCE AUDIT TOOL - V4.0");
	        System.out.println("==============================================\n");

	        Properties appProps = loadProperties(resourcesPath);

	        if (Files.exists(Paths.get(projectPath))) {
	            Files.walk(Paths.get(projectPath))
	                .filter(p -> p.toString().endsWith(".java"))
	                .forEach(path -> {
	                    try {
	                        CompilationUnit cu = StaticJavaParser.parse(path);
	                        runAllChecks(cu, path.toString(), appProps);
	                    } catch (Exception e) { }
	                });
	        }

	        checkConfigAudit(appProps);

	        // --- 3. SALVATAGGIO REPORT ---
	        Path outPath = Paths.get(outputDir);
	        if (!Files.exists(outPath)) Files.createDirectories(outPath);

	        writeJsonReport(outPath.resolve(jsonName).toAbsolutePath().toString());
	        writeHtmlReport(outPath.resolve(htmlName).toAbsolutePath().toString());
	        
	        System.out.println("\n✅ ANALISI COMPLETATA.");
	        System.out.println("📂 JSON: " + outPath.resolve(jsonName).toAbsolutePath());
	        System.out.println("🌐 HTML: " + outPath.resolve(htmlName).toAbsolutePath());
	    }

	    private static void writeHtmlReport(String fullPath) {
	        StringBuilder html = new StringBuilder();
	        html.append("<!DOCTYPE html><html><head><title>Spring Audit Report</title>");
	        html.append("<style>");
	        html.append("body { font-family: sans-serif; margin: 40px; background: #f4f7f6; }");
	        html.append("h1 { color: #2c3e50; }");
	        html.append("table { width: 100%; border-collapse: collapse; background: white; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }");
	        html.append("th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }");
	        html.append("th { background: #34495e; color: white; }");
	        html.append("tr:hover { background-color: #f1f1f1; }");
	        html.append(".file-path { font-size: 0.85em; color: #7f8c8d; }");
	        html.append(".reason { color: #e74c3c; font-weight: bold; }");
	        html.append(".suggestion { color: #27ae60; font-style: italic; }");
	        html.append(".badge { padding: 4px 8px; border-radius: 4px; font-size: 0.8em; background: #eee; }");
	        html.append("</style></head><body>");
	        html.append("<h1>🚀 Spring Boot Performance Audit</h1>");
	        html.append("<p>Totale criticità trovate: <strong>" + report.size() + "</strong></p>");
	        html.append("<table><tr><th>File</th><th>Riga</th><th>Target</th><th>Problema</th><th>Suggerimento</th></tr>");

	        for (AuditIssue issue : report) {
	            html.append("<tr>");
	            html.append("<td class='file-path'>" + issue.file + "</td>");
	            html.append("<td><span class='badge'>" + issue.line + "</span></td>");
	            html.append("<td><code>" + issue.target + "</code></td>");
	            html.append("<td class='reason'>" + issue.reason + "</td>");
	            html.append("<td class='suggestion'>" + issue.suggestion + "</td>");
	            html.append("</tr>");
	        }

	        html.append("</table></body></html>");

	        try (FileWriter writer = new FileWriter(fullPath)) {
	            writer.write(html.toString());
	        } catch (IOException e) {
	            System.err.println("Errore scrittura HTML.");
	        }
	    }

	    private static void writeJsonReport(String fullPath) {
	        try (FileWriter writer = new FileWriter(fullPath)) {
	            writer.write("[\n");
	            for (int i = 0; i < report.size(); i++) {
	                writer.write(report.get(i).toJson());
	                if (i < report.size() - 1) writer.write(",");
	                writer.write("\n");
	            }
	            writer.write("]");
	        } catch (IOException e) { }
	    }

	    private static void addIssue(String f, int l, String t, String r, String s) {
	        report.add(new AuditIssue(f, l, t, r, s));
	        System.err.println("🚨 " + r + " -> " + t);
	    }

	    // --- LOGICA DI AUDIT (Invariata) ---

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

	    private static void checkInternalTransactionalCalls(CompilationUnit cu, String f) {
	        Set<String> txMethods = cu.findAll(MethodDeclaration.class).stream()
	                .filter(m -> m.isAnnotationPresent("Transactional"))
	                .map(m -> m.getNameAsString()).collect(Collectors.toSet());
	        cu.findAll(MethodCallExpr.class).forEach(call -> {
	            if (txMethods.contains(call.getNameAsString()) && call.getScope().isEmpty()) {
	                addIssue(f, call.getBegin().get().line, call.getNameAsString(), "Self-invocation @Transactional", "Il proxy viene ignorato.");
	            }
	        });
	    }

	    private static void checkTransactionalEfficiency(CompilationUnit cu, String f) {
	        cu.findAll(MethodDeclaration.class).forEach(m -> {
	            if (m.isAnnotationPresent("Transactional")) {
	                m.findAll(MethodCallExpr.class).forEach(c -> {
	                    if (BLOCKING_INDICATORS.stream().anyMatch(i -> c.toString().contains(i)))
	                        addIssue(f, c.getBegin().get().line, m.getNameAsString(), "I/O in transazione", "Estrai I/O.");
	                });
	            }
	        });
	    }

	    private static void checkJPAPerformance(CompilationUnit cu, String f) {
	        cu.findAll(FieldDeclaration.class).forEach(field -> {
	            field.getAnnotations().forEach(anno -> {
	                if (List.of("OneToMany", "ManyToMany").contains(anno.getNameAsString()) && anno.toString().contains("FetchType.EAGER"))
	                    addIssue(f, field.getBegin().get().line, field.getVariable(0).getNameAsString(), "EAGER su collezione", "Usa LAZY.");
	            });
	        });
	    }

	    private static void checkStaticNPlusOne(CompilationUnit cu, String f) {
	        cu.findAll(MethodDeclaration.class).forEach(m -> {
	            m.findAll(ForEachStmt.class).forEach(l -> scanN1(l.getBody(), m.getNameAsString(), f));
	            m.findAll(MethodCallExpr.class).forEach(c -> {
	                if (List.of("map", "forEach").contains(c.getNameAsString())) 
	                    c.getArguments().forEach(arg -> scanN1(arg, m.getNameAsString(), f));
	            });
	        });
	    }

	    private static void scanN1(Node b, String m, String f) {
	        b.findAll(MethodCallExpr.class).forEach(c -> {
	            if (c.getNameAsString().startsWith("get") && (c.getNameAsString().endsWith("s") || c.getNameAsString().endsWith("List")))
	                addIssue(f, c.getBegin().get().line, m, "Query N+1", "Usa JOIN FETCH.");
	        });
	    }

	    private static void checkCartesianProductRisk(CompilationUnit cu, String f) {
	        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cl -> {
	            List<String> eager = new ArrayList<>();
	            cl.findAll(FieldDeclaration.class).forEach(field -> {
	                if (field.getAnnotations().stream().anyMatch(a -> List.of("OneToMany", "ManyToMany").contains(a.getNameAsString()) && a.toString().contains("FetchType.EAGER")))
	                    eager.add(field.getVariable(0).getNameAsString());
	            });
	            if (eager.size() > 1) addIssue(f, cl.getBegin().get().line, cl.getNameAsString(), "Rischio Prodotto Cartesiano", "Multiple EAGER.");
	        });
	    }

	    private static void checkMissingPagination(CompilationUnit cu, String f) {
	        cu.findAll(MethodCallExpr.class).forEach(c -> {
	            if (List.of("findAll", "listAll").contains(c.getNameAsString())) {
	                if (c.getArguments().stream().noneMatch(a -> a.toString().toLowerCase().contains("page")))
	                    addIssue(f, c.getBegin().get().line, c.getNameAsString(), "Mancanza Paginazione", "Usa Pageable.");
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
	                            addIssue(f, field.getBegin().get().line, field.getVariable(0).getNameAsString(), "Rischio Iniezione Prototype", "Usa ObjectProvider.");
	                    }
	                });
	            }
	        });
	    }

	    private static void checkCacheEfficiency(CompilationUnit cu, String f, Properties p) {
	        cu.findAll(MethodDeclaration.class).forEach(m -> {
	            if (m.isAnnotationPresent("Cacheable")) {
	                boolean hasTTL = p.keySet().stream().anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));
	                if (!hasTTL) addIssue(f, m.getBegin().get().line, m.getNameAsString(), "Cache senza TTL", "Configura scadenza.");
	            }
	        });
	    }

	    private static void checkManualThreadCreation(CompilationUnit cu, String f) {
	        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
	            if (n.getTypeAsString().equals("Thread")) addIssue(f, n.getBegin().get().line, "new Thread()", "Thread manuale", "Usa @Async.");
	        });
	    }

	    private static void checkLoggingInLoops(CompilationUnit cu, String f) {
	        cu.findAll(MethodDeclaration.class).forEach(m -> {
	            m.findAll(ForEachStmt.class).forEach(l -> scanLogs(l.getBody(), f));
	            m.findAll(MethodCallExpr.class).forEach(c -> {
	                if (List.of("forEach", "map").contains(c.getNameAsString())) scanLogs(c, f);
	            });
	        });
	    }

	    private static void scanLogs(Node block, String f) {
	        block.findAll(MethodCallExpr.class).forEach(call -> {
	            if (List.of("info", "error", "debug").contains(call.getNameAsString()) && call.toString().contains("log"))
	                addIssue(f, call.getBegin().get().line, "log", "Logging in loop", "Sposta fuori.");
	        });
	    }

	    private static void checkConfigAudit(Properties p) {
	        if (p.getProperty("spring.jpa.open-in-view", "true").equals("true"))
	            addIssue("application.properties", 0, "OSIV", "Open-In-View attivo", "Imposta a false.");
	        
	        int t = Integer.parseInt(p.getProperty("server.tomcat.threads.max", "200"));
	        int h = Integer.parseInt(p.getProperty("spring.datasource.hikari.maximum-pool-size", "10"));
	        if ((double)t/h > 10.0)
	            addIssue("application.properties", 0, "Pool", "Squilibrio Thread/DB", "Aumenta pool.");
	    }

	    private static void setupParser() {
	        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
	        ParserConfiguration config = new ParserConfiguration();
	        config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
	        StaticJavaParser.setConfiguration(config);
	    }

	    private static Properties loadProperties(String resPath) {
	        Properties props = new Properties();
	        Path p = Paths.get(resPath, "application.properties");
	        if (Files.exists(p)) { 
	            try (InputStream is = Files.newInputStream(p)) { props.load(is); } catch (Exception e) {} 
	        }
	        return props;
	    }

	    static class AuditIssue {
	        String file; int line; String target; String reason; String suggestion;
	        public AuditIssue(String f, int l, String t, String r, String s) {
	            this.file = f.replace("\\", "/"); 
	            this.line = l; 
	            this.target = t; 
	            this.reason = r; 
	            this.suggestion = s;
	        }
	        public String toJson() {
	            return String.format("  {\n    \"file\": \"%s\",\n    \"line\": %d,\n    \"target\": \"%s\",\n    \"reason\": \"%s\",\n    \"suggestion\": \"%s\"\n  }",
	                file.replace("\"", "\\\""), line, target, reason, suggestion);
	        }
	    }
	}