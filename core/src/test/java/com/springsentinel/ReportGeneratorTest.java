package com.springsentinel;

import com.beanspringboot.ReportGenerator;
import com.beanspringboot.ReportMessages;
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

    @Test
    void generatesCommentReportWithSeverityCategoriesAndReportList() throws Exception {
        List<StaticAnalysisCore.AuditIssue> issues = List.of(
                new StaticAnalysisCore.AuditIssue("Security.java", 1,
                        "Security", "Unsafe", "Fix it"),
                new StaticAnalysisCore.AuditIssue("Repository.java", 2,
                        "Database", "N+1", "Fetch it"),
                new StaticAnalysisCore.AuditIssue("OtherRepository.java", 3,
                        "Database", "N+1", "Fetch it"),
                new StaticAnalysisCore.AuditIssue("Controller.java", 4,
                        "REST Design", "Bad path", "Rename it"));

        new ReportGenerator().generateReports(outputDir.toFile(), issues, "strict");

        String comment = Files.readString(outputDir.resolve("comment.md"));
        assertTrue(comment.contains("SpringSentinel Analysis Summary"));
        assertTrue(comment.contains("Total findings : 4"));
        assertTrue(comment.contains("🔴 Critical : 1"));
        assertTrue(comment.contains("🟠 High     : 2"));
        assertTrue(comment.contains("🟡 Warning  : 1"));
        assertTrue(comment.contains("Database    : 2"));
        assertTrue(comment.contains("REST Design : 1"));
        assertTrue(comment.contains("Security    : 1"));
        assertTrue(comment.contains("✔ report.html"));
        assertTrue(comment.contains("✔ report.json"));
        assertTrue(comment.contains("✔ report.sarif"));
        assertTrue(comment.contains("✔ comment.md"));
    }

    @Test
    void defaultReportRemainsItalian() throws Exception {
        new ReportGenerator().generateReports(outputDir.toFile(), List.of(), "strict");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertTrue(html.contains("<html lang='it'>"));
        assertTrue(html.contains("Profilo: strict"));
        assertTrue(html.contains("Nessun errore trovato"));
        assertTrue(html.contains("Il progetto rispetta tutte le regole del profilo <b>strict</b>."));
    }

    @Test
    void englishLanguageProducesTranslatedReport() throws Exception {
        List<StaticAnalysisCore.AuditIssue> issues = List.of(
                new StaticAnalysisCore.AuditIssue(
                        "src/main/java/example/FirstService.java", 18,
                        "Thread Safety", "Mutable state", "Avoid mutable fields."),
                new StaticAnalysisCore.AuditIssue(
                        "src/main/java/example/SecondService.java", 42,
                        "Thread Safety", "Mutable state", "Avoid mutable fields."),
                new StaticAnalysisCore.AuditIssue(
                        "src/main/java/example/Repository.java", 0,
                        "Database", "N+1", "Fetch it"));

        new ReportGenerator(ReportMessages.forLanguage("en", null))
                .generateReports(outputDir.toFile(), issues, "strict");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertTrue(html.contains("<html lang='en'>"));
        assertTrue(html.contains("Profile: strict"));
        assertTrue(html.contains("Analysis completed<br><b>2 distinct errors</b> detected in the project"));
        assertTrue(html.contains("Filter by priority"));
        assertTrue(html.contains("All priorities"));
        assertTrue(html.contains("2 occurrences"));
        assertTrue(html.contains("1 occurrence<"));
        assertTrue(html.contains("<span class='label'>Error found</span>Mutable state"));
        assertTrue(html.contains("<span class='label'>Possible solution</span>Avoid mutable fields."));
        assertTrue(html.contains("Affected classes and lines"));
        assertTrue(html.contains("<td class='line-number'>N/A</td>"));
        assertFalse(html.contains("occorrenz"));
        assertFalse(html.contains("Profilo"));
    }

    @Test
    void englishEmptyReportIsTranslated() throws Exception {
        new ReportGenerator(ReportMessages.forLanguage("en", null))
                .generateReports(outputDir.toFile(), List.of(), "strict");

        String html = Files.readString(outputDir.resolve("report.html"));
        assertTrue(html.contains("No errors found"));
        assertTrue(html.contains("The project complies with all rules of the <b>strict</b> profile."));
    }

    @Test
    void unsupportedLanguageFallsBackToItalianWithWarning() {
        List<String> logged = new java.util.ArrayList<>();

        ReportMessages messages = ReportMessages.forLanguage("fr", logged::add);

        assertEquals("it", messages.languageCode());
        assertEquals("Profilo: %s", messages.get("report.profile.badge"));
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("fr"));
    }

    @Test
    void blankOrNullLanguageDefaultsToItalianWithoutWarning() {
        List<String> logged = new java.util.ArrayList<>();

        assertEquals("it", ReportMessages.forLanguage(null, logged::add).languageCode());
        assertEquals("it", ReportMessages.forLanguage("  ", logged::add).languageCode());
        assertTrue(logged.isEmpty());
    }

    @Test
    void languageCodeIsCaseInsensitive() {
        assertEquals("en", ReportMessages.forLanguage("EN", null).languageCode());
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
