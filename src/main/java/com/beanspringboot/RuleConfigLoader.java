package com.beanspringboot;

import org.apache.maven.plugin.logging.Log;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.*;

public class RuleConfigLoader {
    private final Log log;

    public RuleConfigLoader(Log log) { this.log = log; }

    public ResolvedConfig loadActiveRules(File customFile, String profileId) throws Exception {
        Document doc;
        if (customFile != null && customFile.exists()) {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(customFile);
        } else {
            InputStream is = getClass().getResourceAsStream("/default-rules.xml");
            if (is == null) throw new RuntimeException("default-rules.xml non trovato!");
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
        }

        // 1. Carica tutti i parametri di default definiti nelle Rules
        Map<String, Map<String, String>> parameters = loadDefaultRuleParameters(doc);

        // 2. Risolve i profili (regole attive + override)
        Set<String> activeRules = new HashSet<>();
        applyProfile(doc, profileId, activeRules, parameters);

        return new ResolvedConfig(activeRules, parameters);
    }

    private Map<String, Map<String, String>> loadDefaultRuleParameters(Document doc) {
        Map<String, Map<String, String>> allParams = new HashMap<>();
        NodeList rules = doc.getElementsByTagName("rule");

        for (int i = 0; i < rules.getLength(); i++) {
            Element rule = (Element) rules.item(i);
            String id = rule.getAttribute("id");
            Map<String, String> params = new HashMap<>();

            NodeList paramNodes = rule.getElementsByTagName("parameter");
            for (int j = 0; j < paramNodes.getLength(); j++) {
                Element p = (Element) paramNodes.item(j);
                params.put(p.getAttribute("name"), p.getAttribute("value"));
            }
            allParams.put(id, params);
        }
        return allParams;
    }

    private void applyProfile(Document doc, String profileId, Set<String> activeRules, Map<String, Map<String, String>> parameters) {
        Element profile = findProfileById(doc, profileId);
        if (profile == null) return;

        // Gestione ereditarietà (prima carichiamo il genitore)
        String parent = profile.getAttribute("extends");
        if (!parent.isEmpty()) {
            applyProfile(doc, parent, activeRules, parameters);
        }

        // Aggiunta regole (include)
        NodeList includes = profile.getElementsByTagName("include");
        for (int i = 0; i < includes.getLength(); i++) {
            activeRules.add(((Element) includes.item(i)).getAttribute("rule"));
        }

        // Applicazione override (sovrascrivono i default o i genitori)
        NodeList overrides = profile.getElementsByTagName("override");
        for (int i = 0; i < overrides.getLength(); i++) {
            Element o = (Element) overrides.item(i);
            String ruleId = o.getAttribute("rule");
            String paramName = o.getAttribute("param");
            String value = o.getAttribute("value");

            parameters.computeIfAbsent(ruleId, k -> new HashMap<>()).put(paramName, value);
        }
    }

    private Element findProfileById(Document doc, String id) {
        NodeList profiles = doc.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element p = (Element) profiles.item(i);
            if (p.getAttribute("id").equals(id)) return p;
        }
        return null;
    }
}