# SpotBugs SpringSentinel Scripts

This directory is reserved for helper scripts used by the plugin.

Initial script ideas:

- compile SpringSentinel modules before bytecode analysis
- run SpotBugs with a selected rule set
- normalize SpotBugs XML or SARIF output for Codex review
- validate future detector fixtures

Current Gradle checks:

```bash
gradle --no-daemon :plugins:spotbugs-springsentinel:test :plugins:spotbugs-springsentinel:jar
```
