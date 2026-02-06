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
