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
        // Inizializziamo il core con un log di sistema per vedere l'output in console
        analysisCore = new StaticAnalysisCore(new SystemStreamLog());
    }

    @Test
    void shouldDetectHardcodedSecrets() {
        String code = "class Test { String myApiKey = \"AIzaSyB-12345\"; }";
        executeTest(code);
        assertTrue(hasIssue("Hardcoded Secret"), "Dovrebbe rilevare segreti hardcoded");
    }

    @Test
    void shouldDetectFieldInjection() {
        String code = "class Test { @Autowired private MyService service; }";
        executeTest(code);
        assertTrue(hasIssue("Field Injection"), "Dovrebbe rilevare @Autowired sui campi");
    }

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
    void shouldDetectMissingRepositoryAnnotation() {
        String code = "interface UserRepository extends JpaRepository<User, Long> { }";
        executeTest(code);
        assertTrue(hasIssue("Missing @Repository"),
                "Dovrebbe segnalare la mancanza di @Repository nelle interfacce Repository");
    }

    @Test
    void shouldDetectMissingTransactionTimeout() {
        String code = "class Test { @Transactional void process() { } }";
        executeTest(code);
        assertTrue(hasIssue("Missing Transaction Timeout"), "Dovrebbe segnalare @Transactional senza timeout");
    }

    @Test
    void shouldDetectCacheMissingTTL() {
        String code = "class Test { @Cacheable(\"users\") public User get() { return null; } }";
        // Simuliamo properties vuote (senza TTL)
        analysisCore.runFileChecks(StaticJavaParser.parse(code), "Test.java", new Properties());
        assertTrue(hasIssue("Cache missing TTL"),
                "Dovrebbe segnalare @Cacheable senza configurazione TTL nelle properties");
    }

    @Test
    void shouldDetectOSIVEnabled() {
        Properties props = new Properties();
        props.setProperty("spring.jpa.open-in-view", "true");
        analysisCore.checkOSIV(props);
        assertTrue(hasIssue("OSIV is Enabled"), "Dovrebbe segnalare se OSIV è impostato su true");
    }

    @Test
    void shouldDetectCrossOriginWildcard() {
        String code = "import org.springframework.web.bind.annotation.CrossOrigin; " +
                "class Test { @CrossOrigin(\"*\") void m() {} }";
        executeTest(code);
        assertTrue(hasIssue("Insecure @CrossOrigin policy"), "Dovrebbe rilevare @CrossOrigin con wildcard");
    }

    @Test
    void shouldDetectMissingResponseEntityInController() {
        String code = "import org.springframework.web.bind.annotation.RestController; " +
                "import org.springframework.web.bind.annotation.GetMapping; " +
                "@RestController class TestController { @GetMapping(\"/test\") public String getTest() { return \"test\"; } }";
        executeTest(code);
        assertTrue(hasIssue("Missing ResponseEntity"), "Dovrebbe rilevare che manca ResponseEntity in un controller");
    }

    @Test
    void shouldDetectSecretInProperties() {
        Properties props = new Properties();
        props.setProperty("database.password", "mySuperSecretPassword123");
        analysisCore.checkPropertiesSecrets(props);
        assertTrue(hasIssue("Hardcoded Secret in properties"), "Dovrebbe rilevare segreti nel file properties");
    }

    // --- Metodi di utilità per pulire i test ---

    private void executeTest(String code) {
        CompilationUnit cu = StaticJavaParser.parse(code);
        analysisCore.runFileChecks(cu, "Test.java", new Properties());
    }

    private boolean hasIssue(String reasonFragment) {
        return analysisCore.getIssues().stream()
                .anyMatch(i -> i.reason.contains(reasonFragment));
    }
}