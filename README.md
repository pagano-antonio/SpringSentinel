# Spring Sentinel

**Spring Sentinel** is a powerful Maven Plugin designed for Spring Boot developers to perform static analysis and identify performance bottlenecks, security risks, and architectural smells.

## Key Features
**Spring Sentinel** is a powerful Maven Plugin designed for Spring Boot developers to perform static analysis and identify performance bottlenecks, security risks, and architectural smells.
JPA Audit: Detects N+1 queries, FetchType.EAGER usage, and Cartesian Product risks.
Transaction Safety: Identifies blocking I/O (REST calls, sleeps) inside @Transactional methods and detects proxy self-invocation bypasses.
System Analysis: Validates the balance between Tomcat Thread Pools and HikariCP connection pools.
Architecture Integrity: Finds manual thread creation (new Thread()) and Prototype beans incorrectly injected into Singletons.
Smart Reporting: Generates intuitive HTML Dashboards and structured JSON files for CI/CD integration.


## Complete Documentation
https://medium.com/@antoniopagano/how-to-use-springsentinel-245a3d2c433c



## 🚀 Quick Start
### 21. Add the Plugin
```xml
<plugin>
    <groupId>io.github.pagano-antonio</groupId>
    <artifactId>SpringSentinel</artifactId>
    <version>1.1.13</version>
    <executions>
        <execution>
            <phase>verify</phase> 
            <goals>
                <goal>audit</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <profile>strict</profile> 
        
        <maxDependencies>7</maxDependencies> 
        <secretPattern>.*(password|secret|apikey|token).*</secretPattern> 
    </configuration>
</plugin>
```

## Usage
Run the audit directly from your terminal in the project root:
```bash
mvn spring-sentinel:audit
```

## Smart Reporting

After the scan, Spring Sentinel generates two types of reports in the target/spring-sentinel-reports/ folder:

report.html:  A beautiful, human-readable dashboard for quick issue identification.

report.json: Structured data designed for CI/CD pipelines, automated analysis, or custom dashboards.


## Key Audit Checks
JPA & DB: Detects N+1 queries, EAGER fetching, and OSIV status.

Transaction Safety: Identifies blocking I/O (REST, sleeps) inside @Transactional.

Architecture: Finds Field Injection (@Autowired) and manual thread creation.

Security: Scans for hardcoded secrets (passwords, API keys).

Caching: Identifies missing TTL configurations.


##  Requirements

Java: 17 or higher.

Build Tool: Maven 3.6.0+.

## License
Distributed under the Apache License 2.0.

## Audit Rules & Analyzed Cases
This list details every check performed by the static analysis engine, the associated Rule IDs (used for XML profile configuration), and the underlying detection logic.

⚡ Performance & Database: These checks focus on optimizing database interactions and preventing resource exhaustion.

PERF-001 (JPA Eager Fetching): Scans JPA entities for FetchType.EAGER. It aims to prevent unnecessary loading of complex object graphs, which causes significant memory overhead and performance degradation.

PERF-002 (N+1 Query Potential): Identifies collection getters called inside loops (for, forEach), a common cause of database performance issues.

PERF-003 (Blocking Calls in Transactions): Detects blocking I/O or network calls (e.g., RestTemplate, Thread.sleep) within @Transactional methods to prevent connection pool exhaustion.

PERF-004 (Cache TTL Configuration): Verifies that methods annotated with @Cacheable have a corresponding Time-To-Live (TTL) defined in the application properties to avoid stale data.

🔐 Security: Focused on protecting sensitive data and ensuring secure endpoint configurations.

SEC-001 (Hardcoded Secrets Scanner): Checks class fields and properties for variable names matching sensitive patterns (e.g., password, apikey, token) that do not use environment variable placeholders.

SEC-002 (Insecure CORS Policy): Flags the use of the "*" wildcard in @CrossOrigin annotations, which is a significant security risk for production APIs.

SEC-003 (Exposed Repositories): Warns if spring-boot-starter-data-rest is included, as it automatically exposes repositories without explicit security configurations.

🏗️ Architecture & Thread Safety: Rules to ensure code follows Spring Framework best practices and is safe for multi-threaded execution.

ARCH-001 (Singleton Thread Safety): Detects mutable state in Singleton beans (Lombok-aware).

ARCH-002 (Field Injection Anti-pattern): Flags the use of @Autowired on private fields, encouraging Constructor Injection for better testability and immutability.

ARCH-003 (Fat Components Detection): Monitors the number of dependencies in a single class. If it exceeds the configured limit, it suggests refactoring into smaller, focused services.

ARCH-004 (Manual Bean Instantiation): Detects the use of the new keyword for classes that should be managed by the Spring Context (Services, Repositories, Components).

ARCH-005 (Lazy Injection Smell): Identifies @Lazy combined with @Autowired, often used as a workaround for circular dependencies.

RES-001 (Manual Thread Creation): Finds manual thread creation (new Thread()), suggesting managed @Async tasks instead.

🌐 REST API Governance: Ensures that your APIs are consistent, versioned, and follow standard RESTful design principles.

REST-001 (URL Kebab-case Enforcement): Ensures endpoint URLs follow the kebab-case convention (e.g., /user-profiles) instead of camelCase or snake_case.

REST-002 (API Versioning Check): Alerts if an endpoint is missing a versioning prefix (e.g., /v1/), which is essential for long-term API maintenance.

REST-003 (Resource Pluralization): Suggests using plural names for REST resources (e.g., /users instead of /user) to follow standard REST design.

REST-004 (Missing ResponseEntity): Encourages returning ResponseEntity<T> in Controllers to properly handle and communicate HTTP status codes.

🛠️ Build & Project Maintenance: Audits the general health of the Maven project and compliance with stable versions.

MAINT-001 (Spring Boot Version Audit): Warns if the project is still using Spring Boot 2.x and recommends upgrading to 3.x for Jakarta EE compatibility.

MAINT-002 (Missing Production Plugins): Checks for the spring-boot-maven-plugin, which is required for packaging executable artifacts.

MAINT-003 (Repository Best Practices): Ensures that data access interfaces are correctly annotated with @Repository for proper exception translation.

## Configuration 
SpringSentinel uses a hierarchical profile system to let you choose the level of "strictness" for your project. By selecting a profile, you activate a specific set of rules, allowing you to focus on critical security issues or enforce a comprehensive architectural standard.

### Profile Standard

1. Security First (security-only): This profile is designed for legacy projects or high-velocity environments where you want to ensure safety without changing the coding style or architectural patterns.

Logic: It only triggers rules related to data leaks and insecure configurations.

Rules Included:

SEC-001: Hardcoded Secrets Scanner

SEC-002: Insecure CORS Policy (* wildcard)

SEC-003: Exposed Data REST Repositories

2. Standard Spring Health (standard)
Focus: Security + Performance + Maintenance.
Inheritance: Extends security-only.
This is the recommended profile for most production applications. it balances risk prevention with code quality without being overly pedantic about naming conventions.

Logic: It ensures the application is secure, doesn't leak database connections, and follows modern Spring Boot standards.

Added Rules:

PERF-001 to PERF-004: All database and caching performance checks.

ARCH-001: Singleton Thread Safety.

ARCH-002: Field Injection Anti-pattern.

RES-001 & RES-002: Resilience and Threading checks.

MAINT-001 to MAINT-003: Versioning and best practices.

 3. Full Governance (strict)
Focus: Maximum Consistency & Best Practices.
Inheritance: Extends standard.
This is the default profile if none is specified. It is ideal for new projects (Greenfield) where maintaining a perfect "Clean Code" standard is a priority.

Logic: It enforces strict RESTful naming, architectural boundaries, and detects even minor code smells.

Added Rules:

ARCH-003: Fat Components Detection (Sustains modularity).

ARCH-004: Manual Bean Instantiation.

ARCH-005: Lazy Injection Smell.

REST-001 to REST-004: All REST API Governance rules (Kebab-case, pluralization, versioning).

### How to Use Profiles
A. In your pom.xml
You can define the profile globally for your project within the plugin configuration block:

```xml
<plugin>
    <groupId>io.github.pagano-antonio</groupId>
    <artifactId>SpringSentinel</artifactId>
    <version>1.1.12</version>
    <configuration>
        <profile>standard</profile> 
    </configuration>
</plugin>
```

### Custom XML Profiles: 
Define your own rulesets. Extend built-in profiles (security-only, standard, strict) and <include> or <exclude> specific rules to match your company's guidelines.
Granular Path Filtering (Regex): Apply rules only to specific modules or ignore legacy directories using includePaths and excludePaths.
Parameter Overrides: Fine-tune rule behaviors (like dependency limits or secret regex patterns) on a per-profile basis.
Smart Relative Paths: Reports now display the full relative path of the analyzed files (e.g., src/main/java/...) for immediate issue localization.
 You can create a custom-sentinel-rules.xml file in your project root to override defaults, ignore legacy folders, and tailor the audit strictly to your needs.

First, update your pom.xml to point to your custom file:

```xml
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>

			<plugin>
				<groupId>io.github.pagano-antonio</groupId>
				<artifactId>SpringSentinel</artifactId>
				<version>1.1.12</version>
				<executions>
					<execution>
						<phase>verify</phase>
						<goals>
							<goal>audit</goal>
						</goals>
					</execution>
				</executions>
				<configuration>
					<customRules>
						${project.basedir}/src/main/resources/custom-sentinel-rules.xml</customRules>
					<profile>my-company-profile</profile>
				</configuration>
			</plugin>
```
By setting ${project.basedir}/src/main/resources/custom-sentinel-rules.xml, you are telling the plugin to ignore its built-in default-rules.xml and load the logic from a local file in your project's resources.

Then, define your governance in custom-sentinel-rules.xml, for example:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<spring-sentinel>
    <profiles>
        <profile id="my-company-profile" extends="standard">
            <name>Company Custom Profile</name>
            <description>Standard rules adapted for our legacy codebase.</description>
            
            <exclude rule="ARCH-002" />
            
            <include rule="REST-004" />
            
            <override rule="ARCH-003" param="maxDependencies" value="15" />
            
            <override rule="ARCH-003" param="excludePaths" value=".*(/legacy/|/test/).*" />

            <override rule="REST-004" param="includePaths" value=".*/controller/.*" />
        </profile>
    </profiles>
</spring-sentinel>
```
in this casa we have:
1. Profile Inheritance (extends="standard")
The attribute extends="standard" is the foundation of this configuration.

What it does: It imports all the rules active in the built-in standard profile (Security + Performance + basic Architecture).

Why it's useful: You don't have to list 15+ rules manually; you start with a solid baseline and only declare the differences.

2. Rule Exclusion (<exclude rule="ARCH-002" />)
Target: ARCH-002 (Field Injection Anti-pattern).

Action: This specific check is disabled.

Use Case: In a legacy codebase, you might have thousands of @Autowired fields. Fixing them all at once is impossible, so you "silence" the rule to avoid noise in your reports until you are ready to refactor.

3. Rule Inclusion (<include rule="REST-004" />)
Target: REST-004 (Missing ResponseEntity).

Action: Activates this specific rule even though it is not part of the standard base profile (it's normally in strict).

Use Case: You want to enforce better HTTP status code management immediately, even if the rest of your API naming isn't perfect yet.

4. Parameter Overrides (<override ... />)
This is the most powerful part of the file. It changes how a specific rule behaves without touching the Java code.

A. Tuning Thresholds (ARCH-003)
XML
<override rule="ARCH-003" param="maxDependencies" value="15" />
Default: Usually 7.

New Value: 15.

Logic: You are allowing "fatter" components. This acknowledges that legacy services often have many dependencies, preventing the report from being flooded with "Fat Component" warnings.

B. Path Filtering (excludePaths)
XML
<override rule="ARCH-003" param="excludePaths" value=".*(/legacy/|/test/).*" />
Logic: Tells the engine to ignore any file located in a /legacy/ or /test/ folder when checking for dependency counts.

Use Case: You only want to enforce modularity on new code, leaving old or testing modules alone.

C. Targeted Analysis (includePaths)
XML
<override rule="REST-004" param="includePaths" value=".*/controller/.*" />
Logic: Restricts the "Missing ResponseEntity" check exclusively to classes within a controller package.

Use Case: Prevents the rule from running on internal helper classes that might look like controllers but aren't intended to be public APIs.



