---
name: spotbugs-springsentinel
description: Prepare and evolve SpringSentinel SpotBugs bytecode analysis workflows. Use when working on the SpringSentinel SpotBugs plugin structure, future detector rules, or bytecode analysis setup.
---

# SpotBugs SpringSentinel

This skill supports the SpringSentinel SpotBugs plugin.

## Current Scope

- Keep the plugin scaffold valid for Codex.
- Treat SpotBugs as a bytecode-oriented analysis step.
- Implement ARCH-002 as the first bytecode detector for field injection.
- Avoid locking in additional detector rules until the rule policy is chosen.
- Prefer repository-local configuration so the plugin evolves with SpringSentinel.

## Implemented Rules

- `ARCH-002`: flags field-level injection annotations in compiled classes.
  - `@Autowired`
  - `@Value`
  - `@Inject`
  - `@Resource`

## Future Rule Work

When adding rule behavior later:

- Define which bytecode patterns SpringSentinel should detect.
- Add rule metadata and examples before wiring execution.
- Keep generated reports deterministic and suitable for CI.
- Validate against compiled classes rather than source-only assumptions.
