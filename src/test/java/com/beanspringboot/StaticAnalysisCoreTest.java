package com.beanspringboot;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class StaticAnalysisCoreTest {

    private StaticAnalysisCore analysisCore;

    @BeforeEach
    void setUp() {
        analysisCore = new StaticAnalysisCore(new SystemStreamLog());
    }

    // --- TEST SECURITY & SECRETS ---

    @Test
    void shouldDetectHardcodedSecrets() {
        String code = "class Test { String myApiKey = \"AIzaSyB-12345\"; }";
        executeTest(code);
        assertTrue(hasIssue("Hardcoded Secret"), "Dovrebbe rilevare segreti hardcoded");
    }

    @Test
    void shouldDetectSecretInProperties() {
        Properties props = new Properties();
        props.setProperty("database.password", "mySuperSecretPassword123");
        analysisCore.checkPropertiesSecrets(props);
        assertTrue(hasIssue("Hardcoded Secret in properties"), "Dovrebbe rilevare segreti nel file properties");
    }

    @Test
    void shouldDetectCrossOriginWildcard() {
        String code = "@CrossOrigin(\"*\") class Test { }";
        executeTest(code);
        assertTrue(hasIssue("Insecure @CrossOrigin policy"), "Dovrebbe rilevare wildcard in CrossOrigin");
    }

    // --- TEST PERFORMANCE & JPA ---

    @Test
    void shouldDetectNPlusOne() {
        String code = "class Test { void m() { for(var x : list) { repo.getDetails(); } } }";
        executeTest(code);
        assertTrue(hasIssue("Potential N+1 Query"), "Dovrebbe rilevare chiamate a getter in un loop");
    }

    @Test
    void shouldDetectJPAEager() {
        String code = "class Test { @ManyToOne(fetch = FetchType.EAGER) private User user; }";
        executeTest(code);
        assertTrue(hasIssue("EAGER Fetching detected"), "Dovrebbe rilevare il caricamento EAGER");
    }

    @Test
    void shouldDetectOSIVEnabled() {
        Properties props = new Properties();
        props.setProperty("spring.jpa.open-in-view", "true");
        analysisCore.checkOSIV(props);
        assertTrue(hasIssue("OSIV is Enabled"), "Dovrebbe segnalare se OSIV è impostato su true");
    }

    // --- TEST ARCHITECTURE & GRAPH ---

    @Test
    void shouldDetectFatComponent() {
        // Simuliamo un componente con 8 dipendenze iniettate tramite campi
        String code = "@Service class FatService { " +
                "@Autowired S1 s1; @Autowired S2 s2; @Autowired S3 s3; " +
                "@Autowired S4 s4; @Autowired S5 s5; @Autowired S6 s6; " +
                "@Autowired S7 s7; @Autowired S8 s8; }";
        executeTest(code);
        assertTrue(hasIssue("Fat Component Detected"), "Dovrebbe rilevare un componente con troppe dipendenze");
    }

    @Test
    void shouldDetectLazyInjectionSmell() {
        String code = "class Circular { @Lazy @Autowired private OtherService service; }";
        executeTest(code);
        assertTrue(hasIssue("Lazy Injection Detected"), "Dovrebbe segnalare l'odore di dipendenza circolare tramite @Lazy");
    }

    @Test
    void shouldDetectMutableStateInSingleton() {
        String code = "@Service class MyService { private int counter = 0; }";
        executeTest(code);
        assertTrue(hasIssue("Mutable state in Singleton"), "Dovrebbe segnalare variabili mutabili nei Singleton");
    }

    @Test
    void shouldNotFlagFinalFieldsInSingleton() {
        String code = "@Service class MyService { private final String id = \"123\"; }";
        executeTest(code);
        assertFalse(hasIssue("Mutable state in Singleton"), "Non dovrebbe segnalare campi finali");
    }

    @Test
    void shouldDetectUnsafePrototypeScope() {
        String code = "@Component @Scope(\"prototype\") class MyBean { }";
        executeTest(code);
        assertTrue(hasIssue("Unsafe Scoped Bean Injection"), "Dovrebbe segnalare Prototype scope senza proxyMode");
    }

    @Test
    void shouldAcceptScopedBeanWithProxyMode() {
        String code = "@Component @Scope(value = \"request\", proxyMode = ScopedProxyMode.TARGET_CLASS) class Req { }";
        executeTest(code);
        assertFalse(hasIssue("Unsafe Scoped Bean Injection"), "Non dovrebbe segnalare se il proxyMode è presente");
    }

    @Test
    void shouldDetectFieldInjection() {
        String code = "class Test { @Autowired private MyService service; }";
        executeTest(code);
        assertTrue(hasIssue("Field Injection"), "Dovrebbe rilevare @Autowired sui campi");
    }

    // --- TEST CONCURRENCY & RESILIENCE ---

    @Test
    void shouldDetectBlockingCallInTransactional() {
        String code = "class Test { @Transactional void save() { Thread.sleep(1000); } }";
        executeTest(code);
        assertTrue(hasIssue("Blocking call in Transaction"), "Dovrebbe rilevare Thread.sleep dentro @Transactional");
    }

    @Test
    void shouldDetectManualThreadCreation() {
        String code = "class Test { void run() { Thread t = new Thread(); } }";
        executeTest(code);
        assertTrue(hasIssue("Manual Thread creation"), "Dovrebbe rilevare la creazione manuale di Thread");
    }

    @Test
    void shouldDetectMissingTransactionTimeout() {
        String code = "class Test { @Transactional void process() { } }";
        executeTest(code);
        assertTrue(hasIssue("Missing Transaction Timeout"), "Dovrebbe segnalare @Transactional senza timeout");
    }

    // --- TEST BEST PRACTICES ---

    @Test
    void shouldDetectMissingRepositoryAnnotation() {
        String code = "interface UserRepository extends JpaRepository<User, Long> { }";
        executeTest(code);
        assertTrue(hasIssue("Missing @Repository"), "Dovrebbe segnalare la mancanza di @Repository");
    }

    @Test
    void shouldDetectMissingResponseEntityInController() {
        String code = "@RestController class Test { @GetMapping(\"/x\") public String m() { return \"\"; } }";
        executeTest(code);
        assertTrue(hasIssue("Missing ResponseEntity"), "Dovrebbe suggerire l'uso di ResponseEntity");
    }

    @Test
    void shouldDetectCacheMissingTTL() {
        String code = "class Test { @Cacheable(\"users\") public User get() { return null; } }";
        analysisCore.runFileChecks(StaticJavaParser.parse(code), "Test.java", new Properties());
        assertTrue(hasIssue("Cache missing TTL"), "Dovrebbe segnalare @Cacheable senza configurazione TTL");
    }

    // --- UTILITY METHODS ---

    private void executeTest(String code) {
        CompilationUnit cu = StaticJavaParser.parse(code);
        analysisCore.runFileChecks(cu, "Test.java", new Properties());
    }

    private boolean hasIssue(String reasonFragment) {
        return analysisCore.getIssues().stream()
                .anyMatch(i -> i.reason.contains(reasonFragment));
    }
}