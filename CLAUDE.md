# smplkit Java SDK

See `~/.claude/CLAUDE.md` for universal rules (git workflow, testing, code quality, SDK conventions, etc.).

## Repository Structure

- `src/main/java/com/smplkit/internal/generated/` — Auto-generated client code from OpenAPI specs. Do not edit manually.
- `src/main/java/com/smplkit/` (excluding `internal/generated/`) — Hand-crafted SDK wrapper. This is the public API.

### SDK Module Layout

```
com.smplkit
├── SmplClient.java          # Top-level entry point (builder, flags/config/logging accessors)
├── SmplClientBuilder.java   # Builder for SmplClient
├── Context.java             # Cross-cutting evaluation context
├── Rule.java                # Fluent rule builder for flag targeting
├── LogLevel.java            # Log level enum (TRACE..SILENT)
├── SharedWebSocket.java     # Shared WebSocket connection infrastructure
├── Helpers.java             # Shared utilities (keyToDisplayName)
├── ApiKeyResolver.java      # Multi-source API key resolution
├── flags/
│   ├── Flag.java            # Unified flag model — runtime handle + management active record
│   ├── FlagsClient.java     # Flags service client (lazy init, CRUD, evaluation)
│   ├── FlagChangeEvent.java # Change notification record
│   └── FlagStats.java       # Cache diagnostics record
├── config/
│   ├── Config.java          # Mutable config model with save()
│   ├── ConfigClient.java    # Config service client (lazy resolve, CRUD, subscribe)
│   ├── ConfigChangeEvent.java # Change notification record
│   ├── LiveConfig.java      # Live-updating config proxy
│   └── Resolver.java        # Config inheritance deep-merge resolution
├── logging/
│   ├── Logger.java          # Logger model with level convenience methods
│   ├── LogGroup.java        # Log group model
│   ├── LoggingClient.java   # Logging service client (start, CRUD, level resolution)
│   └── LoggerChangeEvent.java # Change notification record
└── errors/
    ├── SmplException.java   # Base exception
    └── ...                  # Typed exceptions (NotFound, Conflict, Validation, etc.)
```

### Architecture Patterns

- **Lazy init:** Flags and config use lazy initialization — first `Flag.get()` or `ConfigClient.resolve()` triggers connection. Logging uses explicit `start()`.
- **Active record:** `Flag`, `Config`, `Logger`, `LogGroup` all have `save()` that POSTs (new) or PUTs (existing) and applies the response back.
- **Management vs runtime:** Management methods (`get`, `list`, `new_`, `delete`, `save`) always use HTTP and never trigger lazy init. Runtime methods (`Flag.get()`, `resolve()`, `subscribe()`, `start()`) trigger lazy init.
- **No connect():** SmplClient has no explicit `connect()` method. Runtime init is lazy per-module.

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

### Intermittent coverage-gate flake — playbook

CI sometimes fails with `jacocoTestCoverageVerification FAILED` and
`Rule violated for bundle smplkit-sdk: lines covered ratio is 0.99,
but expected minimum is 1.00`. Rate is ~33% (≈5 of 15 recent runs).
Despite earlier reporting, the flake is **NOT JDK-version-specific** —
it hits JDK 17 or JDK 21 unpredictably, and only one matrix entry per
run.

What "0.99" actually means: the wrapper is ≈4855 lines, so the gap is
**25-73 missed lines**, not a single line. Tests still pass — only
the verification gate fails. Strongly suggests races in background-
thread coverage (daemon threads spawned by `SharedWebSocket.reconnect`,
`FlagsClient.scheduleConnectRetry`, `MetricsReporter` scheduled tasks)
where tests use `Thread.sleep(50/100/200)` waits that don't reliably
let the daemon execute its coverage-bearing code under CI load. The
suspect tests have no hard assertion on the daemon's post-sleep work,
so the test passes but the lines never get recorded.

**Cannot reproduce locally** on Apple Silicon, even under CPU stress.
Don't burn time trying.

**Recovery path when this hits:**

1. **Get main green first.** `gh run rerun <run-id> --failed` —
   ≈67% chance the rerun passes. Don't fix the flake under merge
   pressure; recover main, then investigate.
2. **Capture the artifact.** CI uploads `coverage-report-jdk-17` and
   `coverage-report-jdk-21` on **every** run, success or failure
   (`if: always()` in `.github/workflows/ci-cd.yml`). Download the
   artifact from the **failing JDK's matrix entry** of the failed
   run — that's the one whose report has the missing lines.
3. **Find the missing lines.** Diff against a baseline by checking
   each wrapper class's `LINE` counter `missed` attribute in
   `jacocoTestReport.xml`. A passing run shows `missed=0` for every
   wrapper class; the failing run will have `missed>0` somewhere.
   Then open the HTML report (`reports/jacoco/test/html/`) to see
   which specific lines are uncovered.
4. **Fix the root cause.** Find the test that's the sole coverer of
   those lines (run that test in isolation: `./gradlew test --tests
   <TestClass>` and inspect its coverage contribution). Replace the
   timing-based wait with a deterministic barrier — usually a
   `CountDownLatch.await(timeout, TimeUnit.SECONDS)` that the
   wrapper code counts down when it reaches the line under test,
   plus a hard `assertTrue(latch.await(...))` so the test fails
   loudly instead of silently dropping coverage.
5. **Do NOT add a JaCoCo `excludes` entry** as a shortcut. The wrapper
   is at 100% by design and coverage exclusions need Mike's explicit
   permission (universal rule).

Suspect tests to look at first: `SharedWebSocketLifecycleTest`
(`Thread.sleep(50/100/200)` after spawning reconnect threads),
`TelemetryIntegrationTest` (several `Thread.sleep(100)` waits around
ws lifecycle), `MetricsReporterTest.timerStartsLazilyOnFirstRecord`
(1.5s wait for timer fire), `FlagsClientStartRetryTest.retryTimer_firesAutomaticallyAndConnects`
(1.5s wait for retry).

## Java Version Policy

The SDK targets Java 17 as the minimum supported version. CI runs on JDK 17 and 21.

- Do NOT use Java features introduced after JDK 17 (e.g., record patterns from 21, unnamed variables from 22).
- Records, sealed classes, text blocks, and pattern matching for instanceof (JDK 17) ARE safe.

## Package Naming

- **Group ID:** `com.smplkit`
- **Artifact ID:** `smplkit-sdk`
- **Import:** `import com.smplkit.SmplClient`
