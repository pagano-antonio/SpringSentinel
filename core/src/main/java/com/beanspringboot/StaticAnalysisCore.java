package com.beanspringboot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

/**
 * Core engine for SpringSentinel static analysis.
 * CLEAN VERSION (no Maven dependencies)
 */
public class StaticAnalysisCore {

    private static final List<String> RECOMMENDED_HIKARI_POOL_PROPERTIES = List.of(
            "spring.datasource.hikari.maximum-pool-size",
            "spring.datasource.hikari.connection-timeout",
            "spring.datasource.hikari.max-lifetime",
            "spring.datasource.hikari.minimum-idle"
    );
    private static final List<String> SERVLET_THREAD_PROPERTIES = List.of(
            "server.tomcat.max-threads",
            "server.tomcat.threads.max",
            "server.undertow.threads.worker",
            "server.jetty.threads.max"
    );

    private final Consumer<String> log;
    private ProjectInfo projectInfo;

    private final List<AuditIssue> issues = new ArrayList<>();

    private String selectedProfile = "strict";
    private File customRulesFile;
    private int maxDependencies = 7;
    private String secretPattern = ".*(password|secret|apikey|pwd|token).*";
    private String language = ReportMessages.DEFAULT_LANGUAGE;

    public StaticAnalysisCore(Consumer<String> log) {
        this.log = log != null ? log : System.out::println;
        configureJavaParser();
    }

    public void setProjectInfo(ProjectInfo projectInfo) {
        this.projectInfo = projectInfo;
    }

    public void setSelectedProfile(String profile) {
        this.selectedProfile = profile;
    }

    public void setCustomRulesFile(File file) {
        this.customRulesFile = file;
    }

    public void setMaxDependencies(int maxDependencies) {
        this.maxDependencies = maxDependencies;
    }

    public void setSecretPattern(String secretPattern) {
        this.secretPattern = secretPattern;
    }

    /**
     * Sets the two-character language code for the generated HTML report.
     * Defaults to Italian ({@code "it"}); {@code "en"} selects English.
     *
     * @param language the requested report language code
     */
    public void setLanguage(final String language) {
        this.language = language;
    }

    private void configureJavaParser() {
        ParserConfiguration config = new ParserConfiguration();
        try {
            ParserConfiguration.LanguageLevel[] levels = ParserConfiguration.LanguageLevel.values();

            ParserConfiguration.LanguageLevel maxLevel = Arrays.stream(levels)
                    .filter(l -> l.name().startsWith("JAVA_"))
                    .reduce((first, second) -> second)
                    .orElse(ParserConfiguration.LanguageLevel.JAVA_17);

            config.setLanguageLevel(maxLevel);
            log.accept("JavaParser configured: " + maxLevel.name());

        } catch (Exception e) {
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            log.accept("Fallback to JAVA_17");
        }

        StaticJavaParser.setConfiguration(config);
    }

    public void executeAnalysis(File baseDir, File outputDir) throws Exception {
        issues.clear();

        Path javaPath = baseDir.toPath().resolve("src/main/java");
        Path resPath = baseDir.toPath().resolve("src/main/resources");

        log.accept("Loading profile: " + selectedProfile);

        RuleConfigLoader configLoader = new RuleConfigLoader(log);
        ResolvedConfig config = configLoader.loadActiveRules(customRulesFile, selectedProfile);

        if (maxDependencies != 7) {
            config.overrideParameter("ARCH-003", "maxDependencies", String.valueOf(maxDependencies));
        }

        if (!secretPattern.equals(".*(password|secret|apikey|pwd|token).*")) {
            config.overrideParameter("SEC-001", "pattern", secretPattern);
        }

        AnalysisRules rules = new AnalysisRules(this.issues::add, config);

        // ✅ PROJECT CHECK (NEUTRO)
        if (projectInfo != null) {
            log.accept("Running project checks...");
            rules.runProjectChecks(projectInfo);
        }

        Properties props = loadProperties(resPath);
        executeAnalysisWithPropsOnly(props, this.issues, config);

        if (Files.exists(javaPath)) {
            log.accept("Scanning Java files...");

            Map<Path, CompilationUnit> compilationUnits = new LinkedHashMap<>();

            try (Stream<Path> paths = Files.walk(javaPath)) {
                paths.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                compilationUnits.put(path, StaticJavaParser.parse(path));
                            } catch (Exception e) {
                                log.accept("Parse error: " + path.getFileName());
                            }
                        });
            }

            rules.indexPasswordProtection(compilationUnits.values());
            compilationUnits.forEach((path, cu) -> {
                String relative = baseDir.toPath().relativize(path).toString();
                rules.runAllChecks(cu, relative, props);
            });
        }

        new ReportGenerator(ReportMessages.forLanguage(language, log)).generateReports(outputDir, issues, selectedProfile);
    }

    public void executeAnalysisWithPropsOnly(Properties props,
                                             List<AuditIssue> issuesList,
                                             ResolvedConfig config) {

        String pattern = config.getParameter("SEC-001", "pattern", secretPattern);

        if (config.getActiveRules().contains("ARCH-OSIV"))
            checkOSIV(props, issuesList);

        if (config.getActiveRules().contains("SEC-001"))
            checkPropertiesSecrets(props, issuesList, pattern);

        if (config.getActiveRules().contains("SEC-H2"))
            checkCriticalProperties(props, issuesList);

        if (config.getActiveRules().contains("SEC-004"))
            checkActuatorExposure(props, issuesList);

        if (config.getActiveRules().contains("DB-001"))
            checkMissingHikariPoolConfiguration(props, issuesList);

        if (config.getActiveRules().contains("DB-002"))
            checkPotentialHikariOversizing(props, issuesList);

        if (config.getActiveRules().contains("DB-003"))
            checkDynamicHikariPoolSizing(props, issuesList);

        if (config.getActiveRules().contains("DB-004"))
            checkMisalignedLeakDetectionThreshold(props, issuesList);
    }

    private void checkOSIV(Properties p, List<AuditIssue> issuesList) {
        if ("true".equals(p.getProperty("spring.jpa.open-in-view", "true"))) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Architecture", "OSIV Enabled",
                    "Disable open-in-view"));
        }
    }

    private void checkPropertiesSecrets(Properties p,
                                        List<AuditIssue> issuesList,
                                        String pattern) {

        p.forEach((key, value) -> {
            String k = key.toString().toLowerCase();
            if (k.matches(pattern) && !value.toString().matches("\\$\\{.*\\}")) {
                issuesList.add(new AuditIssue(
                        "application.properties", 0,
                        "Security", "Hardcoded Secret",
                        "Move to env variables"));
            }
        });
    }

    private void checkCriticalProperties(Properties p,
                                         List<AuditIssue> issuesList) {

        if ("true".equals(p.getProperty("spring.h2.console.enabled"))) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Security", "H2 Console Enabled",
                    "Disable in production"));
        }
    }

    private void checkActuatorExposure(Properties p,
                                       List<AuditIssue> issuesList) {

        String exposure = p.getProperty("management.endpoints.web.exposure.include");

        if (exposure != null && exposure.contains("*")) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Security", "Actuator Exposed",
                    "Avoid wildcard exposure"));
        }
    }

    private void checkMissingHikariPoolConfiguration(Properties p,
                                                     List<AuditIssue> issuesList) {

        boolean hasDatasource = hasAnyPropertyWithPrefix(p, "spring.datasource.");
        boolean usesHikari = isExplicitHikariDatasource(p) || isImplicitHikariDatasource(p, hasDatasource);
        boolean hasRecommendedHikariPoolConfig = RECOMMENDED_HIKARI_POOL_PROPERTIES.stream()
                .anyMatch(p::containsKey);

        if (hasDatasource && usesHikari && !hasRecommendedHikariPoolConfig) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Configuration",
                    "HikariCP is being used with implicit defaults.",
                    "While Spring Boot provides sensible defaults, production workloads often require explicit tuning for connection pool sizing and timeout behavior. Recommended properties: spring.datasource.hikari.maximum-pool-size, spring.datasource.hikari.connection-timeout, spring.datasource.hikari.max-lifetime, spring.datasource.hikari.minimum-idle. Avoid oversizing pools: more connections do not necessarily improve performance."));
        }
    }

    private boolean isExplicitHikariDatasource(Properties p) {
        String datasourceType = p.getProperty("spring.datasource.type", "");

        return datasourceType.contains("com.zaxxer.hikari.HikariDataSource") ||
                hasAnyPropertyWithPrefix(p, "spring.datasource.hikari.");
    }

    private boolean isImplicitHikariDatasource(Properties p, boolean hasDatasource) {
        String datasourceType = p.getProperty("spring.datasource.type", "");

        if (!datasourceType.isBlank() && !datasourceType.contains("com.zaxxer.hikari.HikariDataSource")) {
            return false;
        }

        return hasDatasource && (hasHikariDependency() || projectInfo == null);
    }

    private boolean hasHikariDependency() {
        if (projectInfo == null || projectInfo.dependencies == null) {
            return false;
        }

        return projectInfo.dependencies.stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .anyMatch(dep -> dep.contains("hikari") ||
                        dep.equals("spring-boot-starter-jdbc") ||
                        dep.equals("spring-boot-starter-data-jpa") ||
                        dep.equals("spring-boot-starter-data-jdbc"));
    }

    private boolean hasAnyPropertyWithPrefix(Properties p, String prefix) {
        return p.keySet().stream()
                .map(Object::toString)
                .anyMatch(key -> key.startsWith(prefix));
    }

    private void checkPotentialHikariOversizing(Properties p,
                                                List<AuditIssue> issuesList) {

        OptionalInt maxPoolSize = readPositiveInt(p, "spring.datasource.hikari.maximum-pool-size");

        if (maxPoolSize.isEmpty()) {
            return;
        }

        int poolSize = maxPoolSize.getAsInt();

        if (poolSize > 100) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Configuration", "Potential HikariCP Oversizing",
                    "Extremely large connection pool detected. This is often a sign of architecture or database contention issues."));
        } else if (poolSize > 50) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Configuration", "Potential HikariCP Oversizing",
                    "HikariCP creator recommends small pools. Oversized pools may increase contention and latency."));
        }

        readServletThreadCount(p).ifPresent(threadCount -> {
            if (poolSize >= threadCount * 4) {
                issuesList.add(new AuditIssue(
                        "application.properties", 0,
                        "Configuration", "Potential HikariCP Oversizing",
                        "Pool size exceeds servlet thread count by 4×. This may indicate oversizing."));
            }
        });
    }

    private OptionalInt readServletThreadCount(Properties p) {
        return SERVLET_THREAD_PROPERTIES.stream()
                .map(property -> readPositiveInt(p, property))
                .filter(OptionalInt::isPresent)
                .mapToInt(OptionalInt::getAsInt)
                .findFirst();
    }

    private OptionalInt readPositiveInt(Properties p, String propertyName) {
        String value = p.getProperty(propertyName);

        if (value == null || value.isBlank()) {
            return OptionalInt.empty();
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private void checkDynamicHikariPoolSizing(Properties p,
                                              List<AuditIssue> issuesList) {

        OptionalInt maximumPoolSize = readPositiveInt(p, "spring.datasource.hikari.maximum-pool-size");
        OptionalInt minimumIdle = readPositiveInt(p, "spring.datasource.hikari.minimum-idle");

        if (maximumPoolSize.isPresent() &&
                minimumIdle.isPresent() &&
                minimumIdle.getAsInt() != maximumPoolSize.getAsInt()) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Configuration", "Dynamic HikariCP pool sizing",
                    "HikariCP works best with a fixed-size pool. Set spring.datasource.hikari.minimum-idle equal to spring.datasource.hikari.maximum-pool-size, or omit minimum-idle to let HikariCP use fixed-size behavior."));
        }
    }

    private void checkMisalignedLeakDetectionThreshold(Properties p,
                                                       List<AuditIssue> issuesList) {

        OptionalLong leakDetectionThreshold = readPositiveLongMillis(p,
                "spring.datasource.hikari.leak-detection-threshold",
                "spring.datasource.hikari.leakDetectionThreshold");

        if (leakDetectionThreshold.isEmpty()) {
            return;
        }

        OptionalLong effectiveTimeout = readEffectiveTimeoutMillis(p);

        if (effectiveTimeout.isEmpty()) {
            return;
        }

        if (leakDetectionThreshold.getAsLong() < effectiveTimeout.getAsLong() * 0.8d) {
            issuesList.add(new AuditIssue(
                    "application.properties", 0,
                    "Configuration",
                    "Hikari leak detection threshold is lower than the configured transaction/query timeout.",
                    "Valid long-running operations may be reported as connection leaks, producing misleading diagnostics. Suggested fix: Configure leakDetectionThreshold above the longest expected transaction or query timeout."));
        }
    }

    private OptionalLong readEffectiveTimeoutMillis(Properties p) {
        return Stream.of(
                        readPositiveLongSeconds(p, "spring.transaction.default-timeout"),
                        readPositiveLongMillis(p, "javax.persistence.query.timeout"),
                        readPositiveLongSeconds(p, "hibernate.jdbc.timeout"))
                .filter(OptionalLong::isPresent)
                .mapToLong(OptionalLong::getAsLong)
                .max();
    }

    private OptionalLong readPositiveLongMillis(Properties p, String... propertyNames) {
        for (String propertyName : propertyNames) {
            OptionalLong value = readPositiveLong(p, propertyName);
            if (value.isPresent()) {
                return value;
            }
        }

        return OptionalLong.empty();
    }

    private OptionalLong readPositiveLongSeconds(Properties p, String propertyName) {
        OptionalLong value = readPositiveLong(p, propertyName);
        return value.isPresent()
                ? OptionalLong.of(value.getAsLong() * 1000L)
                : OptionalLong.empty();
    }

    private OptionalLong readPositiveLong(Properties p, String propertyName) {
        String value = p.getProperty(propertyName);

        if (value == null || value.isBlank()) {
            return OptionalLong.empty();
        }

        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? OptionalLong.of(parsed) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private Properties loadProperties(Path resPath) {
        Properties props = new Properties();

        Path p = resPath.resolve("application.properties");

        if (Files.exists(p)) {
            try (var is = Files.newInputStream(p)) {
                props.load(is);
            } catch (IOException e) {
                log.accept("Error loading properties");
            }
        }

        return props;
    }

    public List<AuditIssue> getIssues() {
        return this.issues;
    }

    public static class AuditIssue {
        public final String file, type, reason, suggestion;
        public final int line;

        public AuditIssue(String f, int l, String t, String r, String s) {
            this.file = f;
            this.line = l;
            this.type = t;
            this.reason = r;
            this.suggestion = s;
        }
    }
}
