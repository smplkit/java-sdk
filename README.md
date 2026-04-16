# smplkit Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/com.smplkit/smplkit-sdk)](https://central.sonatype.com/artifact/com.smplkit/smplkit-sdk) [![Build](https://github.com/smplkit/java-sdk/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/smplkit/java-sdk/actions) [![Coverage](https://codecov.io/gh/smplkit/java-sdk/branch/main/graph/badge.svg)](https://codecov.io/gh/smplkit/java-sdk) [![License](https://img.shields.io/github/license/smplkit/java-sdk)](LICENSE) [![Docs](https://img.shields.io/badge/docs-docs.smplkit.com-blue)](https://docs.smplkit.com)

The official Java SDK for [smplkit](https://www.smplkit.com) — simple application infrastructure that just works.

## Installation

### Gradle (Kotlin DSL)

```kotlin
implementation("com.smplkit:smplkit-sdk:1.0.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'com.smplkit:smplkit-sdk:1.0.0'
```

### Maven

```xml
<dependency>
    <groupId>com.smplkit</groupId>
    <artifactId>smplkit-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Requirements

- Java 17 or later

## Quick Start

```java
import com.smplkit.SmplClient;

// Option 1: Explicit API key
SmplClient client = SmplClient.builder()
    .apiKey("sk_api_...")
    .environment("production")
    .build();

// Option 2: Environment variable (SMPLKIT_API_KEY)
// export SMPLKIT_API_KEY=sk_api_...
SmplClient client = SmplClient.builder()
    .environment("production")
    .build();

// Option 3: Configuration file (~/.smplkit)
// [default]
// api_key = sk_api_...
SmplClient client = SmplClient.builder()
    .environment("production")
    .build();
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

    // Delete
    client.flags().management().delete("dark-mode");
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

    // Set a context provider (called on every evaluation)
    client.flags().setContextProvider(() -> List.of(
        Context.builder("user", "user-42")
            .attr("plan", "enterprise")
            .attr("country", "US")
            .build()
    ));

    // Evaluate — synchronous, local, zero network after first call
    boolean isDarkMode = darkMode.get();        // true (enterprise rule matches)
    String bannerText = banner.get();           // resolved or default
    Number limit = rateLimit.get();             // resolved or default

    // Override context per-request
    boolean guestDarkMode = darkMode.get(List.of(
        Context.builder("user", "guest-1").attr("plan", "free").build()
    ));

    // Listen for flag changes
    client.flags().onChange(event ->
        System.out.println("Flag changed: " + event.id()));
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

    // Create a config with items
    Config svc = client.config().management().new_("user_service", "User Service", null, null);
    svc.setItems(Map.of(
        "cache_ttl", Map.of("value", 300),
        "enable_signup", Map.of("value", true)
    ));
    svc.save();

    // Add environment overrides
    svc.setEnvironments(Map.of("production", Map.of(
        "values", Map.of("cache_ttl", 600, "enable_signup", false)
    )));
    svc.save();

    // Fetch, list, update
    Config fetched = client.config().management().get("user_service");
    List<Config> all = client.config().management().list();

    fetched.setDescription("Updated description");
    fetched.save();

    // Delete
    client.config().management().delete("user_service");
}
```

### Runtime API (Prescriptive Access)

```java
try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .build()) {

    // Resolve a config as a flat key→value map (lazy init, environment-aware)
    Map<String, Object> values = client.config().get("user_service");
    int ttl = (int) values.get("cache_ttl");

    // Resolve into a typed model
    MyServiceConfig cfg = client.config().get("user_service", MyServiceConfig.class);

    // Subscribe to live updates
    client.config().subscribe("user_service", values2 ->
        System.out.println("Config updated: " + values2));
}
```

## Logging

The Logging module manages logger configurations and log groups with level resolution and environment overrides.

### Management API

```java
import com.smplkit.LogLevel;
import com.smplkit.logging.Logger;
import com.smplkit.logging.LogGroup;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .environment("production")
        .build()) {

    // Create a log group
    LogGroup group = client.logging().management().newGroup("infra", "Infrastructure", null);
    group.setLevel(LogLevel.WARN);
    group.save();

    // Create a logger
    Logger logger = client.logging().management().new_("payment-service", "Payment Service", true);
    logger.setLevel(LogLevel.INFO);
    logger.setGroup(group.getId());
    logger.save();

    // Environment overrides
    logger.setEnvironmentLevel("production", LogLevel.WARN);
    logger.setEnvironmentLevel("staging", LogLevel.DEBUG);
    logger.save();

    // Fetch, list, update
    Logger fetched = client.logging().management().get("payment-service");
    List<Logger> all = client.logging().management().list();
    List<LogGroup> groups = client.logging().management().listGroups();

    // Delete
    client.logging().management().delete("payment-service");
    client.logging().management().deleteGroup("infra");
}
```

## API Key Resolution

The API key is resolved using the following priority:

1. **Explicit argument:** `.apiKey(...)` on the builder.
2. **Environment variable:** `SMPLKIT_API_KEY`.
3. **Configuration file:** `api_key` under `[default]` in `~/.smplkit`:

```ini
[default]
api_key = sk_api_your_key_here
```

If none are set, the SDK throws `SmplException` listing all three methods.

## Error Handling

All SDK exceptions extend `SmplException` (unchecked `RuntimeException`):

```java
import com.smplkit.errors.*;

try {
    Flag<?> flag = client.flags().management().get("nonexistent");
} catch (SmplNotFoundException e) {
    // HTTP 404
} catch (SmplConflictException e) {
    // HTTP 409
} catch (SmplValidationException e) {
    // HTTP 422
} catch (SmplTimeoutException e) {
    // Request timed out
} catch (SmplConnectionException e) {
    // Network error
} catch (SmplException e) {
    System.err.println("Status: " + e.statusCode());
    System.err.println("Body: " + e.responseBody());
}
```

| Exception                 | Cause                         |
|---------------------------|-------------------------------|
| `SmplNotFoundException`   | HTTP 404 — resource not found |
| `SmplConflictException`   | HTTP 409 — conflict           |
| `SmplValidationException` | HTTP 422 — validation error   |
| `SmplTimeoutException`    | Request timed out             |
| `SmplConnectionException` | Network connectivity issue    |
| `SmplException`           | Any other SDK error           |

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

Subsystems: `lifecycle`, `websocket`, `api`, `discovery`, `resolution`, `adapter`, `registration`.

Output bypasses the Java logging framework and writes directly to stderr to avoid interference with level management.

## Documentation

- [Getting Started](https://docs.smplkit.com/getting-started)
- [Java SDK Guide](https://docs.smplkit.com/sdks/java)
- [API Reference](https://docs.smplkit.com/api)

## License

MIT
