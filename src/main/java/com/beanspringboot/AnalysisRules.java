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

import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

public class AnalysisRules {

    private static final List<String> BLOCKING_CALLS = List.of(
            "resttemplate", "webclient", "feignclient", "httpclient", "thread.sleep"
    );

    private final Consumer<StaticAnalysisCore.AuditIssue> issueConsumer;

    public AnalysisRules(Consumer<StaticAnalysisCore.AuditIssue> issueConsumer) {
        this.issueConsumer = issueConsumer;
    }

    private void addIssue(String f, int l, String t, String r, String s) {
        issueConsumer.accept(new StaticAnalysisCore.AuditIssue(f, l, t, r, s));
    }

    // --- HOLISTIC ANALYSIS (POM.XML) ---

    public void runProjectChecks(MavenProject project) {
        if (project == null) return;
        checkDependencyConflicts(project);
        checkMissingProductionPlugins(project);
    }

    private void checkDependencyConflicts(MavenProject project) {
        for (Object depObj : project.getDependencies()) {
            Dependency dep = (Dependency) depObj;
            String artifactId = dep.getArtifactId();
            
            if ("spring-boot-starter-data-rest".equals(artifactId)) {
                addIssue("pom.xml", 0, "Architecture", "Exposed Repositories (Data REST)", 
                    "Spring Data REST automatically exposes repositories. Check the security of the endpoints.");
            }
            
            if ("spring-boot-starter".equals(artifactId) && dep.getVersion() != null) {
                if (dep.getVersion().startsWith("2.")) {
                    addIssue("pom.xml", 0, "Maintenance", "Old Spring Boot Version", 
                        "You are using Spring Boot 2.x. Consider upgrading to Spring Boot 3.x.");
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
                "Add 'spring-boot-maven-plugin' to generate executable JARs.");
        }
    }

    // --- JAVA CODE ANALYSIS (WITH FLEXIBLE PARAMETERS) ---

    public void runAllChecks(CompilationUnit cu, String fileName, Properties props, int maxDeps, String sPattern) {
        checkJPAEager(cu, fileName);
        checkNPlusOne(cu, fileName);
        checkBlockingTransactional(cu, fileName);
        checkManualThreads(cu, fileName);
        checkCacheTTL(cu, fileName, props);
        checkTransactionTimeout(cu, fileName);
        checkMissingRepositoryAnnotation(cu, fileName);
        checkAutowiredFieldInjection(cu, fileName);
        checkHardcodedSecrets(cu, fileName, sPattern);
        checkCrossOriginWildcard(cu, fileName);
        checkMissingResponseEntity(cu, fileName);
        checkBeanScopesAndThreadSafety(cu, fileName);
        checkFatComponents(cu, fileName, maxDeps);
        checkLazyInjectionSmell(cu, fileName);
        checkManualInstantiation(cu, fileName);
    }

    protected void checkHardcodedSecrets(CompilationUnit cu, String f, String pattern) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            String name = field.getVariable(0).getNameAsString().toLowerCase();
            if (name.matches(pattern)) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Security", "Potential Hardcoded Secret", 
                    "The variable '" + name + "' matches the security pattern. Use environment variables.");
            }
        });
    }

    protected void checkFatComponents(CompilationUnit cu, String f, int maxDeps) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            long injectedFields = clazz.findAll(FieldDeclaration.class).stream().filter(this::isInjectedField).count();
            int constructorParams = clazz.getConstructors().stream().mapToInt(c -> c.getParameters().size()).max().orElse(0);
            
            if ((injectedFields + constructorParams) > maxDeps) {
                addIssue(f, clazz.getBegin().map(p -> p.line).orElse(0), "Architecture", "Fat Component", 
                    "This class exceeds the limit of " + maxDeps + " dependencies. Consider refactoring.");
            }
        });
    }

    protected void checkManualInstantiation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getTypeAsString();
            if (type.endsWith("Service") || type.endsWith("Repository") || type.endsWith("Component")) {
                addIssue(f, newExpr.getBegin().map(p -> p.line).orElse(0), 
                    "Design Smell", "Manual Instantiation of Spring Bean", 
                    "Avoid 'new " + type + "()'. Use Dependency Injection.");
            }
        });
    }

    // --- OTHER CHECKS ---

    protected void checkJPAEager(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getAnnotations().forEach(anno -> {
                if (anno.toString().contains("FetchType.EAGER")) {
                    addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                        "JPA Performance", "EAGER Fetching detected", 
                        "Switch to LAZY fetching.");
                }
            });
        });
    }

    protected void checkNPlusOne(CompilationUnit cu, String f) {
        cu.findAll(ForEachStmt.class).forEach(loop -> {
            loop.findAll(MethodCallExpr.class).forEach(call -> {
                if (call.getNameAsString().startsWith("get") && 
                   (call.getNameAsString().endsWith("s") || call.getNameAsString().endsWith("List"))) {
                    addIssue(f, call.getBegin().map(p -> p.line).orElse(0), 
                        "Database", "Potential N+1 Query", 
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
                        addIssue(f, call.getBegin().map(p -> p.line).orElse(0), 
                            "Concurrency", "Blocking call in Transaction", 
                            "Move I/O operations outside of @Transactional.");
                    }
                });
            });
    }

    protected void checkManualThreads(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(n -> {
            if (n.getTypeAsString().equals("Thread")) {
                addIssue(f, n.getBegin().map(p -> p.line).orElse(0), 
                    "Resource Mgmt", "Manual Thread creation", 
                    "Use @Async.");
            }
        });
    }

    protected void checkCacheTTL(CompilationUnit cu, String f, Properties p) {
        if (cu.findAll(MethodDeclaration.class).stream()
              .noneMatch(m -> m.isAnnotationPresent("Cacheable"))) return;

        boolean hasTTL = p.keySet().stream()
                .anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));

        if (!hasTTL) {
            addIssue(f, 0, "Caching", "Cache missing TTL", 
                "Define the expiration in application.properties.");
        }
    }

    protected void checkTransactionTimeout(CompilationUnit cu, String f) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
            m.getAnnotationByName("Transactional").ifPresent(a -> {
                if (!a.toString().contains("timeout")) {
                    addIssue(f, m.getBegin().map(p -> p.line).orElse(0), 
                        "Resilience", "Missing Transaction Timeout", 
                        "Add a timeout.");
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
                        "Add @Repository.");
                }
            });
    }

    protected void checkAutowiredFieldInjection(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Autowired")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Architecture", "Field Injection", 
                    "Use constructor injection.");
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
                        "Specify explicit origins.");
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
                    .forEach(m -> addIssue(f, m.getBegin().map(p -> p.line).orElse(0), 
                        "Best Practice", "Missing ResponseEntity", 
                        "Use ResponseEntity."));
            });
    }

    protected void checkBeanScopesAndThreadSafety(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (isSpringComponent(clazz)) {
                clazz.findAll(FieldDeclaration.class).forEach(field -> {
                    if (!field.isFinal() && !isInjectedField(field) && !field.isStatic()) {
                        addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                            "Thread Safety", "Mutable state in Singleton", 
                            "Make the fields final.");
                    }
                });
            }
        });
    }

    protected void checkLazyInjectionSmell(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Lazy") && field.isAnnotationPresent("Autowired")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), 
                    "Design Smell", "Lazy Injection", 
                    "Avoid using @Lazy to hide circular dependencies.");
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
               field.isAnnotationPresent("Value") ||
               field.isAnnotationPresent("Resource") || 
               field.isAnnotationPresent("Inject");
    }
}
