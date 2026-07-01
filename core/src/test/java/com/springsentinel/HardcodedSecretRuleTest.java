package com.springsentinel;

import com.beanspringboot.AnalysisRules;
import com.beanspringboot.ResolvedConfig;
import com.beanspringboot.StaticAnalysisCore;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardcodedSecretRuleTest {

    @Test
    void shouldReportPasswordWhenNoProtectionIsPresent() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class Credentials {
                    private String password;
                }
                """);

        assertTrue(hasSecretIssue(issues));
        assertTrue(issues.stream().anyMatch(issue ->
                issue.suggestion.contains("PasswordEncoder") &&
                issue.suggestion.contains("BCrypt")));
    }

    @Test
    void shouldReportPwdWhenNoProtectionIsPresent() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class Credentials {
                    private String pwd;
                }
                """);

        assertTrue(hasSecretIssue(issues));
    }

    @Test
    void shouldNotReportPasswordWhenPasswordEncoderIsUsed() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class CredentialsService {
                    private String password;

                    String protect(String password) {
                        return passwordEncoder.encode(password);
                    }
                }
                """);

        assertFalse(hasSecretIssue(issues));
    }

    @Test
    void shouldRecognizePasswordEncoderWhenVariableHasGenericName() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class CredentialsService {
                    private String password;
                    private PasswordEncoder encoder;

                    String protect(String raw) {
                        return encoder.encode(raw);
                    }
                }
                """);

        assertFalse(hasSecretIssue(issues));
    }

    @Test
    void shouldFindPasswordProtectionInAnotherSourceFile() {
        CompilationUnit credentials = StaticJavaParser.parse("""
                class Credentials {
                    private String password;
                }
                """);
        CompilationUnit encoder = StaticJavaParser.parse("""
                class PasswordService {
                    String protect(String rawPassword) {
                        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
                    }
                }
                """);
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        AnalysisRules rules = rules(issues);

        rules.indexPasswordProtection(List.of(credentials, encoder));
        rules.runAllChecks(credentials, "Credentials.java", new Properties());

        assertFalse(hasSecretIssue(issues));
    }

    @Test
    void shouldNotTreatBase64AsPasswordProtection() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class CredentialsService {
                    private String password;

                    String encode(String password) {
                        return Base64.getEncoder().encodeToString(password.getBytes());
                    }
                }
                """);

        assertTrue(hasSecretIssue(issues));
    }

    @Test
    void shouldNotTreatPasswordVerificationAsProtection() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class CredentialsService {
                    private String password;

                    boolean verify(String password, String hash) {
                        return passwordEncoder.matches(password, hash);
                    }
                }
                """);

        assertTrue(hasSecretIssue(issues));
    }

    @Test
    void shouldKeepExistingChecksForOtherSecrets() {
        List<StaticAnalysisCore.AuditIssue> issues = analyze("""
                class ApiClient {
                    private String apiKey;
                }
                """);

        assertTrue(hasSecretIssue(issues));
        assertTrue(issues.stream().anyMatch(issue ->
                issue.suggestion.contains("environment variables")));
    }

    private List<StaticAnalysisCore.AuditIssue> analyze(String source) {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        AnalysisRules rules = rules(issues);
        rules.runAllChecks(
                StaticJavaParser.parse(source), "Credentials.java", new Properties());
        return issues;
    }

    private AnalysisRules rules(List<StaticAnalysisCore.AuditIssue> issues) {
        ResolvedConfig config = new ResolvedConfig(Set.of("SEC-001"), new HashMap<>());
        return new AnalysisRules(issues::add, config);
    }

    private boolean hasSecretIssue(List<StaticAnalysisCore.AuditIssue> issues) {
        return issues.stream().anyMatch(
                issue -> "Potential Hardcoded Secret".equals(issue.reason));
    }
}
