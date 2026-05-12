package com.beanspringboot;

import java.util.*;

/**
 * Contenitore per la configurazione finale risolta.
 * Gestisce l'unione tra regole attive, parametri XML e override dal POM.
 * v1.3.0
 */
public class ResolvedConfig {
    private final Set<String> activeRules;
    private final Map<String, Map<String, String>> ruleParameters;

    public ResolvedConfig(Set<String> activeRules, Map<String, Map<String, String>> ruleParameters) {
        this.activeRules = activeRules;
        this.ruleParameters = ruleParameters;
    }

    /**
     * Restituisce l'insieme degli ID delle regole attive per il profilo selezionato.
     */
    public Set<String> getActiveRules() { 
        return activeRules; 
    }

    /**
     * Permette allo StaticAnalysisCore di sovrascrivere un parametro a runtime.
     * Utilizzato per applicare le configurazioni dirette del Maven Mojo (pom.xml).
     */
    public void overrideParameter(String ruleId, String paramName, String value) {
        ruleParameters.computeIfAbsent(ruleId, k -> new HashMap<>()).put(paramName, value);
    }

    /**
     * Recupera il valore di un parametro per una specifica regola.
     * @param ruleId ID della regola (es. ARCH-003)
     * @param paramName Nome del parametro (es. maxDependencies)
     * @param defaultValue Valore di fallback se il parametro non è definito
     * @return Il valore risolto (Mojo > XML Profile > Default XML)
     */
    public String getParameter(String ruleId, String paramName, String defaultValue) {
        return ruleParameters.getOrDefault(ruleId, Collections.emptyMap())
                             .getOrDefault(paramName, defaultValue);
    }
}