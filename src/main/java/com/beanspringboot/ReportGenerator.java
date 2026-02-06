package com.beanspringboot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates reports in HTML, JSON, and SARIF formats.
 * SARIF support added in v1.1.9 for better CI/CD integration.
 */
public class ReportGenerator {

    public void generateReports(File outputDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        Path reportDir = outputDir.toPath().resolve("spring-sentinel-reports");
        Files.createDirectories(reportDir);
        
        // Sorting: Errors (Red) first, then Warnings (Orange)
        List<StaticAnalysisCore.AuditIssue> sortedIssues = issues.stream()
                .sorted(Comparator.comparing(i -> i.type.toLowerCase().contains("warning")))
                .collect(Collectors.toList());

        generateJsonReport(reportDir, sortedIssues);
        generateHtmlReport(reportDir, sortedIssues);
        generateSarifReport(reportDir, sortedIssues); // New standardized format
    }

    private void generateJsonReport(Path reportDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.json").toFile())) {
            writer.write("{\n  \"totalIssues\": " + issues.size() + ",\n  \"issues\": [\n");
            for (int i = 0; i < issues.size(); i++) {
                StaticAnalysisCore.AuditIssue issue = issues.get(i);
                writer.write(String.format("    {\"file\":\"%s\",\"line\":%d,\"type\":\"%s\",\"reason\":\"%s\",\"suggestion\":\"%s\"}%s\n",
                        issue.file, issue.line, issue.type, issue.reason, issue.suggestion, (i < issues.size() - 1 ? "," : "")));
            }
            writer.write("  ]\n}");
        }
    }

    private void generateHtmlReport(Path reportDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.html").toFile())) {
            writer.write("<html><head><style>" +
                "body{font-family:sans-serif;background:#f4f7f6;padding:20px;}" +
                ".card{background:#fff;padding:15px;margin-bottom:10px;box-shadow:0 1px 3px rgba(0,0,0,0.1); border-left: 5px solid;}" +
                ".tag{background:#34495e;color:#fff;padding:2px 5px;border-radius:3px;font-size:11px;}" +
                ".error-border{border-left-color: #e74c3c;}" + // Red for critical issues
                ".warning-border{border-left-color: #f39c12;}" + // Orange for design warnings
                "h1{color:#2c3e50;} .summary{margin-bottom:20px; padding:10px; background:#fff; border-radius:5px;}" +
                "</style></head><body>");
            
            writer.write("<h1>🛡️ Spring Sentinel Report</h1>");
            writer.write("<div class='summary'>Total issues found: <b>" + issues.size() + "</b></div>");
            
            for (StaticAnalysisCore.AuditIssue i : issues) {
                boolean isWarning = i.type.toLowerCase().contains("warning");
                String severityClass = isWarning ? "warning-border" : "error-border";
                
                writer.write(String.format("<div class='card %s'><span class='tag'>%s</span><h3>%s</h3><p>Location: <b>%s</b> (Line: %d)</p><p><b>Fix:</b> %s</p></div>",
                        severityClass, i.type, i.reason, i.file, i.line, i.suggestion));
            }
            writer.write("</body></html>");
        }
    }

    /**
     * Generates a report in SARIF format (v2.1.0).
     * This allows native integration with GitHub Code Scanning and Jenkins plugins.
     */
    private void generateSarifReport(Path reportDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.sarif").toFile())) {
            writer.write("{\n" +
                "  \"$schema\": \"https://schemastore.azurewebsites.net/schemas/json/sarif-2.1.0-rtm.5.json\",\n" +
                "  \"version\": \"2.1.0\",\n" +
                "  \"runs\": [\n" +
                "    {\n" +
                "      \"tool\": {\n" +
                "        \"driver\": {\n" +
                "          \"name\": \"SpringSentinel\",\n" +
                "          \"version\": \"1.1.9\",\n" +
                "          \"informationUri\": \"https://springsentinel.com\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"results\": [\n");

            for (int i = 0; i < issues.size(); i++) {
                StaticAnalysisCore.AuditIssue issue = issues.get(i);
                String level = issue.type.toLowerCase().contains("warning") ? "warning" : "error";
                
                // Ensure proper URI formatting and escaping for JSON
                String escapedReason = issue.reason.replace("\"", "\\\"");
                String escapedSuggestion = issue.suggestion.replace("\"", "\\\"");

                writer.write("        {\n" +
                    "          \"ruleId\": \"" + issue.type.replace(" ", "-") + "\",\n" +
                    "          \"level\": \"" + level + "\",\n" +
                    "          \"message\": { \"text\": \"" + escapedReason + ": " + escapedSuggestion + "\" },\n" +
                    "          \"locations\": [\n" +
                    "            {\n" +
                    "              \"physicalLocation\": {\n" +
                    "                \"artifactLocation\": { \"uri\": \"" + issue.file + "\" },\n" +
                    "                \"region\": { \"startLine\": " + Math.max(1, issue.line) + " }\n" +
                    "              }\n" +
                    "            }\n" +
                    "          ]\n" +
                    "        }" + (i < issues.size() - 1 ? "," : "") + "\n");
            }

            writer.write("      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        }
    }
}