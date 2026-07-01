package com.springsentinel;

import com.beanspringboot.AnalysisRules;
import com.beanspringboot.ResolvedConfig;
import com.beanspringboot.StaticAnalysisCore;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingletonThreadSafetyRulesTest {

    @Test
    void shouldIgnoreSpringManagedEntityManager() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                import jakarta.persistence.EntityManager;
                import jakarta.persistence.PersistenceContext;

                @Service
                class UserService {
                    @PersistenceContext
                    private EntityManager entityManager;
                }
                """);

        assertFalse(hasMutableStateIssue(issues));
    }

    @Test
    void shouldIgnoreFullyQualifiedLegacyEntityManager() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Service
                class UserService {
                    private javax.persistence.EntityManager entityManager;
                }
                """);

        assertFalse(hasMutableStateIssue(issues));
    }

    @Test
    void shouldNotDetectFieldThatIsNeverModified() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Service
                class UserService {
                    private int counter;
                }
                """);

        assertFalse(hasMutableStateIssue(issues));
    }

    @Test
    void shouldDetectFieldAssignmentInsideMethod() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Service
                class UserService {
                    private User currentUser;

                    void login(User user) {
                        this.currentUser = user;
                    }
                }
                """);

        assertTrue(hasMutableStateIssue(issues));
    }

    @Test
    void shouldDetectUnqualifiedFieldIncrement() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Component
                class CounterService {
                    private int counter;

                    void increment() {
                        counter++;
                    }
                }
                """);

        assertTrue(hasMutableStateIssue(issues));
    }

    @Test
    void shouldIgnoreFieldAssignmentInsideConstructor() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Service
                class UserService {
                    private User currentUser;

                    UserService(User currentUser) {
                        this.currentUser = currentUser;
                    }
                }
                """);

        assertFalse(hasMutableStateIssue(issues));
    }

    @Test
    void shouldIgnoreAssignmentToShadowingLocalVariable() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Service
                class UserService {
                    private int counter;

                    void calculate() {
                        int counter = 0;
                        counter++;
                    }
                }
                """);

        assertFalse(hasMutableStateIssue(issues));
    }

    @Test
    void shouldIgnoreNonSingletonBean() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                @Service
                @Scope("prototype")
                class PrototypeService {
                    private int counter;

                    void increment() {
                        counter++;
                    }
                }
                """);

        assertFalse(hasMutableStateIssue(issues));
    }

    private List<StaticAnalysisCore.AuditIssue> analyze(String source) {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        ResolvedConfig config = new ResolvedConfig(Set.of("ARCH-001"), new HashMap<>());
        AnalysisRules rules = new AnalysisRules(issues::add, config);

        rules.runAllChecks(StaticJavaParser.parse(source), "UserService.java", new Properties());
        return issues;
    }

    private boolean hasMutableStateIssue(List<StaticAnalysisCore.AuditIssue> issues) {
        return issues.stream().anyMatch(
                issue -> "Mutable state in Singleton".equals(issue.reason));
    }
}
