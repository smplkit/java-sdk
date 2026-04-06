# smplkit Java SDK

See `~/.claude/CLAUDE.md` for universal rules (git workflow, testing, code quality, SDK conventions, etc.).

## Repository Structure

- `src/main/java/com/smplkit/internal/generated/` — Auto-generated client code from OpenAPI specs. Do not edit manually.
- `src/main/java/com/smplkit/` (excluding `internal/generated/`) — Hand-crafted SDK wrapper. This is the public API.

## Regenerating Clients

```bash
./scripts/generate.sh
```

## Building

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

### Coverage Report

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
