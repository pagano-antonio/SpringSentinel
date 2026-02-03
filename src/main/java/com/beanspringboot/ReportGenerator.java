package com.beanspringboot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReportGenerator {

    public void generateReports(File outputDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        Path reportDir = outputDir.toPath().resolve("spring-sentinel-reports");
        Files.createDirectories(reportDir);
        
        generateJsonReport(reportDir, issues);
        generateHtmlReport(reportDir, issues);
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
            writer.write("<html><head><style>body{font-family:sans-serif;background:#f4f7f6;padding:20px;}.card{background:#fff;padding:15px;margin-bottom:10px;border-left:5px solid #e74c3c;box-shadow:0 1px 3px rgba(0,0,0,0.1);}.tag{background:#34495e;color:#fff;padding:2px 5px;border-radius:3px;font-size:11px;}</style></head><body>");
            writer.write("<h1>🛡️ Spring Sentinel Report</h1><p>Issues: <b>" + issues.size() + "</b></p>");
            for (StaticAnalysisCore.AuditIssue i : issues) {
                writer.write(String.format("<div class='card'><span class='tag'>%s</span><h3>%s</h3><p>Location: %s (L:%d)</p><p>Fix: %s</p></div>",
                        i.type, i.reason, i.file, i.line, i.suggestion));
            }
            writer.write("</body></html>");
        }
    }
}