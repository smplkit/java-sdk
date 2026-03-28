# smplkit SDK Examples

Runnable examples demonstrating the [smplkit Java SDK](https://github.com/smplkit/java-sdk).

> **Note:** These examples require valid smplkit credentials and a live environment — they are not self-contained demos.

## Prerequisites

1. Java 17+
2. A valid smplkit API key (create one in the [smplkit console](https://app.smplkit.com)).
3. At least one config created in your smplkit account (every account comes with a `common` config by default).

## Config Showcase

**File:** [`src/main/java/com/smplkit/examples/ConfigShowcase.java`](src/main/java/com/smplkit/examples/ConfigShowcase.java)

An end-to-end walkthrough of the Smpl Config SDK covering:

- **Client initialization** — `SmplClient.builder().apiKey(...).build()`
- **Management-plane CRUD** — create, update, list, get by key, and delete configs
- **Environment overrides** — `setValues()` and `setValue()` for per-environment configuration
- **Multi-level inheritance** — child → parent → common hierarchy setup
- **Runtime value resolution** — `connect()`, `get()`, typed accessors (`getString`, `getInt`, `getBool`)
- **Real-time updates** — WebSocket-driven cache invalidation with change listeners
- **Manual refresh and cache diagnostics** — `refresh()`, `stats()`
- **AutoCloseable pattern** — automatic cleanup via try-with-resources

### Running

```bash
export SMPLKIT_API_KEY="sk_api_..."
./gradlew :examples:run
```

The script creates temporary configs, exercises all SDK features, then cleans up after itself.
