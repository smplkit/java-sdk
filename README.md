# smplkit Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/com.smplkit/smplkit-sdk)](https://central.sonatype.com/artifact/com.smplkit/smplkit-sdk) [![Build](https://github.com/smplkit/java-sdk/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/smplkit/java-sdk/actions) [![Coverage](https://codecov.io/gh/smplkit/java-sdk/branch/main/graph/badge.svg)](https://codecov.io/gh/smplkit/java-sdk) [![License](https://img.shields.io/github/license/smplkit/java-sdk)](LICENSE) [![Docs](https://img.shields.io/badge/docs-docs.smplkit.com-blue)](https://docs.smplkit.com)

The official Java SDK for [smplkit](https://www.smplkit.com) — simple application infrastructure that just works.

## Installation

### Gradle (Kotlin DSL)

```kotlin
implementation("com.smplkit:smplkit-sdk:3.0.4")
```

### Gradle (Groovy DSL)

```groovy
implementation 'com.smplkit:smplkit-sdk:3.0.4'
```

### Maven

```xml
<dependency>
    <groupId>com.smplkit</groupId>
    <artifactId>smplkit-sdk</artifactId>
    <version>3.0.4</version>
</dependency>
```

## Requirements

- Java 17 or later

## Quick Start

`SmplClient` requires three settings — `apiKey`, `environment`, and `service` —
each resolvable from builder methods, environment variables (`SMPLKIT_API_KEY`,
`SMPLKIT_ENVIRONMENT`, `SMPLKIT_SERVICE`), or `~/.smplkit`. `build()` throws if
any of them can't be resolved.

```java
import com.smplkit.SmplClient;

// Option 1: Fully explicit
try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .service("my-service")
        .build()) {
    // ... use client.config() / .flags() / .logging() / .manage()
}

// Option 2: Environment variables only
// export SMPLKIT_API_KEY=sk_api_... SMPLKIT_ENVIRONMENT=production SMPLKIT_SERVICE=my-service
try (SmplClient client = SmplClient.builder().build()) { /* ... */ }

// Option 3: Named profile from ~/.smplkit
try (SmplClient client = SmplClient.builder().profile("local").build()) { /* ... */ }
```

`SmplClient` is `AutoCloseable` — always use try-with-resources or call
`close()`, otherwise the live-updates WebSocket and metrics threads stay alive.

### `waitUntilReady()`

Code that creates a client and immediately fires a management write (then expects
to observe the resulting WebSocket event) needs to wait until the live-updates
subscription is registered server-side. Without it the broadcast races the
subscribe and is silently missed:

```java
try (SmplClient client = SmplClient.builder().build()) {
    client.waitUntilReady();                    // default 10s deadline
    // client.waitUntilReady(Duration.ofSeconds(30));  // custom deadline
    // ... safe to fire writes that you expect to broadcast back
}
```

## Flags

The Flags module provides feature flag management and a runtime tier with typed flag handles, local evaluation, and real-time updates.

### Management API

```java
import com.smplkit.SmplClient;
import com.smplkit.Rule;
import com.smplkit.flags.Flag;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .build()) {

    // Create flags of each type
    Flag<Boolean> darkMode = client.flags().management()
        .newBooleanFlag("dark-mode", false, "Dark Mode", "Controls the dark mode UI.");
    darkMode.save();

    Flag<String> banner = client.flags().management()
        .newStringFlag("banner-text", "Welcome!", "Banner Text", "Homepage banner.");
    banner.save();

    Flag<Number> rateLimit = client.flags().management()
        .newNumberFlag("rate-limit", 100, "Rate Limit", "Max requests per minute.");
    rateLimit.save();

    Flag<Object> uiConfig = client.flags().management()
        .newJsonFlag("ui-config", Map.of("theme", "light"), "UI Config", "Layout config.");
    uiConfig.save();

    // Add a targeting rule
    darkMode.addRule(new Rule("Enable for enterprise")
        .environment("production")
        .when("user.plan", "==", "enterprise")
        .serve(true)
        .build());
    darkMode.save();

    // Fetch, list, update
    Flag<?> fetched = client.flags().management().get("dark-mode");
    List<Flag<?>> all = client.flags().management().list();

    darkMode.setDescription("Updated description");
    darkMode.save();

    // Delete — either through the active record or the management facade
    darkMode.delete();
    // client.flags().management().delete("dark-mode");
}
```

### Runtime Tier (Typed Handles, Local Evaluation)

```java
import com.smplkit.Context;
import com.smplkit.flags.Flag;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .service("my-service")
        .build()) {

    // Declare typed handles at startup — lazy init, no explicit connect() needed
    Flag<Boolean> darkMode = client.flags().booleanFlag("dark-mode", false);
    Flag<String> banner = client.flags().stringFlag("banner-text", "Welcome!");
    Flag<Number> rateLimit = client.flags().numberFlag("rate-limit", 100);
    Flag<Object> uiConfig = client.flags().jsonFlag("ui-config", Map.of("theme", "light"));

    // Two ways to attach context to evaluations:
    //
    // (a) Ambient context for the current thread — typical for request middleware:
    client.setContext(List.of(
        Context.builder("user", "user-42")
            .attr("plan", "enterprise")
            .attr("country", "US")
            .build()
    ));
    // ...later, on the same thread:
    boolean isDarkMode = darkMode.get();        // picks up the ambient context

    // (b) A context provider callback — invoked on every evaluation:
    client.flags().setContextProvider(() -> List.of(
        Context.builder("user", currentUserId()).build()
    ));

    // Override context per-call (highest priority — wins over both above):
    boolean guestDarkMode = darkMode.get(List.of(
        Context.builder("user", "guest-1").attr("plan", "free").build()
    ));

    // Evaluate — synchronous, local, zero network after first call
    String bannerText = banner.get();
    Number limit = rateLimit.get();

    // Listen for flag changes (WebSocket + manual refresh both fire these)
    client.flags().onChange(event ->
        System.out.println("Flag changed: " + event.id()));

    // Force a server re-fetch (useful after server-side bulk updates)
    client.flags().refresh();

    // Cache stats for diagnostics
    FlagStats stats = client.flags().stats();
    System.out.println("hits=" + stats.cacheHits() + " misses=" + stats.cacheMisses());
}
```

## Configuration

The Config module provides hierarchical configuration management with environment overrides, deep-merge inheritance, and a live-update runtime tier.

### Management API

```java
import com.smplkit.config.Config;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .build()) {

    // Create a config and set items via the typed setters
    Config svc = client.config().management().new_("user_service",
            "User Service", "Main user service config", null);
    svc.setNumber("cache_ttl", 300);
    svc.setBoolean("enable_signup", true);
    svc.setString("database.host", "users-rds.internal");
    svc.save();

    // Per-environment overrides — third arg is the environment name
    svc.setNumber("cache_ttl", 600, "production");
    svc.setBoolean("enable_signup", false, "production");
    svc.save();

    // Fetch, list, update
    Config fetched = client.config().management().get("user_service");
    List<Config> all = client.config().management().list();

    fetched.setDescription("Updated description");
    fetched.save();

    // Delete — either through the active record or the management facade
    fetched.delete();
    // client.config().management().delete("user_service");
}
```

### Runtime API (Live Proxy)

```java
import com.smplkit.config.LiveConfigProxy;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .service("my-service")
        .build()) {
    client.waitUntilReady();

    // Get a live, dict-like, identity-stable proxy over the resolved config.
    // Reads pass through the resolution cache and pick up WebSocket updates
    // automatically — no separate subscribe() step.
    LiveConfigProxy svc = client.config().get("user_service");
    Number ttl = (Number) svc.get("cache_ttl");
    String host = (String) svc.get("database.host");

    // Or project the current values into a typed model (dot-notation keys
    // like "database.host" are expanded into nested objects):
    MyServiceConfig cfg = svc.into(MyServiceConfig.class);
    // shortcut: client.config().get("user_service", MyServiceConfig.class)

    // Item-scoped change listener — fires when a single key changes
    svc.onChange("cache_ttl", event ->
        System.out.println("cache_ttl: " + event.oldValue() + " → " + event.newValue()));

    // Config-scoped or global listeners are also available off the client
    client.config().onChange(event ->
        System.out.println(event.configId() + "." + event.itemKey() + " changed"));

    // Force a re-fetch from the server (rarely needed — WS events drive this)
    client.config().refresh();
}
```

## Logging

The Logging module manages logger configurations and log groups with level
resolution and environment overrides, plus a runtime tier that auto-discovers
existing JUL / SLF4J-Logback / Log4j2 loggers and applies server-managed levels
back onto them.

### Management API

```java
import com.smplkit.LogLevel;
import com.smplkit.logging.Logger;
import com.smplkit.logging.LogGroup;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .service("my-service")
        .build()) {

    // Create a log group
    LogGroup group = client.manage().logGroups.new_("infra", "Infrastructure", null);
    group.setLevel(LogLevel.WARN);
    group.save();

    // Create a logger (managed=true means the SDK runtime should apply the
    // resolved level back to the native logger)
    Logger logger = client.manage().loggers.new_("payment-service", true);
    logger.setLevel(LogLevel.INFO);
    logger.setGroup(group.getId());
    logger.save();

    // Per-environment overrides — second arg is the environment name
    logger.setLevel(LogLevel.WARN, "production");
    logger.setLevel(LogLevel.DEBUG, "staging");
    logger.save();

    // Fetch, list, update
    Logger fetched = client.manage().loggers.get("payment-service");
    List<Logger> all = client.manage().loggers.list();
    List<LogGroup> groups = client.manage().logGroups.list();

    // Force-drain the in-memory discovery buffer (rarely needed — the runtime
    // auto-flushes every 30s and eagerly past 50 entries)
    client.manage().loggers.flush();

    // Delete — either through the active record or the management facade
    logger.delete();
    group.delete();
    // client.manage().loggers.delete("payment-service");
    // client.manage().logGroups.delete("infra");
}
```

### Runtime Tier (Auto-Discovery + Apply Levels)

`install()` scans every logger registered with the JVM's logging frameworks
(JUL is always available; SLF4J-Logback and Log4j2 are picked up if on the
classpath), bulk-registers them with the server, and applies the resolved
levels back to the native loggers. WebSocket events keep the levels live;
`refresh()` forces a re-pull when you've made server-side changes elsewhere.

```java
try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .service("my-service")
        .build()) {

    // Discover all native loggers, register them, apply server-managed levels.
    client.logging().install();

    // Use SLF4J / JUL / Log4j2 normally — levels are now driven by the server.
    LoggerFactory.getLogger("payment-service").info("...");

    // Force a re-fetch + re-apply (no-op if install() hasn't been called)
    client.logging().refresh();

    // Listen for level changes (WS events + manual refresh both fire these)
    client.logging().onChange(event ->
        System.out.println(event.id() + " → " + event.level()));
}
```

The three built-in adapters (JUL, SLF4J–Logback, Log4j2) are registered in `META-INF/services/com.smplkit.logging.adapters.LoggingAdapter` and loaded via Java's standard `ServiceLoader`. To support an additional framework, implement `LoggingAdapter` and either register it explicitly before `install()`:

```java
client.logging().registerAdapter(new MyFrameworkAdapter());
client.logging().install();
```

or ship a `META-INF/services/com.smplkit.logging.adapters.LoggingAdapter` file in your adapter JAR (one FQN per line) so it is discovered automatically when the JAR is on the classpath.

Calling `registerAdapter()` disables auto-loading — only the adapters you register are used.

## Client Configuration

All settings are resolved from four sources, in order of precedence (highest
wins):

1. **Builder methods** — `.apiKey(...)`, `.environment(...)`, etc.
2. **Environment variables** — `SMPLKIT_API_KEY`, `SMPLKIT_ENVIRONMENT`,
   `SMPLKIT_SERVICE`, `SMPLKIT_PROFILE`, `SMPLKIT_BASE_DOMAIN`,
   `SMPLKIT_SCHEME`, `SMPLKIT_DEBUG`, `SMPLKIT_DISABLE_TELEMETRY`.
3. **Configuration file** (`~/.smplkit`) — INI-format with `[common]` plus
   per-profile sections.
4. **Defaults** — built-in SDK defaults.

### Configuration File

The `~/.smplkit` file supports a `[common]` section (applied to all profiles) and named profiles:

```ini
[common]
environment = production
service = my-app

[default]
api_key = sk_api_abc123

[local]
base_domain = localhost
scheme = http
api_key = sk_api_local_xyz
environment = development
debug = true
```

### Constructor Examples

```java
// Use a named profile
SmplClient client = SmplClient.builder().profile("local").build();

// Or configure explicitly
SmplClient client = SmplClient.builder()
    .apiKey("sk_api_...")
    .environment("production")
    .service("my-service")
    .build();
```

For the complete configuration reference, see the [Configuration Guide](https://docs.smplkit.com/getting-started/configuration).

## Error Handling

All SDK exceptions extend `SmplError` (unchecked `RuntimeException`). The
`Smpl` prefix is kept on the base class to avoid colliding with
`java.lang.Error`; subclasses follow Python naming:

```java
import com.smplkit.errors.*;

try {
    Flag<?> flag = client.flags().management().get("nonexistent");
} catch (NotFoundError e) {
    // HTTP 404
} catch (ConflictError e) {
    // HTTP 409
} catch (ValidationError e) {
    // HTTP 422
} catch (TimeoutError e) {
    // Request timed out
} catch (ConnectionError e) {
    // Network error
} catch (SmplError e) {
    System.err.println("Status: " + e.statusCode());
    System.err.println("Body: " + e.responseBody());
}
```

| Exception         | Cause                         |
|-------------------|-------------------------------|
| `NotFoundError`   | HTTP 404 — resource not found |
| `ConflictError`   | HTTP 409 — conflict           |
| `ValidationError` | HTTP 422 — validation error   |
| `TimeoutError`    | Request timed out             |
| `ConnectionError` | Network connectivity issue    |
| `SmplError`       | Base class / any other        |

## Debug Logging

Set the `SMPLKIT_DEBUG` environment variable to enable verbose diagnostic output to stderr:

```bash
export SMPLKIT_DEBUG=1
```

Accepted truthy values: `1`, `true`, `yes` (case-insensitive). All other values (including unset) disable output.

Each line follows the format:

```
[smplkit:{subsystem}] {ISO-8601 timestamp} {message}
```

Subsystems: `lifecycle`, `websocket`, `api`, `flags`, `discovery`, `resolution`, `adapter`, `registration`, `metrics`.

Output bypasses the Java logging framework and writes directly to stderr to avoid interference with level management.

## Documentation

- [Getting Started](https://docs.smplkit.com/getting-started)
- [Java SDK Guide](https://docs.smplkit.com/sdks/java)
- [API Reference](https://docs.smplkit.com/api)

## License

MIT
