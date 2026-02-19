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
 * v1.2.0: Added Profile visualization, interactive UI filters, and SARIF support.
 */
public class ReportGenerator {

    public void generateReports(File outputDir, List<StaticAnalysisCore.AuditIssue> issues, String profile) throws IOException {
        // Assicuriamoci che la directory esista
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        // Sorting: Priority based (Critical first)
        List<StaticAnalysisCore.AuditIssue> sortedIssues = issues.stream()
                .sorted(Comparator.comparing(this::getPriorityWeight))
                .collect(Collectors.toList());

        generateJsonReport(outputDir.toPath(), sortedIssues);
        generateHtmlReport(outputDir.toPath(), sortedIssues, profile);
        generateSarifReport(outputDir.toPath(), sortedIssues);
    }

    private int getPriorityWeight(StaticAnalysisCore.AuditIssue issue) {
        String p = mapToPriority(issue.type);
        if (p.equals("critical")) return 1;
        if (p.equals("high")) return 2;
        return 3;
    }

    private String mapToPriority(String type) {
        String t = type.toLowerCase();
        if (t.contains("security") || t.contains("thread safety") || t.contains("concurrency")) {
            return "critical";
        }
        if (t.contains("performance") || t.contains("architecture") || t.contains("database")) {
            return "high";
        }
        return "warning";
    }

    private void generateHtmlReport(Path reportDir, List<StaticAnalysisCore.AuditIssue> issues, String profile) throws IOException {
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.html").toFile())) {
            writer.write("<html><head><title>Spring Sentinel Report</title><style>" +
                "body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:#f4f7f6;padding:30px;color:#333;}" +
                ".header{background:#2c3e50;color:white;padding:25px;border-radius:8px;margin-bottom:20px;box-shadow:0 4px 6px rgba(0,0,0,0.1);}" +
                ".profile-badge{background:#3498db;color:white;padding:5px 15px;border-radius:20px;font-size:14px;font-weight:bold;margin-left:15px;vertical-align:middle;text-transform:uppercase;}" +
                ".summary{background:#fff;padding:20px;border-radius:8px;box-shadow:0 2px 4px rgba(0,0,0,0.1);margin-bottom:20px;display:flex;justify-content:space-between;align-items:center;}" +
                ".filter-box{margin-bottom:20px; background:#fff; padding:15px; border-radius:8px; display:flex; align-items:center; gap:10px; box-shadow:0 2px 4px rgba(0,0,0,0.05);}" +
                "select{padding:8px; border-radius:4px; border:1px solid #ccc; cursor:pointer;}" +
                ".card{background:#fff;padding:15px;margin-bottom:15px;border-radius:8px;box-shadow:0 2px 5px rgba(0,0,0,0.05); border-left: 6px solid; transition: 0.3s;}" +
                ".card:hover{transform: translateY(-2px); box-shadow: 0 4px 8px rgba(0,0,0,0.1);}" +
                ".tag{background:#34495e;color:#fff;padding:3px 8px;border-radius:4px;font-size:12px;font-weight:bold;text-transform:uppercase;}" +
                ".critical{border-left-color: #c0392b;}" + 
                ".high{border-left-color: #e67e22;}" +     
                ".warning{border-left-color: #f1c40f;}" +  
                "h1{margin:0;} .badge-count{background:#e74c3c; color:white; padding:4px 12px; border-radius:15px; font-weight:bold;}" +
                "</style></head><body>");
            
            // Header con Profilo Attivo
            writer.write("<div class='header'>");
            writer.write("<h1>🛡️ Spring Sentinel Audit <span class='profile-badge'>Profile: " + profile + "</span></h1>");
            writer.write("</div>");
            
            // Summary Info
            writer.write("<div class='summary'>");
            writer.write("<span>Analysis completed. Total issues found:</span>");
            writer.write("<span class='badge-count'>" + issues.size() + "</span>");
            writer.write("</div>");

            // UI Filters
            writer.write("<div class='filter-box'>" +
                "<strong>Filter by Priority:</strong>" +
                "<select id='priorityFilter' onchange='filterIssues()'>" +
                "<option value='all'>Show All Rules</option>" +
                "<option value='critical'>🔴 Critical (Security/Concurrency)</option>" +
                "<option value='high'>🟠 High (Performance/Architecture)</option>" +
                "<option value='warning'>🟡 Warning (Best Practice/Design)</option>" +
                "</select></div>");

            // Issues Container
            writer.write("<div id='issues-container'>");
            if (issues.isEmpty()) {
                writer.write("<div class='card' style='border-left-color: #27ae60;'><h2>✅ No issues found!</h2><p>Your project matches all the rules defined in the <b>" + profile + "</b> profile.</p></div>");
            } else {
                for (StaticAnalysisCore.AuditIssue i : issues) {
                    String priority = mapToPriority(i.type);
                    writer.write(String.format(
                        "<div class='card %s' data-priority='%s'>" +
                        "<span class='tag'>%s</span>" +
                        "<h3>%s</h3>" +
                        "<p>Location: <b>%s</b> (Line: %d)</p>" +
                        "<p style='background:#f9f9f9; padding:10px; border-radius:4px;'><b>Fix:</b> %s</p>" +
                        "</div>",
                        priority, priority, i.type, i.reason, i.file, i.line, i.suggestion));
                }
            }
            writer.write("</div>");

            // Filter Script
            writer.write("<script>" +
                "function filterIssues() {" +
                "  var val = document.getElementById('priorityFilter').value;" +
                "  var cards = document.querySelectorAll('.card');" +
                "  cards.forEach(c => {" +
                "    if(val === 'all' || c.getAttribute('data-priority') === val) {" +
                "      c.style.display = 'block';" +
                "    } else {" +
                "      c.style.display = 'none';" +
                "    }" +
                "  });" +
                "}" +
                "</script>");
            
            writer.write("</body></html>");
        }
    }

    private void generateJsonReport(Path reportDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        try (FileWriter writer = new FileWriter(reportDir.resolve("report.json").toFile())) {
            writer.write("{\n  \"totalIssues\": " + issues.size() + ",\n  \"issues\": [\n");
            for (int i = 0; i < issues.size(); i++) {
                StaticAnalysisCore.AuditIssue issue = issues.get(i);
                writer.write(String.format("    {\"file\":\"%s\",\"line\":%d,\"type\":\"%s\",\"priority\":\"%s\",\"reason\":\"%s\",\"suggestion\":\"%s\"}%s\n",
                        issue.file, issue.line, issue.type, mapToPriority(issue.type), issue.reason, issue.suggestion, (i < issues.size() - 1 ? "," : "")));
            }
            writer.write("  ]\n}");
        }
    }

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
                "          \"version\": \"1.2.0\",\n" +
                "          \"informationUri\": \"https://github.com/pagano-antonio/SpringSentinel\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"results\": [\n");

            for (int i = 0; i < issues.size(); i++) {
                StaticAnalysisCore.AuditIssue issue = issues.get(i);
                String priority = mapToPriority(issue.type);
                String level = priority.equals("critical") || priority.equals("high") ? "error" : "warning";
                
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

            writer.write("      ]\n" + "    }\n" + "  ]\n" + "}");
        }
    }
}