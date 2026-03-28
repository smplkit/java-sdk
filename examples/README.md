# smplkit SDK Examples

Runnable examples demonstrating the [smplkit Java SDK](https://github.com/smplkit/java-sdk).

> **Note:** These examples require valid smplkit credentials and a live environment — they are not self-contained demos.

## Prerequisites

1. Java 17+
2. A valid smplkit API key (create one in the [smplkit console](https://www.smplkit.com)).
3. At least one config created in your smplkit account (every account comes with a `common` config by default).

## Config Showcase

**File:** [`src/main/java/com/smplkit/examples/ConfigShowcase.java`](src/main/java/com/smplkit/examples/ConfigShowcase.java)

An end-to-end walkthrough of the Smpl Config SDK covering:

- **Client initialization** — `SmplkitClient.builder().apiKey(...).build()`
- **Management-plane CRUD** — create, list, get by key, and delete configs
- **Multi-level inheritance** — child → parent → common hierarchy setup
- **Config values** — setting initial base values at creation time

> **Note:** The Java SDK's management plane does not yet support `update()`, `setValues()`, or `setValue()`, and the runtime plane (`connect()`, typed accessors, WebSocket, change listeners) is not yet implemented. These sections are marked as skipped in the showcase output. See the [Python showcase](https://github.com/smplkit/python-sdk/tree/main/examples) for the full feature surface.

### Running

```bash
export SMPLKIT_API_KEY="sk_api_..."
./gradlew :examples:run
```

The script creates temporary configs, exercises the available SDK features, then cleans up after itself.
