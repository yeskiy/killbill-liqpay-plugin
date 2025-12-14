# Contributing

Thanks for helping improve the Kill Bill LiqPay plugin!

## Development Setup
- Java 11+ and Maven 3.8+
- Build: `mvn clean package`
- Test: `mvn verify`
- Keep changes self-contained under `src/` and do not add `sources/` to version control.

## Conventional Commits
All commits must follow the Conventional Commits 1.0.0 spec: `type(scope): short summary`

Common types: `feat`, `fix`, `chore`, `docs`, `refactor`, `test`, `ci`, `build`.

Examples:
- `feat(api): add capture/void endpoints`
- `fix(payment): handle sandbox currency mismatch`
- `ci: enable maven cache`

## Pull Requests
- Rebase on `main` and keep history clean.
- Include tests for new logic or bug fixes.
- Update docs when behavior or configuration changes.

## Releases
Releases are driven by Git tags (e.g., `v1.2.3`). Tagging triggers the CI release workflow to build and publish artifacts. Keep commit history conventional so changelogs can be generated reliably.
