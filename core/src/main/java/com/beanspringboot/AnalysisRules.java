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

import java.util.*;
import java.util.function.Consumer;

/**
 * Core analysis logic for Spring Boot projects.
 * CLEAN VERSION (no Maven dependencies)
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

    private void addIssue(Node node, String ruleId, String f, int l, String t, String r, String s) {
        if (isSuppressed(node, ruleId)) return;
        addIssue(f, l, t, r, s);
    }

    private boolean isSuppressed(Node node, String ruleId) {
        if (node == null) return false;

        if (node instanceof NodeWithAnnotations<?> annotatedNode) {
            boolean suppressed = annotatedNode.getAnnotationByName("SuppressWarnings")
                    .map(expr -> {
                        String val = expr.toString();
                        return val.contains("\"sentinel:" + ruleId + "\"") ||
                               val.contains("\"sentinel:all\"");
                    })
                    .orElse(false);
            if (suppressed) return true;
        }

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

    // ===============================
    // ✅ PROJECT CHECKS (NEUTRO)
    // ===============================

    public void runProjectChecks(ProjectInfo project) {
        if (project == null) return;

        if (isRuleActive("SEC-003") || isRuleActive("MAINT-001")) {
            checkDependencyConflicts(project);
        }

        if (isRuleActive("MAINT-002")) {
            checkMissingProductionPlugins(project);
        }
    }

    private void checkDependencyConflicts(ProjectInfo project) {
        for (String artifactId : project.dependencies) {

            if (isRuleActive("SEC-003") && "spring-boot-starter-data-rest".equals(artifactId)) {
                addIssue("build config", 0, "Architecture", "Exposed Repositories (Data REST)",
                        "Spring Data REST exposes repositories automatically. Secure endpoints properly.");
            }

            if (isRuleActive("MAINT-001") && "spring-boot-starter".equals(artifactId)) {
                addIssue("build config", 0, "Maintenance", "Spring Boot Version Check",
                        "Ensure you are using Spring Boot 3.x.");
            }
        }
    }

    private void checkMissingProductionPlugins(ProjectInfo project) {
        boolean hasSpringPlugin = project.plugins.stream()
                .anyMatch(p -> p.contains("spring-boot"));

        if (!hasSpringPlugin) {
            addIssue("build config", 0, "Build", "Missing Spring Boot Plugin",
                    "Spring Boot plugin not detected. Required for executable packaging.");
        }
    }

    // ===============================
    // JAVA ANALYSIS
    // ===============================

    public void runAllChecks(CompilationUnit cu, String filePath, Properties props) {

        if (isRuleApplicable("SEC-001", filePath)) {
            String pattern = config.getParameter("SEC-001", "pattern",
                    ".*(password|secret|apikey|pwd|token).*");
            checkHardcodedSecrets(cu, filePath, pattern);
        }

        if (isRuleApplicable("ARCH-003", filePath)) {
            int maxDeps = Integer.parseInt(
                    config.getParameter("ARCH-003", "maxDependencies", "7"));
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

        if (isRuleApplicable("REST-001", filePath) ||
            isRuleApplicable("REST-002", filePath) ||
            isRuleApplicable("REST-003", filePath)) {
            checkRestfulNaming(cu, filePath);
        }
    }

    // ===============================
    // RULES (immutate)
    // ===============================

    protected void checkHardcodedSecrets(CompilationUnit cu, String f, String pattern) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            String name = field.getVariable(0).getNameAsString().toLowerCase();
            if (name.matches(pattern)) {
                addIssue(field, "SEC-001", f,
                        field.getBegin().map(p -> p.line).orElse(0),
                        "Security", "Potential Hardcoded Secret",
                        "Move sensitive data to environment variables.");
            }
        });
    }

    protected void checkFatComponents(CompilationUnit cu, String f, int maxDeps) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            long injectedFields = clazz.findAll(FieldDeclaration.class)
                    .stream().filter(this::isInjectedField).count();

            int constructorParams = clazz.getConstructors().stream()
                    .mapToInt(c -> c.getParameters().size()).max().orElse(0);

            if ((injectedFields + constructorParams) > maxDeps) {
                addIssue(clazz, "ARCH-003", f,
                        clazz.getBegin().map(p -> p.line).orElse(0),
                        "Architecture", "Fat Component",
                        "Too many dependencies.");
            }
        });
    }

    protected void checkManualInstantiation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getTypeAsString();
            if (type.endsWith("Service") || type.endsWith("Repository") || type.endsWith("Component")) {
                addIssue(newExpr, "ARCH-004", f,
                        newExpr.getBegin().map(p -> p.line).orElse(0),
                        "Design Smell", "Manual Instantiation",
                        "Use dependency injection.");
            }
        });
    }

    protected void checkJPAEager(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.toString().contains("FetchType.EAGER")) {
                addIssue(field, "PERF-001", f,
                        field.getBegin().map(p -> p.line).orElse(0),
                        "Performance", "EAGER Fetching",
                        "Use LAZY.");
            }
        });
    }

    protected void checkNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(loop -> {
            loop.findAll(MethodCallExpr.class).forEach(call -> {
                if (call.getNameAsString().startsWith("get")) {
                    addIssue(call, "PERF-002", f,
                            call.getBegin().map(p -> p.line).orElse(0),
                            "Database", "Potential N+1",
                            "Avoid inside loops.");
                }
            });
        });
    }

    protected void checkBlockingTransactional(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.isAnnotationPresent("Transactional"))
                .forEach(m -> m.findAll(MethodCallExpr.class).forEach(call -> {
                    if (BLOCKING_CALLS.stream().anyMatch(b ->
                            call.toString().toLowerCase().contains(b))) {
                        addIssue(call, "PERF-003", f,
                                call.getBegin().map(p -> p.line).orElse(0),
                                "Concurrency", "Blocking call",
                                "Avoid in transactions.");
                    }
                }));
    }

    protected void checkManualThreads(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread")) {
                addIssue(n, "RES-001", f,
                        n.getBegin().map(p -> p.line).orElse(0),
                        "Resource Mgmt", "Manual Thread",
                        "Use @Async.");
            }
        });
    }

    private boolean isSpringComponent(ClassOrInterfaceDeclaration clazz) {
        return clazz.isAnnotationPresent("Service") ||
               clazz.isAnnotationPresent("RestController") ||
               clazz.isAnnotationPresent("Component") ||
               clazz.isAnnotationPresent("Repository");
    }

    private boolean isInjectedField(FieldDeclaration field) {
        return field.isAnnotationPresent("Autowired") ||
               field.isAnnotationPresent("Inject");
    }
    protected void checkCacheTTL(CompilationUnit cu, String f, Properties p) {
        boolean hasTTL = p.keySet().stream()
                .anyMatch(k -> k.toString().toLowerCase().contains("ttl") ||
                               k.toString().toLowerCase().contains("expire"));

        if (!hasTTL) {
            cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.isAnnotationPresent("Cacheable"))
                    .forEach(m -> addIssue(m, "PERF-004", f,
                            m.getBegin().map(pos -> pos.line).orElse(0),
                            "Caching", "Cache missing TTL",
                            "Define TTL in application.properties."));
        }
    }

    protected void checkLombokDataOnEntity(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            boolean isEntity = clazz.isAnnotationPresent("Entity");
            boolean hasBadLombok = clazz.isAnnotationPresent("Data") ||
                                  clazz.isAnnotationPresent("EqualsAndHashCode");

            if (isEntity && hasBadLombok) {
                addIssue(clazz, "PERF-005", f,
                        clazz.getBegin().map(p -> p.line).orElse(0),
                        "Database", "Lombok @Data on @Entity",
                        "Use @Getter/@Setter instead.");
            }
        });
    }

    protected void checkTransactionTimeout(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.getAnnotationByName("Transactional").ifPresent(a -> {
                if (!a.toString().contains("timeout")) {
                    addIssue(m, "RES-002", f,
                            m.getBegin().map(p -> p.line).orElse(0),
                            "Resilience", "Missing Transaction Timeout",
                            "Define timeout.");
                }
            });
        });
    }

    protected void checkMissingRepositoryAnnotation(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().endsWith("Repository"))
                .forEach(c -> {
                    if (!c.isAnnotationPresent("Repository")) {
                        addIssue(c, "MAINT-003", f,
                                c.getBegin().map(p -> p.line).orElse(0),
                                "Best Practice", "Missing @Repository",
                                "Add annotation.");
                    }
                });
    }

    protected void checkAutowiredFieldInjection(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Autowired") ||
                field.isAnnotationPresent("Inject")) {
                addIssue(field, "ARCH-002", f,
                        field.getBegin().map(p -> p.line).orElse(0),
                        "Architecture", "Field Injection",
                        "Use constructor injection.");
            }
        });
    }

    protected void checkCrossOriginWildcard(CompilationUnit cu, String f) {
        cu.findAll(AnnotationExpr.class).stream()
                .filter(a -> a.getNameAsString().equals("CrossOrigin"))
                .forEach(a -> {
                    if (a.toString().equals("@CrossOrigin") ||
                        a.toString().contains("\"*\"")) {
                        addIssue(a, "SEC-002", f,
                                a.getBegin().map(p -> p.line).orElse(0),
                                "Security", "Insecure CORS",
                                "Avoid wildcard.");
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
                            .forEach(m -> addIssue(m, "REST-004", f,
                                    m.getBegin().map(p -> p.line).orElse(0),
                                    "Best Practice", "Missing ResponseEntity",
                                    "Return ResponseEntity."));
                });
    }

    protected void checkBeanScopesAndThreadSafety(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (isSpringComponent(clazz)) {
                clazz.findAll(FieldDeclaration.class).forEach(field -> {
                    if (!field.isFinal() && !isInjectedField(field) && !field.isStatic()) {
                        addIssue(field, "ARCH-001", f,
                                field.getBegin().map(p -> p.line).orElse(0),
                                "Thread Safety", "Mutable state",
                                "Avoid mutable fields.");
                    }
                });
            }
        });
    }

    protected void checkLazyInjectionSmell(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Lazy") &&
                field.isAnnotationPresent("Autowired")) {
                addIssue(field, "ARCH-005", f,
                        field.getBegin().map(p -> p.line).orElse(0),
                        "Design Smell", "Lazy Injection",
                        "Possible circular dependency.");
            }
        });
    }
    protected void checkRestfulNaming(CompilationUnit cu, String f) {
        cu.findAll(AnnotationExpr.class).forEach(anno -> {
            String name = anno.getNameAsString();

            if (name.endsWith("Mapping")) {
                anno.getChildNodes().forEach(node -> {

                    String url = node.toString().replace("\"", "");

                    if (url.startsWith("/")) {

                        String type = "REST Design";

                        if (isRuleApplicable("REST-001", f) &&
                            (url.matches(".*[A-Z].*") || url.contains("_"))) {

                            addIssue(anno, "REST-001", f,
                                    anno.getBegin().map(p -> p.line).orElse(0),
                                    type, "Non-standard URL naming",
                                    "Use kebab-case (lowercase + hyphens).");
                        }

                        if (isRuleApplicable("REST-002", f) &&
                            !url.matches(".*/v[0-9]+.*") &&
                            !url.equals("/")) {

                            addIssue(anno, "REST-002", f,
                                    anno.getBegin().map(p -> p.line).orElse(0),
                                    type, "Missing API versioning",
                                    "Add version prefix (/v1).");
                        }

                        if (isRuleApplicable("REST-003", f) && isSingular(url)) {

                            addIssue(anno, "REST-003", f,
                                    anno.getBegin().map(p -> p.line).orElse(0),
                                    type, "Singular resource name",
                                    "Use plural form (/users).");
                        }
                    }
                });
            }
        });
    }
    private boolean isSingular(String url) {
        String[] parts = url.split("/");
        if (parts.length == 0) return false;

        String last = parts[parts.length - 1];

        return !last.isEmpty() &&
               !last.endsWith("s") &&
               !last.contains("{");
    }
}