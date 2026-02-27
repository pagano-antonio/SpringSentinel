package com.beanspringboot;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
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
 * v1.5.0: Added Inline Suppressions (@SuppressWarnings) and Lombok Entity checks.
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

    // Aggiunge un issue globale (es. per il pom.xml dove non c'è un nodo Java)
    private void addIssue(String f, int l, String t, String r, String s) {
        issueConsumer.accept(new StaticAnalysisCore.AuditIssue(f, l, t, r, s));
    }

    // NUOVO: Aggiunge un issue SOLO se non è stato soppresso dallo sviluppatore
    private void addIssue(Node node, String ruleId, String f, int l, String t, String r, String s) {
        if (isSuppressed(node, ruleId)) {
            return; // L'utente ha usato @SuppressWarnings, ignoriamo l'errore!
        }
        addIssue(f, l, t, r, s);
    }

    // NUOVO: Controlla se il nodo corrente o uno dei suoi genitori ha l'annotazione di soppressione
    private boolean isSuppressed(Node node, String ruleId) {
        if (node == null) return false;

        if (node instanceof NodeWithAnnotations) {
            NodeWithAnnotations<?> annotatedNode = (NodeWithAnnotations<?>) node;
            boolean suppressed = annotatedNode.getAnnotationByName("SuppressWarnings")
                    .map(expr -> {
                        String val = expr.toString();
                        return val.contains("\"sentinel:" + ruleId + "\"") || val.contains("\"sentinel:all\"");
                    })
                    .orElse(false);
            if (suppressed) return true;
        }

        // Risaliamo l'albero: se la classe è soppressa, lo sono anche i suoi metodi e campi!
        return isSuppressed(node.getParentNode().orElse(null), ruleId);
    }

    private boolean isRuleActive(String id) {
        return config.getActiveRules().contains(id);
    }

    private boolean isRuleApplicable(String ruleId, String filePath) {
        if (!isRuleActive(ruleId)) return false;

        String normalizedPath = filePath.replace("\\", "/");

        String includes = config.getParameter(ruleId, "includePaths", "");
        if (!includes.isEmpty()) {
            boolean matchesInclude = Arrays.stream(includes.split(","))
                    .map(String::trim)
                    .anyMatch(normalizedPath::matches);
            if (!matchesInclude) return false;
        }

        String excludes = config.getParameter(ruleId, "excludePaths", "");
        if (!excludes.isEmpty()) {
            boolean matchesExclude = Arrays.stream(excludes.split(","))
                    .map(String::trim)
                    .anyMatch(normalizedPath::matches);
            if (matchesExclude) return false;
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
        if (isRuleApplicable("SEC-001", filePath)) {
            String pattern = config.getParameter("SEC-001", "pattern", ".*(password|secret|apikey|pwd|token).*");
            checkHardcodedSecrets(cu, filePath, pattern);
        }
        
        if (isRuleApplicable("ARCH-003", filePath)) {
            int maxDeps = Integer.parseInt(config.getParameter("ARCH-003", "maxDependencies", "7"));
            checkFatComponents(cu, filePath, maxDeps);
        }

        if (isRuleApplicable("PERF-001", filePath)) checkJPAEager(cu, filePath);
        if (isRuleApplicable("PERF-002", filePath)) checkNPlusOne(cu, filePath);
        if (isRuleApplicable("PERF-003", filePath)) checkBlockingTransactional(cu, filePath);
        if (isRuleApplicable("PERF-004", filePath)) checkCacheTTL(cu, filePath, props);
        if (isRuleApplicable("PERF-005", filePath)) checkLombokDataOnEntity(cu, filePath);
        if (isRuleApplicable("RES-001", filePath)) checkManualThreads(cu, filePath);
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
                            addIssue(anno, "REST-001", f, anno.getBegin().map(p -> p.line).orElse(0),
                                type, "Non-standard URL naming", 
                                "URL '" + url + "' should use kebab-case (lowercase with hyphens).");
                        }
                        if (isRuleApplicable("REST-002", f) && !url.matches(".*/v[0-9]+.*") && !url.equals("/")) {
                            addIssue(anno, "REST-002", f, anno.getBegin().map(p -> p.line).orElse(0),
                                type, "Missing API Versioning", 
                                "URL '" + url + "' is missing a version prefix (e.g., /v1).");
                        }
                        if (isRuleApplicable("REST-003", f) && isSingular(url)) {
                            addIssue(anno, "REST-003", f, anno.getBegin().map(p -> p.line).orElse(0),
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
                addIssue(field, "SEC-001", f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Security", "Potential Hardcoded Secret", 
                    "Variable '" + name + "' matches the security pattern. Move sensitive data to environment variables.");
            }
        });
    }

    protected void checkFatComponents(CompilationUnit cu, String f, int maxDeps) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            long injectedFields = clazz.findAll(FieldDeclaration.class).stream().filter(this::isInjectedField).count();
            
            int constructorParams = clazz.getConstructors().stream()
                    .mapToInt(c -> c.getParameters().size())
                    .max()
                    .orElse(0); 
            
            if ((injectedFields + constructorParams) > maxDeps) {
                addIssue(clazz, "ARCH-003", f, clazz.getBegin().map(p -> p.line).orElse(0), "Architecture", "Fat Component", 
                    "Class has " + (injectedFields + constructorParams) + " dependencies, exceeding the limit of " + maxDeps + ".");
            }
        });
    }

    protected void checkManualInstantiation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getTypeAsString();
            if (type.endsWith("Service") || type.endsWith("Repository") || type.endsWith("Component")) {
                addIssue(newExpr, "ARCH-004", f, newExpr.getBegin().map(p -> p.line).orElse(0), 
                    "Design Smell", "Manual Instantiation of Spring Bean", 
                    "Avoid 'new " + type + "()'. Let Spring manage bean lifecycle via Dependency Injection.");
            }
        });
    }

    protected void checkJPAEager(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.toString().contains("FetchType.EAGER")) {
                addIssue(field, "PERF-001", f, field.getBegin().map(p -> p.line).orElse(0), 
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
                    addIssue(call, "PERF-002", f, call.getBegin().map(p -> p.line).orElse(0), 
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
                    addIssue(call, "PERF-003", f, call.getBegin().map(p -> p.line).orElse(0), 
                        "Concurrency", "Blocking call in Transaction", 
                        "Avoid network/IO calls inside @Transactional.");
                }
            }));
    }

    protected void checkManualThreads(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread") || n.getTypeAsString().equals("ExecutorService")) {
                addIssue(n, "RES-001", f, n.getBegin().map(p -> p.line).orElse(0), 
                    "Resource Mgmt", "Manual Thread creation", 
                    "Manual threading is discouraged in Spring. Use @Async.");
            }
        });
    }

    protected void checkCacheTTL(CompilationUnit cu, String f, Properties p) {
        boolean hasTTL = p.keySet().stream().anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));
        if (!hasTTL) {
            cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.isAnnotationPresent("Cacheable"))
                .forEach(m -> addIssue(m, "PERF-004", f, m.getBegin().map(pos -> pos.line).orElse(0), 
                    "Caching", "Cache missing TTL", 
                    "Define a Time-To-Live (TTL) for caches in application.properties."));
        }
    }

    protected void checkLombokDataOnEntity(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            boolean isEntity = clazz.isAnnotationPresent("Entity");
            boolean hasBadLombok = clazz.isAnnotationPresent("Data") || clazz.isAnnotationPresent("EqualsAndHashCode");
            
            if (isEntity && hasBadLombok) {
                addIssue(clazz, "PERF-005", f, clazz.getBegin().map(p -> p.line).orElse(0), 
                    "Database", "Lombok @Data on @Entity", 
                    "Severe anti-pattern detected. Remove @Data or @EqualsAndHashCode from JPA @Entity to avoid infinite loops and Set collections corruption. Use @Getter and @Setter instead.");
            }
        });
    }

    protected void checkTransactionTimeout(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.getAnnotationByName("Transactional").ifPresent(a -> {
                if (!a.toString().contains("timeout")) {
                    addIssue(m, "RES-002", f, m.getBegin().map(p -> p.line).orElse(0), 
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
                    addIssue(c, "MAINT-003", f, c.getBegin().map(p -> p.line).orElse(0), 
                        "Best Practice", "Missing @Repository", 
                        "Add @Repository to your data access interfaces.");
                }
            });
    }

    protected void checkAutowiredFieldInjection(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Autowired") || field.isAnnotationPresent("Inject")) {
                addIssue(field, "ARCH-002", f, field.getBegin().map(p -> p.line).orElse(0), 
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
                    addIssue(a, "SEC-002", f, a.getBegin().map(p -> p.line).orElse(0), 
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
                    .forEach(m -> addIssue(m, "REST-004", f, m.getBegin().map(p -> p.line).orElse(0), 
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
                            addIssue(field, "ARCH-001", f, field.getBegin().map(p -> p.line).orElse(0), 
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
                addIssue(field, "ARCH-005", f, field.getBegin().map(p -> p.line).orElse(0), 
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