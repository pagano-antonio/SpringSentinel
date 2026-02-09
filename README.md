# Spring Sentinel

**Spring Sentinel** is a powerful Maven Plugin designed for Spring Boot developers to perform static analysis and identify performance bottlenecks, security risks, and architectural smells.

## Key Features
**Spring Sentinel** is a powerful Maven Plugin designed for Spring Boot developers to perform static analysis and identify performance bottlenecks, security risks, and architectural smells.
JPA Audit: Detects N+1 queries, FetchType.EAGER usage, and Cartesian Product risks.
Transaction Safety: Identifies blocking I/O (REST calls, sleeps) inside @Transactional methods and detects proxy self-invocation bypasses.
System Analysis: Validates the balance between Tomcat Thread Pools and HikariCP connection pools.
Architecture Integrity: Finds manual thread creation (new Thread()) and Prototype beans incorrectly injected into Singletons.
Smart Reporting: Generates intuitive HTML Dashboards and structured JSON files for CI/CD integration.



## 🚀 Quick Start
### 21. Add the Plugin
```xml
<plugin>
    <groupId>io.github.pagano-antonio</groupId>
    <artifactId>SpringSentinel</artifactId>
    <version>1.1.9</version>
    <executions>
        <execution>
            <phase>verify</phase> 
            <goals>
                <goal>audit</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <maxDependencies>7</maxDependencies> 
        <secretPattern>.*(password|secret|apikey).*</secretPattern> 
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

⚡ Performance & Database
JPA Eager Fetching Detection: Scans for FetchType.EAGER in JPA entities to prevent unnecessary memory overhead and performance degradation.

N+1 Query Potential: Identifies collection getters called inside loops (for, forEach), a common cause of database performance issues.

Blocking Calls in Transactions: Detects blocking I/O or network calls (e.g., RestTemplate, Thread.sleep) within @Transactional methods to prevent connection pool exhaustion.

Cache TTL Configuration: Verifies that methods annotated with @Cacheable have a corresponding Time-To-Live (TTL) defined in the application properties to avoid stale data.


🔐 Security
Hardcoded Secrets Scanner: Checks class fields and properties for variable names matching sensitive patterns (e.g., password, apikey, token) that do not use environment variable placeholders.

Insecure CORS Policy: Flags the use of the "*" wildcard in @CrossOrigin annotations, which is a significant security risk for production APIs.

Exposed Repositories: Warns if spring-boot-starter-data-rest is included, as it automatically exposes repositories without explicit security configurations.


🏗️ Architecture & Thread Safety
Singleton Thread Safety (Lombok-aware): Detects mutable state in Singleton beans.

Field Injection Anti-pattern: Flags the use of @Autowired on private fields, encouraging Constructor Injection for better testability and immutability.

Fat Components Detection: Monitors the number of dependencies in a single class. If it exceeds the configured limit, it suggests refactoring into smaller, focused services.

Manual Bean Instantiation: Detects the use of the new keyword for classes that should be managed by the Spring Context (Services, Repositories, Components).

Lazy Injection Smell: Identifies @Lazy combined with @Autowired, often used as a workaround for circular dependencies.


🌐 REST API Governance (New in v1.2.0)
URL Kebab-case Enforcement: Ensures endpoint URLs follow the kebab-case convention (e.g., /user-profiles) instead of camelCase or snake_case.

API Versioning Check: Alerts if an endpoint is missing a versioning prefix (e.g., /v1/), which is essential for long-term API maintenance.

Resource Pluralization: Suggests using plural names for REST resources (e.g., /users instead of /user) to follow standard REST design.

Missing ResponseEntity: Encourages returning ResponseEntity<T> in Controllers to properly handle and communicate HTTP status codes.


🛠️ Build & Project Maintenance
Spring Boot Version Audit: Warns if the project is still using Spring Boot 2.x and recommends upgrading to 3.x for Jakarta EE compatibility.

Missing Production Plugins: Checks for the spring-boot-maven-plugin, which is required for packaging executable artifacts.

Repository Best Practices: Ensures that data access interfaces are correctly annotated with @Repository for proper exception translation.


## Configuration 
ConfigurationSpringSentinel is designed to be flexible. You can customize the audit thresholds and security patterns by adding a <configuration> block to the plugin declaration in your pom.xml.Available ParametersParameterDefault ValueDescriptionmaxDependencies7The maximum number of allowed dependencies (constructor params + injected fields) before a "Fat Component" warning is triggered.secretPattern.*(password|secret|apikey|pwd|token).*A regular expression used to scan field names and properties for potential hardcoded sensitive data.

Example Usage
```xml
<plugin>
    <groupId>io.github.pagano-antonio</groupId>
    <artifactId>SpringSentinel</artifactId>
    <version>1.2.0</version>
    <configuration>
        <maxDependencies>10</maxDependencies>
        
        <secretPattern>.*(password|secret|apikey|pwd|token|auth-key|private-id).*</secretPattern>
    </configuration>
    <executions>
        <execution>
            <phase>compile</phase>
            <goals>
                <goal>audit</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

