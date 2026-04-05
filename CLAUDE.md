# smplkit Java SDK

## Repository structure

Two-layer architecture (mirrors the Python SDK):
- `src/main/java/com/smplkit/internal/generated/` — Auto-generated client code from OpenAPI specs. Do not edit manually.
- `src/main/java/com/smplkit/` (excluding `internal/generated/`) — Hand-crafted SDK wrapper. This is the public API.

## Regenerating clients

```bash
./scripts/generate.sh
```

This regenerates ALL clients from ALL specs in `openapi/`. Do NOT edit files under `internal/generated/` manually — they will be overwritten on next generation.

## Every Problem Is Your Problem

There is no such thing as a "pre-existing failure" or "unrelated test failure." If you encounter a failing test, broken lint, or any other problem — whether or not it is related to your current task — you are responsible for fixing it. Do not push code when any test is failing. Fix it or flag it to Mike.

## Commits

Commit directly to main with conventional commit messages. No branches or PRs.
Exception: automated regeneration PRs from source repos use `regen/` branches by design.

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

Target 90%+ coverage on the SDK wrapper layer. Generated code coverage is not enforced.

### Coverage report

```bash
./gradlew jacocoTestReport
```

Report is written to `build/reports/jacoco/test/`.

## Java Version Policy

The SDK targets Java 17 as the minimum supported version. CI runs on JDK 17 and 21.

- Do NOT use Java features introduced after JDK 17 (e.g., record patterns from 21, unnamed variables from 22).
- Records, sealed classes, text blocks, and pattern matching for instanceof (JDK 17) ARE safe.

## Package Naming

- **Group ID:** `com.smplkit`
- **Artifact ID:** `smplkit-sdk`
- **Import:** `import com.smplkit.SmplClient`

## Publishing

Publishing is automated via CI. Semantic-release owns versioning. Do not create tags manually.

- **Conventional commits drive version bumps:** `feat:` → minor, `fix:` → patch, `BREAKING CHANGE:` → major.
