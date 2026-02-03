package com.beanspringboot;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class StaticAnalysisCoreTest {

    private List<StaticAnalysisCore.AuditIssue> issues;
    private AnalysisRules rules;
    private StaticAnalysisCore core;

    @BeforeEach
    void setUp() {
        issues = new ArrayList<>();
        // Inizializziamo le regole passando la lista locale come consumer
        rules = new AnalysisRules(issues::add);
        // Ci serve il core per i test sulle Properties (OSIV e Secrets)
        core = new StaticAnalysisCore(new SystemStreamLog());
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
        // Nota: Questo controllo è rimasto nel Core perché analizza l'oggetto Properties globale
        core.executeAnalysisWithPropsOnly(props, issues); 
        assertTrue(hasIssue("Hardcoded Secret"), "Dovrebbe rilevare segreti nel file properties");
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
        core.executeAnalysisWithPropsOnly(props, issues);
        assertTrue(hasIssue("OSIV is Enabled"), "Dovrebbe segnalare se OSIV è impostato su true");
    }

    // --- TEST ARCHITECTURE & GRAPH ---

    @Test
    void shouldDetectFatComponent() {
        String code = "@Service class FatService { " +
                "@Autowired S1 s1; @Autowired S2 s2; @Autowired S3 s3; " +
                "@Autowired S4 s4; @Autowired S5 s5; @Autowired S6 s6; " +
                "@Autowired S7 s7; @Autowired S8 s8; }";
        executeTest(code);
        assertTrue(hasIssue("Fat Component"), "Dovrebbe rilevare un componente con troppe dipendenze");
    }

    @Test
    void shouldDetectLazyInjectionSmell() {
        String code = "class Circular { @Lazy @Autowired private OtherService service; }";
        executeTest(code);
        assertTrue(hasIssue("Lazy Injection"), "Dovrebbe segnalare l'uso di @Lazy su campi Autowired");
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

    // --- UTILITY METHODS ---

    private void executeTest(String code) {
        CompilationUnit cu = StaticJavaParser.parse(code);
        rules.runAllChecks(cu, "Test.java", new Properties());
    }

    private boolean hasIssue(String reasonFragment) {
        return issues.stream()
                .anyMatch(i -> i.reason.contains(reasonFragment));
    }
    
    
    @Test
    void shouldDetectManualInstantiationOfSpringBeans() {
        // Simuliamo un Service istanziato manualmente con 'new' invece che tramite DI
        String code = "class MyController { void process() { var s = new UserService(); } }";
        executeTest(code);
        
        assertTrue(hasIssue("Manual Instantiation of Spring Bean"), 
            "Il plugin dovrebbe segnalare l'odore di codice quando un Service viene creato con 'new'");
    }
    
 // --- TEST OLISTICI (POM & PROJECT) ---

    @Test
    void shouldDetectMissingSpringBootPluginInPom() {
        // Simuliamo un progetto Maven senza plugin
        org.apache.maven.project.MavenProject mockProject = new org.apache.maven.project.MavenProject();
        
        rules.runProjectChecks(mockProject);
        
        assertTrue(hasIssue("Missing Spring Boot Plugin"), 
            "Dovrebbe segnalare la mancanza del plugin spring-boot-maven-plugin nel POM");
    }

    @Test
    void shouldDetectOldSpringBootVersionInPom() {
        org.apache.maven.project.MavenProject mockProject = new org.apache.maven.project.MavenProject();
        
        // Aggiungiamo una dipendenza obsoleta (Spring Boot 2.x)
        Dependency oldBoot = new Dependency();
        oldBoot.setArtifactId("spring-boot-starter");
        oldBoot.setVersion("2.7.0");
        mockProject.getDependencies().add(oldBoot);
        
        rules.runProjectChecks(mockProject);
        
        assertTrue(hasIssue("Old Spring Boot Version"), 
            "Dovrebbe segnalare se la versione di Spring Boot è inferiore alla 3.x");
    }

    @Test
    void shouldDetectExposedDataRestRepositories() {
        org.apache.maven.project.MavenProject mockProject = new org.apache.maven.project.MavenProject();
        
        Dependency dataRest = new Dependency();
        dataRest.setArtifactId("spring-boot-starter-data-rest");
        mockProject.getDependencies().add(dataRest);
        
        rules.runProjectChecks(mockProject);
        
        assertTrue(hasIssue("Exposed Repositories (Data REST)"), 
            "Dovrebbe avvisare sui rischi di esposizione automatica di Spring Data REST");
    }

  
}