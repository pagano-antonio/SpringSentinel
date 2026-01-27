Spring Sentinel
Spring Sentinel is a high-performance static analysis tool for Spring Boot applications. It is designed to identify performance bottlenecks, JPA inefficiencies, and system configuration misalignments before they reach production.

🚀 Key Features
JPA Audit: Detects N+1 queries, FetchType.EAGER usage, and Cartesian Product risks.

Transaction Safety: Identifies blocking I/O (REST calls, sleeps) inside @Transactional methods and detects proxy self-invocation bypasses.

System Analysis: Validates the balance between Tomcat Thread Pools and HikariCP connection pools.

Architecture Integrity: Finds manual thread creation (new Thread()) and Prototype beans incorrectly injected into Singletons.

Smart Reporting: Generates intuitive HTML Dashboards and structured JSON files for CI/CD integration.

🛠️ Requirements
Java: 17 or higher.

Build Tool: Maven.

📦 Installation & Build
To use Spring Sentinel in your project, add the following configuration to your `pom.xml` file:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>antpag</groupId>
            <artifactId>spring-sentinel-maven-plugin</artifactId>
            <version>1.1.1</version>
        </plugin>
    </plugins>
</build>

🖥️ Usage
Run the audit directly from your terminal in the project root:

Bash
mvn antpag:spring-sentinel-maven-plugin:audit

📊 Output
After the scan, you will find the results in the spring-sentinel-reports/ folder:

sentinel-audit.html: A visual, color-coded dashboard for developers.

sentinel-audit.json: Structured data for automated analysis or custom dashboards.

📝 License
Distributed under the Apache License 2.0. See LICENSE for more information.
