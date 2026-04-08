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

## Java Version Policy

The SDK targets Java 17 as the minimum supported version. CI runs on JDK 17 and 21.

- Do NOT use Java features introduced after JDK 17 (e.g., record patterns from 21, unnamed variables from 22).
- Records, sealed classes, text blocks, and pattern matching for instanceof (JDK 17) ARE safe.

## Package Naming

- **Group ID:** `com.smplkit`
- **Artifact ID:** `smplkit-sdk`
- **Import:** `import com.smplkit.SmplClient`
