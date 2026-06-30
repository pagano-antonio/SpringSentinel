package com.beanspringboot;

import java.io.File;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        List<IssueGroup> groups = groupIssues(issues);

        try (BufferedWriter writer = Files.newBufferedWriter(
                reportDir.resolve("report.html"), StandardCharsets.UTF_8)) {
            writer.write("<!doctype html><html lang='it'><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>Spring Sentinel Report</title><style>" +
                ":root{--ink:#172333;--muted:#657386;--surface:#fff;--canvas:#f3f6f8;--brand:#21384f;--line:#dfe6ec;}" +
                "*{box-sizing:border-box}body{margin:0;font-family:Inter,'Segoe UI',Arial,sans-serif;background:var(--canvas);color:var(--ink);line-height:1.5;}" +
                ".page{width:min(1180px,calc(100% - 32px));margin:32px auto 56px}.header{background:linear-gradient(135deg,#20364c,#31546f);color:#fff;padding:30px 34px;border-radius:16px;box-shadow:0 12px 30px rgba(31,54,76,.18);}" +
                ".header-row,.toolbar,.card-heading,.summary{display:flex;align-items:center}.header-row{gap:18px;flex-wrap:wrap}.logo{font-size:32px}.header h1{font-size:30px;line-height:1.2;margin:0}.profile-badge,.tag,.count{border-radius:999px;font-weight:700}.profile-badge{background:#46a8e5;padding:5px 13px;font-size:12px;letter-spacing:.05em;text-transform:uppercase;}" +
                ".summary{justify-content:space-between;gap:20px;background:var(--surface);padding:20px 24px;border-radius:12px;margin:20px 0;box-shadow:0 4px 14px rgba(27,45,65,.07)}.summary strong{font-size:22px}.summary-copy{color:var(--muted)}.summary-copy b{color:var(--ink)}" +
                ".toolbar{gap:12px;flex-wrap:wrap;background:var(--surface);padding:16px 20px;border-radius:12px;margin-bottom:20px;border:1px solid var(--line)}label{font-weight:700}select{min-width:280px;padding:10px 36px 10px 12px;border:1px solid #b8c4cf;border-radius:8px;background:#fff;color:var(--ink);font:inherit;cursor:pointer;}" +
                ".card{background:var(--surface);margin-bottom:18px;border:1px solid var(--line);border-left:6px solid;border-radius:12px;box-shadow:0 3px 12px rgba(27,45,65,.06);overflow:hidden}.card.critical{border-left-color:#d64545}.card.high{border-left-color:#e8872d}.card.warning{border-left-color:#d3aa18}" +
                ".card-main{padding:22px 24px}.card-heading{justify-content:space-between;gap:16px}.tag{display:inline-block;background:#eaf0f5;color:#304a61;padding:4px 10px;font-size:11px;letter-spacing:.06em;text-transform:uppercase}.count{white-space:nowrap;background:#f0f3f6;color:#42566a;padding:4px 10px;font-size:12px}.card h2{margin:10px 0 14px;font-size:22px}.label{display:block;color:var(--muted);font-size:11px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;margin-bottom:4px}.solution{margin:0;padding:15px 17px;background:#eef7f2;border:1px solid #cee7d8;border-radius:9px;color:#244e37}" +
                ".locations{border-top:1px solid var(--line)}.locations-title{margin:0;padding:14px 24px;background:#f8fafb;font-size:14px}.locations table{width:100%;border-collapse:collapse}.locations th,.locations td{text-align:left;padding:12px 24px;border-top:1px solid #edf1f4}.locations th{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.06em}.class-name{font-weight:700}.path{display:block;color:var(--muted);font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;overflow-wrap:anywhere}.line-number{width:110px;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-weight:700}.empty{padding:30px;border-left-color:#2b9b61;text-align:center}" +
                "@media(max-width:650px){.page{width:min(100% - 20px,1180px);margin-top:10px}.header{padding:24px}.header h1{font-size:24px}.summary{align-items:flex-start;flex-direction:column}.toolbar{align-items:stretch;flex-direction:column}select{min-width:0;width:100%}.card-main{padding:18px}.card-heading{align-items:flex-start}.locations-title,.locations th,.locations td{padding-left:16px;padding-right:16px}.locations th:first-child{display:none}.locations td{display:block;border-top:0}.locations tr{display:block;border-top:1px solid #edf1f4}.line-number{width:auto;padding-top:0!important}.locations thead{display:none}}" +
                "</style></head><body><main class='page'>");

            writer.write("<header class='header'><div class='header-row'><span class='logo' aria-hidden='true'>🛡️</span>" +
                "<h1>Spring Sentinel Audit</h1><span class='profile-badge'>Profilo: " + escapeHtml(profile) + "</span></div></header>");

            writer.write("<section class='summary' aria-label='Riepilogo'><div class='summary-copy'>Analisi completata<br>" +
                "<b>" + groups.size() + " errori distinti</b> rilevati nel progetto</div>" +
                "<strong>" + issues.size() + " <span class='summary-copy'>occorrenze</span></strong></section>");

            if (!issues.isEmpty()) {
                writer.write("<section class='toolbar'><label for='priorityFilter'>Filtra per priorità</label>" +
                    "<select id='priorityFilter' onchange='filterIssues()'>" +
                    "<option value='all'>Tutte le priorità</option>" +
                    "<option value='critical'>Critica — Sicurezza e concorrenza</option>" +
                    "<option value='high'>Alta — Performance e architettura</option>" +
                    "<option value='warning'>Avviso — Design e best practice</option>" +
                    "</select></section>");
            }

            writer.write("<section id='issues-container' aria-live='polite'>");
            if (issues.isEmpty()) {
                writer.write("<article class='card empty'><h2>✅ Nessun errore trovato</h2><p>Il progetto rispetta tutte le regole del profilo <b>" + escapeHtml(profile) + "</b>.</p></article>");
            } else {
                for (IssueGroup group : groups) {
                    writeIssueGroup(writer, group);
                }
            }
            writer.write("</section>");

            writer.write("<script>function filterIssues(){var value=document.getElementById('priorityFilter').value;" +
                "document.querySelectorAll('.issue-card').forEach(function(card){card.hidden=value!=='all'&&card.dataset.priority!==value;});}</script>" +
                "</main></body></html>");
        }
    }

    private List<IssueGroup> groupIssues(List<StaticAnalysisCore.AuditIssue> issues) {
        Map<String, IssueGroup> groups = new LinkedHashMap<>();
        for (StaticAnalysisCore.AuditIssue issue : issues) {
            String key = issue.type + "\u0000" + issue.reason + "\u0000" + issue.suggestion;
            groups.computeIfAbsent(key, ignored -> new IssueGroup(issue)).occurrences.add(issue);
        }
        return new ArrayList<>(groups.values());
    }

    private void writeIssueGroup(BufferedWriter writer, IssueGroup group) throws IOException {
        StaticAnalysisCore.AuditIssue issue = group.example;
        String priority = mapToPriority(issue.type);
        writer.write("<article class='card issue-card " + priority + "' data-priority='" + priority + "'>" +
            "<div class='card-main'><div class='card-heading'><span class='tag'>" + escapeHtml(issue.type) + "</span>" +
            "<span class='count'>" + group.occurrences.size() + (group.occurrences.size() == 1 ? " occorrenza" : " occorrenze") + "</span></div>" +
            "<h2><span class='label'>Errore trovato</span>" + escapeHtml(issue.reason) + "</h2>" +
            "<p class='solution'><span class='label'>Possibile soluzione</span>" + escapeHtml(issue.suggestion) + "</p></div>" +
            "<div class='locations'><h3 class='locations-title'>Classi e righe interessate</h3>" +
            "<table><thead><tr><th>Classe / file</th><th>Riga</th></tr></thead><tbody>");

        for (StaticAnalysisCore.AuditIssue occurrence : group.occurrences) {
            writer.write("<tr><td><span class='class-name'>" + escapeHtml(displayName(occurrence.file)) + "</span>" +
                "<span class='path'>" + escapeHtml(occurrence.file) + "</span></td>" +
                "<td class='line-number'>" + (occurrence.line > 0 ? occurrence.line : "N/D") + "</td></tr>");
        }
        writer.write("</tbody></table></div></article>");
    }

    private String displayName(String file) {
        String normalized = file.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static class IssueGroup {
        private final StaticAnalysisCore.AuditIssue example;
        private final List<StaticAnalysisCore.AuditIssue> occurrences = new ArrayList<>();

        private IssueGroup(StaticAnalysisCore.AuditIssue example) {
            this.example = example;
        }
    }

    private void generateJsonReport(Path reportDir, List<StaticAnalysisCore.AuditIssue> issues) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(reportDir.resolve("report.json"), StandardCharsets.UTF_8)) {
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
        try (BufferedWriter writer = Files.newBufferedWriter(reportDir.resolve("report.sarif"), StandardCharsets.UTF_8)) {
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
