package com.springsentinel;

import com.beanspringboot.ReportGenerator;
import com.beanspringboot.StaticAnalysisCore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportGeneratorTest {

    @TempDir
    Path outputDir;

    @Test
    void groupsOccurrencesOfTheSameErrorAndListsClassesAndLines() throws Exception {
        List<StaticAnalysisCore.AuditIssue> issues = List.of(
                new StaticAnalysisCore.AuditIssue(
                        "src/main/java/example/FirstService.java", 18,
                        "Thread Safety", "Mutable state", "Avoid mutable fields."),
                new StaticAnalysisCore.AuditIssue(
                        "src/main/java/example/SecondService.java", 42,
                        "Thread Safety", "Mutable state", "Avoid mutable fields."));

        new ReportGenerator().generateReports(outputDir.toFile(), issues, "strict");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertEquals(1, occurrences(html, "class='card issue-card"));
        assertTrue(html.contains("2 occorrenze"));
        assertTrue(html.contains("<span class='label'>Errore trovato</span>Mutable state"));
        assertTrue(html.contains("<span class='label'>Possibile soluzione</span>Avoid mutable fields."));
        assertTrue(html.contains("<span class='class-name'>FirstService</span>"));
        assertTrue(html.contains("<td class='line-number'>18</td>"));
        assertTrue(html.contains("<span class='class-name'>SecondService</span>"));
        assertTrue(html.contains("<td class='line-number'>42</td>"));
    }

    @Test
    void escapesUntrustedValuesAndShowsMissingLineAsNotAvailable() throws Exception {
        StaticAnalysisCore.AuditIssue issue = new StaticAnalysisCore.AuditIssue(
                "src/<Example>.java", 0,
                "Security", "Unsafe <script>", "Use A & B");

        new ReportGenerator().generateReports(outputDir.toFile(), List.of(issue), "custom<script>");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertTrue(html.contains("Unsafe &lt;script&gt;"));
        assertTrue(html.contains("Use A &amp; B"));
        assertTrue(html.contains("custom&lt;script&gt;"));
        assertTrue(html.contains("<td class='line-number'>N/D</td>"));
        assertFalse(html.contains("Unsafe <script>"));
    }

    @Test
    void jsonReportIncludesPriorityAndIssueTypeSummary() throws Exception {
        List<StaticAnalysisCore.AuditIssue> issues = List.of(
                new StaticAnalysisCore.AuditIssue("Security.java", 1,
                        "Security", "Unsafe", "Fix it"),
                new StaticAnalysisCore.AuditIssue("Repository.java", 2,
                        "Database", "N+1", "Fetch it"),
                new StaticAnalysisCore.AuditIssue("Controller.java", 3,
                        "REST Design", "Bad path", "Rename it"),
                new StaticAnalysisCore.AuditIssue("OtherController.java", 4,
                        "REST Design", "Bad verb", "Change it"),
                new StaticAnalysisCore.AuditIssue("Service.java", 5,
                        "Best Practice", "Missing annotation", "Add it"));

        new ReportGenerator().generateReports(outputDir.toFile(), issues, "strict");

        String json = Files.readString(outputDir.resolve("report.json"));
        assertTrue(json.contains("\"totalIssues\": 5"));
        assertTrue(json.contains("\"critical\": 1"));
        assertTrue(json.contains("\"high\": 1"));
        assertTrue(json.contains("\"warning\": 3"));
        assertTrue(json.contains("\"security\": 1"));
        assertTrue(json.contains("\"database\": 1"));
        assertTrue(json.contains("\"restDesign\": 2"));
        assertTrue(json.contains("\"bestPractice\": 1"));
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
