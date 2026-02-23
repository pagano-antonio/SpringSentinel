package com.beanspringboot;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Core analysis logic for Spring Boot projects.
 * v1.4.0: Integrated ResolvedConfig for dynamic parameter management and Path Filtering.
 */
public class AnalysisRules {

    private static final List<String> BLOCKING_CALLS = List.of(
            "resttemplate", "webclient", "feignclient", "httpclient", "thread.sleep"
    );

    private final Consumer<StaticAnalysisCore.AuditIssue> issueConsumer;
    private final ResolvedConfig config;

    public AnalysisRules(Consumer<StaticAnalysisCore.AuditIssue> issueConsumer, ResolvedConfig config) {
        this.issueConsumer = issueConsumer;
        this.config = config;
    }

    private void addIssue(String f, int l, String t, String r, String s) {
        issueConsumer.accept(new StaticAnalysisCore.AuditIssue(f, l, t, r, s));
    }

    private boolean isRuleActive(String id) {
        return config.getActiveRules().contains(id);
    }

    /**
     * Verifica se una regola è attiva e se si applica al percorso del file
     * basandosi sui parametri includePaths ed excludePaths.
     */
    private boolean isRuleApplicable(String ruleId, String filePath) {
        // 1. La regola è attiva nel profilo?
        if (!isRuleActive(ruleId)) {
            return false;
        }

        // Il path va normalizzato per i controlli regex (sostituisce i \ di Windows con /)
        String normalizedPath = filePath.replace("\\", "/");

        // 2. Controllo includePaths (Se presente, il file DEVE fare match)
        String includes = config.getParameter(ruleId, "includePaths", "");
        if (!includes.isEmpty()) {
            boolean matchesInclude = Arrays.stream(includes.split(","))
                    .map(String::trim)
                    .anyMatch(normalizedPath::matches);
            if (!matchesInclude) return false; // Se non matcha, ignoriamo il file
        }

        // 3. Controllo excludePaths (Se presente e fa match, IGNORIAMO il file)
        String excludes = config.getParameter(ruleId, "excludePaths", "");
        if (!excludes.isEmpty()) {
            boolean matchesExclude = Arrays.stream(excludes.split(","))
                    .map(String::trim)
                    .anyMatch(normalizedPath::matches);
            if (matchesExclude) return false; // Se matcha un exclude, lo saltiamo
        }

        return true;
    }

    // --- ANALISI OLISTICA (POM.XML) ---

    public void runProjectChecks(MavenProject project) {
        if (project == null) return;
        if (isRuleActive("SEC-003") || isRuleActive("MAINT-001")) checkDependencyConflicts(project);
        if (isRuleActive("MAINT-002")) checkMissingProductionPlugins(project);
    }

    private void checkDependencyConflicts(MavenProject project) {
        for (Object depObj : project.getDependencies()) {
            Dependency dep = (Dependency) depObj;
            String artifactId = dep.getArtifactId();
            
            if (isRuleActive("SEC-003") && "spring-boot-starter-data-rest".equals(artifactId)) {
                addIssue("pom.xml", 0, "Architecture", "Exposed Repositories (Data REST)", 
                    "Spring Data REST automatically exposes all repositories. Ensure endpoints are properly secured.");
            }
            
            if (isRuleActive("MAINT-001") && "spring-boot-starter".equals(artifactId) && dep.getVersion() != null) {
                if (dep.getVersion().startsWith("2.")) {
                    addIssue("pom.xml", 0, "Maintenance", "Old Spring Boot Version", 
                        "Project is using Spring Boot 2.x. Upgrade to 3.x for Jakarta EE compatibility and security updates.");
                }
            }
        }
    }

    private void checkMissingProductionPlugins(MavenProject project) {
        boolean hasSpringPlugin = false;
        for (Object pluginObj : project.getBuildPlugins()) {
            Plugin p = (Plugin) pluginObj;
            if ("spring-boot-maven-plugin".equals(p.getArtifactId())) {
                hasSpringPlugin = true;
                break;
            }
        }
        if (!hasSpringPlugin) {
            addIssue("pom.xml", 0, "Build", "Missing Spring Boot Plugin", 
                "Missing 'spring-boot-maven-plugin'. This is required to package an executable JAR/WAR.");
        }
    }

    // --- ANALISI CODICE JAVA ---

    public void runAllChecks(CompilationUnit cu, String filePath, Properties props) {
        // Regole con parametri caricati dinamicamente da ResolvedConfig
        if (isRuleApplicable("SEC-001", filePath)) {
            String pattern = config.getParameter("SEC-001", "pattern", ".*(password|secret|apikey|pwd|token).*");
            checkHardcodedSecrets(cu, filePath, pattern);
        }
        
        if (isRuleApplicable("ARCH-003", filePath)) {
            int maxDeps = Integer.parseInt(config.getParameter("ARCH-003", "maxDependencies", "7"));
            checkFatComponents(cu, filePath, maxDeps);
        }

        // Regole standard filtrate per percorso
        if (isRuleApplicable("PERF-001", filePath)) checkJPAEager(cu, filePath);
        if (isRuleApplicable("PERF-002", filePath)) checkNPlusOne(cu, filePath);
        if (isRuleApplicable("PERF-003", filePath)) checkBlockingTransactional(cu, filePath);
        if (isRuleApplicable("RES-001", filePath)) checkManualThreads(cu, filePath);
        if (isRuleApplicable("PERF-004", filePath)) checkCacheTTL(cu, filePath, props);
        if (isRuleApplicable("RES-002", filePath)) checkTransactionTimeout(cu, filePath);
        if (isRuleApplicable("MAINT-003", filePath)) checkMissingRepositoryAnnotation(cu, filePath);
        if (isRuleApplicable("ARCH-002", filePath)) checkAutowiredFieldInjection(cu, filePath);
        if (isRuleApplicable("SEC-002", filePath)) checkCrossOriginWildcard(cu, filePath);
        if (isRuleApplicable("REST-004", filePath)) checkMissingResponseEntity(cu, filePath);
        if (isRuleApplicable("ARCH-001", filePath)) checkBeanScopesAndThreadSafety(cu, filePath);
        if (isRuleApplicable("ARCH-005", filePath)) checkLazyInjectionSmell(cu, filePath);
        if (isRuleApplicable("ARCH-004", filePath)) checkManualInstantiation(cu, filePath);
        
        if (isRuleApplicable("REST-001", filePath) || isRuleApplicable("REST-002", filePath) || isRuleApplicable("REST-003", filePath)) {
            checkRestfulNaming(cu, filePath); 
        }
    }

    protected void checkRestfulNaming(CompilationUnit cu, String f) {
        cu.findAll(AnnotationExpr.class).forEach(anno -> {
            String name = anno.getNameAsString();
            if (name.endsWith("Mapping")) {
                anno.getChildNodes().forEach(node -> {
                    String url = node.toString().replace("\"", "");
                    if (url.startsWith("/")) {
                        String type = "REST Design (Warning)";

                        if (isRuleApplicable("REST-001", f) && (url.matches(".*[A-Z].*") || url.contains("_"))) {
                            addIssue(f, anno.getBegin().map(p -> p.line).orElse(0),
                                type, "Non-standard URL naming", 
                                "URL '" + url + "' should use kebab-case (lowercase with hyphens).");
                        }
                        if (isRuleApplicable("REST-002", f) && !url.matches(".*/v[0-9]+.*") && !url.equals("/")) {
                            addIssue(f, anno.getBegin().map(p -> p.line).orElse(0),
                                type, "Missing API Versioning", 
                                "URL '" + url + "' is missing a version prefix (e.g., /v1).");
                        }
                        if (isRuleApplicable("REST-003", f) && isSingular(url)) {
                            addIssue(f, anno.getBegin().map(p -> p.line).orElse(0),
                                type, "Singular Resource Name", 
                                "Resource '" + url + "' should be plural (e.g., /users).");
                        }
                    }
                });
            }
        });
    }

    protected void checkHardcodedSecrets(CompilationUnit cu, String f, String pattern) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            String name = field.getVariable(0).getNameAsString().toLowerCase();
            if (name.matches(pattern)) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Security", "Potential Hardcoded Secret", 
                    "Variable '" + name + "' matches the security pattern. Move sensitive data to environment variables.");
            }
        });
    }

    protected void checkFatComponents(CompilationUnit cu, String f, int maxDeps) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            long injectedFields = clazz.findAll(FieldDeclaration.class).stream().filter(this::isInjectedField).count();
            
            // Fix: OptionalInt corretto con orElse(0)
            int constructorParams = clazz.getConstructors().stream()
                    .mapToInt(c -> c.getParameters().size())
                    .max()
                    .orElse(0); 
            
            if ((injectedFields + constructorParams) > maxDeps) {
                addIssue(f, clazz.getBegin().map(p -> p.line).orElse(0), "Architecture", "Fat Component", 
                    "Class has " + (injectedFields + constructorParams) + " dependencies, exceeding the limit of " + maxDeps + ".");
            }
        });
    }

    protected void checkManualInstantiation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getTypeAsString();
            if (type.endsWith("Service") || type.endsWith("Repository") || type.endsWith("Component")) {
                addIssue(f, newExpr.getBegin().map(p -> p.line).orElse(0), 
                    "Design Smell", "Manual Instantiation of Spring Bean", 
                    "Avoid 'new " + type + "()'. Let Spring manage bean lifecycle via Dependency Injection.");
            }
        });
    }

    protected void checkJPAEager(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.toString().contains("FetchType.EAGER")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Performance", "EAGER Fetching detected", 
                    "JPA EAGER fetching can cause overhead. Switch to LAZY.");
            }
        });
    }

    protected void checkNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(loop -> {
            loop.findAll(MethodCallExpr.class).forEach(call -> {
                if (call.getNameAsString().startsWith("get") && 
                   (call.getNameAsString().endsWith("s") || call.getNameAsString().endsWith("List"))) {
                    addIssue(f, call.getBegin().map(p -> p.line).orElse(0), 
                        "Database", "Potential N+1 Query", 
                        "Avoid calling collection getters inside loops.");
                }
            });
        });
    }

    protected void checkBlockingTransactional(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).stream()
            .filter(m -> m.isAnnotationPresent("Transactional"))
            .forEach(m -> m.findAll(MethodCallExpr.class).forEach(call -> {
                if (BLOCKING_CALLS.stream().anyMatch(b -> call.toString().toLowerCase().contains(b))) {
                    addIssue(f, call.getBegin().map(p -> p.line).orElse(0), 
                        "Concurrency", "Blocking call in Transaction", 
                        "Avoid network/IO calls inside @Transactional.");
                }
            }));
    }

    protected void checkManualThreads(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread") || n.getTypeAsString().equals("ExecutorService")) {
                addIssue(f, n.getBegin().map(p -> p.line).orElse(0), 
                    "Resource Mgmt", "Manual Thread creation", 
                    "Manual threading is discouraged in Spring. Use @Async.");
            }
        });
    }

    protected void checkCacheTTL(CompilationUnit cu, String f, Properties p) {
        if (cu.findAll(MethodDeclaration.class).stream().noneMatch(m -> m.isAnnotationPresent("Cacheable"))) return;
        boolean hasTTL = p.keySet().stream().anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));
        if (!hasTTL) {
            addIssue(f, 0, "Caching", "Cache missing TTL", 
                "Define a Time-To-Live (TTL) for caches in application.properties.");
        }
    }

    protected void checkTransactionTimeout(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.getAnnotationByName("Transactional").ifPresent(a -> {
                if (!a.toString().contains("timeout")) {
                    addIssue(f, m.getBegin().map(p -> p.line).orElse(0), 
                        "Resilience", "Missing Transaction Timeout", 
                        "Explicitly define a timeout for long-running transactions.");
                }
            });
        });
    }

    protected void checkMissingRepositoryAnnotation(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
            .filter(c -> c.getNameAsString().endsWith("Repository"))
            .forEach(c -> {
                if (!c.isAnnotationPresent("Repository")) {
                    addIssue(f, c.getBegin().map(p -> p.line).orElse(0), 
                        "Best Practice", "Missing @Repository", 
                        "Add @Repository to your data access interfaces.");
                }
            });
    }

    protected void checkAutowiredFieldInjection(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Autowired") || field.isAnnotationPresent("Inject")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Architecture", "Field Injection", 
                    "Field injection is an anti-pattern. Use constructor injection.");
            }
        });
    }

    protected void checkCrossOriginWildcard(CompilationUnit cu, String f) {
        cu.findAll(AnnotationExpr.class).stream()
            .filter(a -> a.getNameAsString().equals("CrossOrigin"))
            .forEach(a -> {
                if (a.toString().equals("@CrossOrigin") || a.toString().contains("\"*\"")) {
                    addIssue(f, a.getBegin().map(p -> p.line).orElse(0), 
                        "Security", "Insecure @CrossOrigin policy", 
                        "CORS wildcard '*' is insecure.");
                }
            });
    }

    protected void checkMissingResponseEntity(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
            .filter(c -> c.isAnnotationPresent("RestController"))
            .forEach(controller -> {
                controller.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("Mapping")))
                    .filter(m -> !m.getType().asString().startsWith("ResponseEntity"))
                    .forEach(m -> addIssue(f, m.getBegin().map(p -> p.line).orElse(0), 
                        "Best Practice", "Missing ResponseEntity", 
                        "Return ResponseEntity<T> in RestControllers."));
            });
    }

    protected void checkBeanScopesAndThreadSafety(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (isSpringComponent(clazz)) {
                boolean lombokMakesFinal = clazz.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("FieldDefaults") && 
                                       a.toString().replace(" ", "").contains("makeFinal=true"));

                if (!lombokMakesFinal) {
                    clazz.findAll(FieldDeclaration.class).forEach(field -> {
                        if (!field.isFinal() && !isInjectedField(field) && !field.isStatic()) {
                            addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                                "Thread Safety", "Mutable state in Singleton", 
                                "Spring Beans are Singletons. Avoid mutable fields.");
                        }
                    });
                }
            }
        });
    }

    protected void checkLazyInjectionSmell(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Lazy") && field.isAnnotationPresent("Autowired")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Design Smell", "Lazy Injection", 
                    "@Lazy injection is often a workaround for circular dependencies.");
            }
        });
    }

    private boolean isSingular(String url) {
        String[] parts = url.split("/");
        if (parts.length == 0) return false;
        String lastPart = parts[parts.length - 1];
        return !lastPart.isEmpty() && !lastPart.endsWith("s") && !lastPart.contains("{");
    }

    private boolean isSpringComponent(ClassOrInterfaceDeclaration clazz) {
        return clazz.isAnnotationPresent("Service") || 
                clazz.isAnnotationPresent("RestController") ||
                clazz.isAnnotationPresent("Component") || 
                clazz.isAnnotationPresent("Repository");
    }

    private boolean isInjectedField(FieldDeclaration field) {
        return field.isAnnotationPresent("Autowired") || 
                field.isAnnotationPresent("Value") ||
                field.isAnnotationPresent("Resource") || 
                field.isAnnotationPresent("Inject");
    }
}