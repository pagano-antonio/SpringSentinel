package com.springsentinel;

import com.beanspringboot.AnalysisRules;
import com.beanspringboot.StaticAnalysisCore;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
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

    private final int DEFAULT_MAX_DEPS = 7;
    private final String DEFAULT_REGEX = ".*(password|secret|apikey|pwd|token).*";

    @BeforeEach
    void setUp() {
        issues = new ArrayList<>();
        rules = new AnalysisRules(issues::add);
        core = new StaticAnalysisCore(new SystemStreamLog());
    }

    // --- SECURITY & SECRETS (3 TEST) ---

    @Test
    void shouldDetectHardcodedSecrets() {
        executeTest("class Test { String myApiKey = \"AIzaSyB-12345\"; }");
        assertTrue(hasIssue("Potential Hardcoded Secret"));
    }

    @Test
    void shouldDetectSecretsWithCustomRegex() {
        CompilationUnit cu = StaticJavaParser.parse("class Vault { String my_access_token = \"12345\"; }");
        rules.runAllChecks(cu, "Test.java", new Properties(), DEFAULT_MAX_DEPS, ".*token.*");
        assertTrue(hasIssue("Potential Hardcoded Secret"));
    }

    @Test
    void shouldDetectSecretInProperties() {
        Properties props = new Properties();
        props.setProperty("database.password", "secret123");
        core.executeAnalysisWithPropsOnly(props, issues); 
        assertTrue(hasIssue("Hardcoded Secret"));
    }

    // --- ARCHITECTURE & DESIGN (5 TEST) ---

    @Test
    void shouldDetectFatComponent() {
        executeTest("@Service class FatService { @Autowired S1 s1; @Autowired S2 s2; @Autowired S3 s3; @Autowired S4 s4; @Autowired S5 s5; @Autowired S6 s6; @Autowired S7 s7; @Autowired S8 s8; }");
        assertTrue(hasIssue("Fat Component"));
    }

    @Test
    void shouldBeTolerantWithCustomMaxDependencies() {
        CompilationUnit cu = StaticJavaParser.parse("@Service class BigService { @Autowired S1 s1; @Autowired S2 s2; @Autowired S3 s3; @Autowired S4 s4; @Autowired S5 s5; @Autowired S6 s6; @Autowired S7 s7; @Autowired S8 s8; }");
        rules.runAllChecks(cu, "Test.java", new Properties(), 10, DEFAULT_REGEX);
        assertFalse(hasIssue("Fat Component"));
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

    // --- OLISTICI: POM & PROJECT (3 TEST) ---

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

    // --- PERFORMANCE & JPA (4 TEST) ---

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
        core.executeAnalysisWithPropsOnly(props, issues);
        assertTrue(hasIssue("OSIV is Enabled"));
    }

    @Test
    void shouldDetectBlockingCallInTransactional() {
        executeTest("class Test { @Transactional void save() { Thread.sleep(1000); } }");
        assertTrue(hasIssue("Blocking call in Transaction"));
    }

    // --- BEST PRACTICES & CONCURRENCY (4 TEST) ---

    @Test
    void shouldDetectMissingRepositoryAnnotation() {
        executeTest("interface UserRepository extends JpaRepository<User, Long> { }");
        assertTrue(hasIssue("Missing @Repository"));
    }

    @Test
    void shouldDetectMissingResponseEntityInController() {
        executeTest("@RestController class Test { @GetMapping(\"/x\") public String m() { return \"\"; } }");
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
        rules.runAllChecks(cu, "Test.java", new Properties(), DEFAULT_MAX_DEPS, DEFAULT_REGEX);
    }

    private boolean hasIssue(String reasonFragment) {
        return issues.stream().anyMatch(i -> i.reason.contains(reasonFragment));
    }
}