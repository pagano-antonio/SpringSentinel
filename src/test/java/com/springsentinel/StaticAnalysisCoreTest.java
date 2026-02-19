package com.springsentinel;

import com.beanspringboot.AnalysisRules;
import com.beanspringboot.ResolvedConfig;
import com.beanspringboot.StaticAnalysisCore;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StaticAnalysisCoreTest {

    private List<StaticAnalysisCore.AuditIssue> issues;
    private AnalysisRules rules;
    private StaticAnalysisCore core;
    private ResolvedConfig testConfig;

    // Set di tutte le regole per i test di regressione
    private final Set<String> ALL_RULES = Set.of(
        "SEC-001", "SEC-002", "SEC-003", "SEC-H2",
        "PERF-001", "PERF-002", "PERF-003", "PERF-004",
        "ARCH-001", "ARCH-002", "ARCH-003", "ARCH-004", "ARCH-005", "ARCH-OSIV",
        "RES-001", "RES-002",
        "MAINT-001", "MAINT-002", "MAINT-003",
        "REST-001", "REST-002", "REST-003", "REST-004"
    );

    @BeforeEach
    void setUp() {
        issues = new ArrayList<>();
        // Inizializziamo una configurazione di test con tutte le regole attive
        testConfig = new ResolvedConfig(new HashSet<>(ALL_RULES), new HashMap<>());
        
        // AnalysisRules ora accetta ResolvedConfig
        rules = new AnalysisRules(issues::add, testConfig);
        core = new StaticAnalysisCore(new SystemStreamLog(), null);
    }

    // --- REST DESIGN TESTS ---

    @Test
    void shouldDetectNonKebabCaseUrl() {
        executeTest("@RestController class Test { @GetMapping(\"/userProfile\") public void get() {} }");
        assertTrue(hasIssue("Non-standard URL naming"), "Should detect camelCase in URL");
    }

    @Test
    void shouldDetectMissingApiVersioning() {
        executeTest("@RestController class Test { @GetMapping(\"/users\") public void get() {} }");
        assertTrue(hasIssue("Missing API Versioning"), "Should suggest /v1 prefix");
    }

    @Test
    void shouldDetectSingularResourceName() {
        executeTest("@RestController class Test { @GetMapping(\"/v1/user\") public void get() {} }");
        assertTrue(hasIssue("Singular Resource Name"), "Should suggest plural /users");
    }

    // --- SECURITY & SECRETS TESTS ---

    @Test
    void shouldDetectHardcodedSecrets() {
        executeTest("class Test { String myApiKey = \"AIzaSyB-12345\"; }");
        assertTrue(hasIssue("Potential Hardcoded Secret"));
    }

    @Test
    void shouldDetectSecretsWithCustomRegex() {
        // Simuliamo l'override del parametro pattern tramite la configurazione
        testConfig.overrideParameter("SEC-001", "pattern", ".*token.*");
        
        CompilationUnit cu = StaticJavaParser.parse("class Vault { String my_access_token = \"12345\"; }");
        rules.runAllChecks(cu, "Test.java", new Properties());
        
        assertTrue(hasIssue("Potential Hardcoded Secret"));
    }

    @Test
    void shouldDetectSecretInProperties() {
        Properties props = new Properties();
        props.setProperty("database.password", "secret123");
        
        // AnalysisWithPropsOnly ora accetta ResolvedConfig
        core.executeAnalysisWithPropsOnly(props, issues, testConfig); 
        assertTrue(hasIssue("Hardcoded Secret"));
    }

    // --- ARCHITECTURE & DESIGN TESTS ---

    @Test
    void shouldDetectFatComponent() {
        executeTest("@Service class FatService { @Autowired S1 s1; @Autowired S2 s2; @Autowired S3 s3; @Autowired S4 s4; @Autowired S5 s5; @Autowired S6 s6; @Autowired S7 s7; @Autowired S8 s8; }");
        assertTrue(hasIssue("Fat Component"));
    }

    @Test
    void shouldBeTolerantWithCustomMaxDependencies() {
        // Simuliamo l'override: alziamo il limite a 10
        testConfig.overrideParameter("ARCH-003", "maxDependencies", "10");
        
        CompilationUnit cu = StaticJavaParser.parse("@Service class BigService { @Autowired S1 s1; @Autowired S2 s2; @Autowired S3 s3; @Autowired S4 s4; @Autowired S5 s5; @Autowired S6 s6; @Autowired S7 s7; @Autowired S8 s8; }");
        rules.runAllChecks(cu, "Test.java", new Properties());
        
        assertFalse(hasIssue("Fat Component"), "Should not detect fat component with limit 10");
    }

    @Test
    void shouldDetectManualInstantiationOfSpringBeans() {
        executeTest("class MyController { void process() { var s = new UserService(); } }");
        assertTrue(hasIssue("Manual Instantiation of Spring Bean"));
    }

    @Test
    void shouldDetectLazyInjectionSmell() {
        executeTest("class Circular { @Lazy @Autowired private OtherService service; }");
        assertTrue(hasIssue("Lazy Injection"));
    }

    @Test
    void shouldDetectAutowiredFieldInjection() {
        executeTest("class Test { @Autowired private MyService s; }");
        assertTrue(hasIssue("Field Injection"));
    }

    // --- HOLISTIC: POM & PROJECT TESTS ---

    @Test
    void shouldDetectMissingSpringBootPluginInPom() {
        rules.runProjectChecks(new MavenProject());
        assertTrue(hasIssue("Missing Spring Boot Plugin"));
    }

    @Test
    void shouldDetectOldSpringBootVersionInPom() {
        MavenProject mockProject = new MavenProject();
        Dependency oldBoot = new Dependency();
        oldBoot.setArtifactId("spring-boot-starter");
        oldBoot.setVersion("2.7.0");
        mockProject.getDependencies().add(oldBoot);
        rules.runProjectChecks(mockProject);
        assertTrue(hasIssue("Old Spring Boot Version"));
    }

    @Test
    void shouldDetectExposedDataRestRepositories() {
        MavenProject mockProject = new MavenProject();
        Dependency dataRest = new Dependency();
        dataRest.setArtifactId("spring-boot-starter-data-rest");
        mockProject.getDependencies().add(dataRest);
        rules.runProjectChecks(mockProject);
        assertTrue(hasIssue("Exposed Repositories (Data REST)"));
    }

    // --- PERFORMANCE & JPA TESTS ---

    @Test
    void shouldDetectNPlusOne() {
        executeTest("class Test { void m() { for(var x : list) { repo.getDetails(); } } }");
        assertTrue(hasIssue("Potential N+1 Query"));
    }

    @Test
    void shouldDetectJPAEager() {
        executeTest("class Test { @ManyToOne(fetch = FetchType.EAGER) private User user; }");
        assertTrue(hasIssue("EAGER Fetching detected"));
    }

    @Test
    void shouldDetectOSIVEnabled() {
        Properties props = new Properties();
        props.setProperty("spring.jpa.open-in-view", "true");
        core.executeAnalysisWithPropsOnly(props, issues, testConfig);
        assertTrue(hasIssue("OSIV is Enabled"));
    }

    @Test
    void shouldDetectBlockingCallInTransactional() {
        executeTest("class Test { @Transactional void save() { Thread.sleep(1000); } }");
        assertTrue(hasIssue("Blocking call in Transaction"));
    }

    // --- BEST PRACTICES & CONCURRENCY TESTS ---

    @Test
    void shouldDetectMissingRepositoryAnnotation() {
        executeTest("interface UserRepository extends JpaRepository<User, Long> { }");
        assertTrue(hasIssue("Missing @Repository"));
    }

    @Test
    void shouldDetectMissingResponseEntityInController() {
        executeTest("@RestController class Test { @GetMapping(\"/v1/users\") public String m() { return \"\"; } }");
        assertTrue(hasIssue("Missing ResponseEntity"));
    }

    @Test
    void shouldDetectManualThreadCreation() {
        executeTest("class Test { void run() { Thread t = new Thread(); } }");
        assertTrue(hasIssue("Manual Thread creation"));
    }

    @Test
    void shouldDetectMutableStateInSingleton() {
        executeTest("@Service class MyService { private int counter = 0; }");
        assertTrue(hasIssue("Mutable state in Singleton"));
    }

    // --- UTILITY ---

    private void executeTest(String code) {
        CompilationUnit cu = StaticJavaParser.parse(code);
        // Signature aggiornata: passiamo solo cu, fileName e props
        rules.runAllChecks(cu, "Test.java", new Properties());
    }

    private boolean hasIssue(String reasonFragment) {
        return issues.stream().anyMatch(i -> i.reason.contains(reasonFragment));
    }
}