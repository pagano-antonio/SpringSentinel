package com.springsentinel;

import com.beanspringboot.ResolvedConfig;
import com.beanspringboot.StaticAnalysisCore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigurationRulesTest {

    @Test
    void shouldDetectMissingHikariPoolConfigurationWhenDatasourceIsConfigured() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/app");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasIssue(issues, "HikariCP is being used with implicit defaults."));
        assertTrue(issues.stream().anyMatch(issue -> "Configuration".equals(issue.type)));
    }

    @Test
    void shouldNotDetectMissingHikariPoolConfigurationWhenPoolIsConfigured() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/app");
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "20");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "HikariCP is being used with implicit defaults."));
    }

    @Test
    void shouldNotDetectMissingHikariPoolConfigurationWhenMaxLifetimeIsConfigured() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/app");
        props.setProperty("spring.datasource.hikari.max-lifetime", "1800000");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "HikariCP is being used with implicit defaults."));
    }

    @Test
    void shouldDetectMissingHikariPoolConfigurationWhenHikariTypeIsExplicit() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/app");
        props.setProperty("spring.datasource.type", "com.zaxxer.hikari.HikariDataSource");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasIssue(issues, "HikariCP is being used with implicit defaults."));
    }

    @Test
    void shouldNotDetectMissingHikariPoolConfigurationWhenDifferentPoolIsExplicit() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/app");
        props.setProperty("spring.datasource.type", "org.apache.tomcat.jdbc.pool.DataSource");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "HikariCP is being used with implicit defaults."));
    }

    @Test
    void shouldNotDetectMissingHikariPoolConfigurationWhenDatasourceIsNotConfigured() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "HikariCP is being used with implicit defaults."));
    }

    @Test
    void shouldDetectPotentialHikariOversizingAboveFiftyConnections() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "60");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasSuggestion(issues,
                "HikariCP creator recommends small pools. Oversized pools may increase contention and latency."));
    }

    @Test
    void shouldDetectExtremeHikariOversizingAboveOneHundredConnections() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "120");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasSuggestion(issues,
                "Extremely large connection pool detected. This is often a sign of architecture or database contention issues."));
        assertFalse(hasSuggestion(issues,
                "HikariCP creator recommends small pools. Oversized pools may increase contention and latency."));
    }

    @Test
    void shouldDetectPoolSizeThatExceedsTomcatThreadCountByFourTimes() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("server.tomcat.max-threads", "50");
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "200");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasSuggestion(issues,
                "Pool size exceeds servlet thread count by 4×. This may indicate oversizing."));
    }

    @Test
    void shouldDetectPoolSizeThatExceedsJettyThreadCountByFourTimes() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("server.jetty.threads.max", "25");
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "100");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasSuggestion(issues,
                "Pool size exceeds servlet thread count by 4×. This may indicate oversizing."));
    }

    @Test
    void shouldNotDetectHikariOversizingAtFiftyConnections() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "50");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "Potential HikariCP Oversizing"));
    }

    @Test
    void shouldDetectDynamicHikariPoolSizingWhenMinimumIdleDiffersFromMaximumPoolSize() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "30");
        props.setProperty("spring.datasource.hikari.minimum-idle", "10");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasIssue(issues, "Dynamic HikariCP pool sizing"));
    }

    @Test
    void shouldNotDetectDynamicHikariPoolSizingWhenMinimumIdleEqualsMaximumPoolSize() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "30");
        props.setProperty("spring.datasource.hikari.minimum-idle", "30");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "Dynamic HikariCP pool sizing"));
    }

    @Test
    void shouldNotDetectDynamicHikariPoolSizingWhenMinimumIdleIsMissing() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.maximum-pool-size", "30");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues, "Dynamic HikariCP pool sizing"));
    }

    @Test
    void shouldDetectMisalignedLeakDetectionThresholdAgainstTransactionTimeout() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.leak-detection-threshold", "10000");
        props.setProperty("spring.transaction.default-timeout", "20");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasIssue(issues,
                "Hikari leak detection threshold is lower than the configured transaction/query timeout."));
    }

    @Test
    void shouldDetectMisalignedLeakDetectionThresholdAgainstJpaQueryTimeout() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.leakDetectionThreshold", "10000");
        props.setProperty("javax.persistence.query.timeout", "20000");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasIssue(issues,
                "Hikari leak detection threshold is lower than the configured transaction/query timeout."));
    }

    @Test
    void shouldUseLongestConfiguredTimeoutForLeakDetectionComparison() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.leak-detection-threshold", "20000");
        props.setProperty("spring.transaction.default-timeout", "10");
        props.setProperty("hibernate.jdbc.timeout", "40");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertTrue(hasIssue(issues,
                "Hikari leak detection threshold is lower than the configured transaction/query timeout."));
    }

    @Test
    void shouldNotDetectMisalignedLeakDetectionThresholdWhenAboveEightyPercent() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.leak-detection-threshold", "17000");
        props.setProperty("spring.transaction.default-timeout", "20");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues,
                "Hikari leak detection threshold is lower than the configured transaction/query timeout."));
    }

    @Test
    void shouldNotDetectMisalignedLeakDetectionThresholdWhenNoTimeoutIsConfigured() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        Properties props = new Properties();
        props.setProperty("spring.datasource.hikari.leak-detection-threshold", "10000");

        new StaticAnalysisCore(message -> { })
                .executeAnalysisWithPropsOnly(props, issues, configWithDbRules());

        assertFalse(hasIssue(issues,
                "Hikari leak detection threshold is lower than the configured transaction/query timeout."));
    }

    private static ResolvedConfig configWithDbRules() {
        return new ResolvedConfig(new HashSet<>(Set.of("DB-001", "DB-002", "DB-003", "DB-004")), new HashMap<>());
    }

    private static boolean hasIssue(List<StaticAnalysisCore.AuditIssue> issues, String reasonFragment) {
        return issues.stream().anyMatch(issue -> issue.reason.contains(reasonFragment));
    }

    private static boolean hasSuggestion(List<StaticAnalysisCore.AuditIssue> issues, String suggestionFragment) {
        return issues.stream().anyMatch(issue -> issue.suggestion.contains(suggestionFragment));
    }
}
