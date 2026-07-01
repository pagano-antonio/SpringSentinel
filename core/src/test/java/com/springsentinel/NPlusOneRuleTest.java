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

import static org.junit.jupiter.api.Assertions.assertTrue;

class NPlusOneRuleTest {

    private static final String EXPECTED_SUGGESTION =
            "This code may be affected by the N+1 query problem, where related entities are loaded one by one, resulting in excessive database queries. Consider fetching associations eagerly using JOIN FETCH or @EntityGraph, enabling batch fetching, or redesigning the query to load all required data in a single database round trip.";

    @Test
    void shouldProvideDetailedNPlusOneRecommendation() {
        List<StaticAnalysisCore.AuditIssue> issues = new ArrayList<>();
        ResolvedConfig config = new ResolvedConfig(Set.of("PERF-002"), new HashMap<>());
        AnalysisRules rules = new AnalysisRules(issues::add, config);

        rules.runAllChecks(StaticJavaParser.parse("""
                class OrderService {
                    void loadOrders(List<User> users) {
                        for (User user : users) {
                            user.getOrders();
                        }
                    }
                }
                """), "OrderService.java", new Properties());

        assertTrue(issues.stream().anyMatch(issue ->
                "Potential N+1".equals(issue.reason) &&
                EXPECTED_SUGGESTION.equals(issue.suggestion)));
    }
}
