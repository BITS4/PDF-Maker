# Contributing to PDF Maker

## Development workflow

1. Start from an up-to-date `main` branch and create a focused branch.
2. Keep one feature, fix, or refactor per pull request.
3. Add or update tests in the same commit as the behavior they prove.
4. Run the local quality, test, coverage, and build gates.
5. Open a pull request using the repository template and explain risk and rollback.

Do not combine behavioral work with repository-wide formatting. Avoid committing generated build outputs, Android SDK
paths, signing material, personal documents, or IDE device state.

## Commit messages

Use Conventional Commits:

```text
feat(scan): validate captured page bounds
fix(storage): reject paths outside the selected document
test(merge): cover an empty input set
refactor(viewer): extract page geometry policy
docs(readme): clarify fresh-clone setup
ci(android): enforce release lint
```

Use the smallest scope that explains the change. The subject should state an outcome, not `changes`, `update`, or
`fix everything`.

## Required checks

```bash
./gradlew lint typecheck
./gradlew test coverage
./gradlew build
```

Run `:app:connectedDebugAndroidTest` when changing navigation, permissions, activities, resources, or Compose behavior.
CI runs the connected suite on an API 35 emulator.

## Test expectations

- Prefer deterministic unit tests for document policies, parsing, range validation, geometry, naming, and failure paths.
- Cover both valid and invalid inputs, boundary values, and expected exceptions.
- Keep Android framework interaction behind a small adapter so ordinary behavior remains JVM-testable.
- Do not exclude code to inflate coverage or weaken the critical-domain gate. Add tests and ratchet that gate upward.
- Use synthetic, non-sensitive fixtures that are small enough to review.

The coverage scopes and numeric gates are documented in [`docs/QUALITY.md`](docs/QUALITY.md).

## Dependency updates

Dependency updates belong in their own commit or pull request. Review release notes, refresh lock and verification
metadata, run the entire gate, and record deferred major upgrades in `docs/DEPENDENCIES.md`.

## Security and privacy

Never place real documents, signing keys, passwords, access tokens, or private user data in the repository. Report a
suspected vulnerability through the private process in `SECURITY.md`, not a public issue.

## Review standard

A reviewer should be able to identify the user outcome, locate the test evidence, understand new permissions or data
access, and revert the change without unrelated churn.
