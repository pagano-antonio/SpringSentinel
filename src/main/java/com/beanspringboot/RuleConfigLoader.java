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
        InputStream is = getClass().getResourceAsStream("/default-rules.xml");
        if (is == null) throw new RuntimeException("default-rules.xml non trovato nel classpath!");
        Document defaultDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);

        Document customDoc = null;
        if (customFile != null && customFile.exists()) {
            log.info("Caricamento profili personalizzati da: " + customFile.getName());
            customDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(customFile);
        }

        Map<String, Map<String, String>> parameters = loadDefaultRuleParameters(defaultDoc);
        Set<String> activeRules = new HashSet<>();
        
        log.info("[DEBUG-SENTINEL] Inizio risoluzione del profilo: " + profileId);
        applyProfile(defaultDoc, customDoc, profileId, activeRules, parameters);

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

    private void applyProfile(Document defaultDoc, Document customDoc, String profileId, Set<String> activeRules, Map<String, Map<String, String>> parameters) {
        Element profile = findProfileById(defaultDoc, customDoc, profileId);
        
        if (profile == null) {
            log.warn("⚠️ Profilo '" + profileId + "' non trovato in nessun file!");
            return;
        }

        String parent = profile.getAttribute("extends");
        if (parent != null && !parent.isEmpty()) {
            log.info("[DEBUG-SENTINEL] Il profilo " + profileId + " estende " + parent);
            applyProfile(defaultDoc, customDoc, parent, activeRules, parameters);
        }

        NodeList includes = profile.getElementsByTagName("include");
        for (int i = 0; i < includes.getLength(); i++) {
            activeRules.add(((Element) includes.item(i)).getAttribute("rule"));
        }

        NodeList excludes = profile.getElementsByTagName("exclude");
        for (int i = 0; i < excludes.getLength(); i++) {
            activeRules.remove(((Element) excludes.item(i)).getAttribute("rule"));
        }

        NodeList overrides = profile.getElementsByTagName("override");
        for (int i = 0; i < overrides.getLength(); i++) {
            Element o = (Element) overrides.item(i);
            String ruleId = o.getAttribute("rule");
            String paramName = o.getAttribute("param");
            String value = o.getAttribute("value");

            log.info("[DEBUG-SENTINEL] Caricato override in " + profileId + " -> Rule: " + ruleId + " | Param: " + paramName + " = " + value);
            parameters.computeIfAbsent(ruleId, k -> new HashMap<>()).put(paramName, value);
        }
    }

    private Element findProfileById(Document defaultDoc, Document customDoc, String id) {
        if (customDoc != null) {
            NodeList customProfiles = customDoc.getElementsByTagName("profile");
            for (int i = 0; i < customProfiles.getLength(); i++) {
                Element p = (Element) customProfiles.item(i);
                if (p.getAttribute("id").equals(id)) return p;
            }
        }

        NodeList defaultProfiles = defaultDoc.getElementsByTagName("profile");
        for (int i = 0; i < defaultProfiles.getLength(); i++) {
            Element p = (Element) defaultProfiles.item(i);
            if (p.getAttribute("id").equals(id)) return p;
        }

        return null; 
    }
}