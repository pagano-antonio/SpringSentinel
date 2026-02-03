package com.beanspringboot;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;

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

    public void runAllChecks(CompilationUnit cu, String fileName, Properties props) {
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
        checkFatComponents(cu, fileName);
        checkLazyInjectionSmell(cu, fileName);
        
        // NUOVA REGOLA: Rileva l'uso di 'new' sui Bean di Spring
        checkManualInstantiation(cu, fileName);
    }

    protected void checkManualInstantiation(CompilationUnit cu, String f) {
        cu.findAll(ObjectCreationExpr.class).forEach(newExpr -> {
            String type = newExpr.getTypeAsString();
            if (type.endsWith("Service") || type.endsWith("Repository") || type.endsWith("Component")) {
                addIssue(f, newExpr.getBegin().map(p -> p.line).orElse(0), 
                    "Design Smell", 
                    "Manual Instantiation of Spring Bean", 
                    "Class '" + type + "' should be managed by Spring. Use Dependency Injection instead of 'new'.");
            }
        });
    }

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
        if (cu.findAll(MethodDeclaration.class).stream().noneMatch(m -> m.isAnnotationPresent("Cacheable"))) return;
        boolean hasTTL = p.keySet().stream().anyMatch(k -> k.toString().contains("ttl") || k.toString().contains("expire"));
        if (!hasTTL) addIssue(f, 0, "Caching", "Cache missing TTL", "Define expiration in application.properties.");
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
        cu.findAll(AnnotationExpr.class).stream().filter(a -> a.getNameAsString().equals("CrossOrigin")).forEach(a -> {
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

    protected void checkBeanScopesAndThreadSafety(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (isSpringComponent(clazz)) {
                clazz.findAll(FieldDeclaration.class).forEach(field -> {
                    if (!field.isFinal() && !isInjectedField(field) && !field.isStatic()) {
                        addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Thread Safety", "Mutable state in Singleton", "Fields in Singletons should be final.");
                    }
                });
            }
        });
    }

    protected void checkFatComponents(CompilationUnit cu, String f) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            long injectedFields = clazz.findAll(FieldDeclaration.class).stream().filter(this::isInjectedField).count();
            int constructorParams = clazz.getConstructors().stream().mapToInt(c -> c.getParameters().size()).max().orElse(0);
            if ((injectedFields + constructorParams) > 7) {
                addIssue(f, clazz.getBegin().map(p -> p.line).orElse(0), "Architecture", "Fat Component", "Consider refactoring dependencies.");
            }
        });
    }

    protected void checkLazyInjectionSmell(CompilationUnit cu, String f) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (field.isAnnotationPresent("Lazy") && field.isAnnotationPresent("Autowired")) {
                addIssue(f, field.getBegin().map(p -> p.line).orElse(0), "Design Smell", "Lazy Injection", "Decouple beans instead of hiding circular dependencies.");
            }
        });
    }

    private boolean isSpringComponent(ClassOrInterfaceDeclaration clazz) {
        return clazz.isAnnotationPresent("Service") || clazz.isAnnotationPresent("RestController") ||
               clazz.isAnnotationPresent("Component") || clazz.isAnnotationPresent("Repository");
    }

    private boolean isInjectedField(FieldDeclaration field) {
        return field.isAnnotationPresent("Autowired") || field.isAnnotationPresent("Value") ||
               field.isAnnotationPresent("Resource") || field.isAnnotationPresent("Inject");
    }
}