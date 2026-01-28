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
mvn antpag:spring-sentinel-maven-plugin:audit

📊 Output
After the scan, Spring Sentinel generates two types of reports in the target/spring-sentinel-reports/ folder:
report.html: A beautiful, human-readable dashboard for quick issue identification.
report.json: Structured data designed for CI/CD pipelines, automated analysis, or custom dashboards.

📝 License
Distributed under the Apache License 2.0. See LICENSE for more information.
