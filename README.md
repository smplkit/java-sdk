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
    .build();

// Option 2: Environment variable (SMPLKIT_API_KEY)
// export SMPLKIT_API_KEY=sk_api_...
SmplClient client2 = SmplClient.create();

// Option 3: Configuration file (~/.smplkit)
// [default]
// api_key = sk_api_...
SmplClient client3 = SmplClient.create();
```

```java
import com.smplkit.SmplClient;
import com.smplkit.config.Config;
import com.smplkit.config.CreateConfigParams;

import java.util.List;
import java.util.Map;

// Build the client (implements AutoCloseable)
try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .build()) {

    // Get a config by key
    Config config = client.config().getByKey("user_service");

    // List all configs
    List<Config> configs = client.config().list();

    // Create a config
    Config newConfig = client.config().create(
        CreateConfigParams.builder("My Service")
            .key("my_service")
            .description("Configuration for my service")
            .values(Map.of("timeout", 30, "retries", 3))
            .build()
    );

    // Delete a config
    client.config().delete(newConfig.id());
}
```

## Flags

The Flags module provides feature flag management and a prescriptive runtime tier with typed flag handles, local evaluation, and real-time updates.

### Management API

```java
import com.smplkit.flags.*;

try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .build()) {

    // Create a boolean flag
    FlagResource flag = client.flags().create(
        CreateFlagParams.builder("dark-mode", "Dark Mode", FlagType.BOOLEAN)
            .defaultValue(false)
            .description("Controls the dark mode UI theme.")
            .build());

    // Add a targeting rule
    flag = flag.addRule(new Rule("Enable for enterprise")
        .environment("production")
        .when("user.plan", "==", "enterprise")
        .serve(true)
        .build());

    // Update a flag
    flag = flag.update(UpdateFlagParams.builder()
        .description("Updated description")
        .build());

    // List and delete
    List<FlagResource> flags = client.flags().list();
    client.flags().delete(flag.id());
}
```

### Runtime Tier (Typed Handles)

```java
try (SmplClient client = SmplClient.builder()
        .apiKey("sk_api_...")
        .build()) {

    // 1. Declare typed flag handles (at startup)
    FlagHandle<Boolean> darkMode = client.flags().boolFlag("dark-mode", false);
    FlagHandle<String> banner = client.flags().stringFlag("banner-text", "Welcome!");
    FlagHandle<Number> rateLimit = client.flags().numberFlag("rate-limit", 100);
    FlagHandle<Object> uiConfig = client.flags().jsonFlag("ui-config",
        Map.of("theme", "light"));

    // 2. Set a context provider (called on every evaluation)
    client.flags().setContextProvider(() -> List.of(
        Context.builder("user", "user-42")
            .attr("plan", "enterprise")
            .attr("country", "US")
            .build()
    ));

    // 3. Connect to an environment (fetches flags, starts WebSocket)
    client.flags().connect("production");

    // 4. Evaluate — synchronous, local, zero network
    boolean isDarkMode = darkMode.get();           // true (enterprise rule matches)
    String bannerText = banner.get();              // resolved value or default
    Number limit = rateLimit.get();                // resolved value or default

    // 5. Override context per-request
    Context guest = Context.builder("user", "guest-1")
        .attr("plan", "free")
        .build();
    boolean guestDarkMode = darkMode.get(List.of(guest));  // false

    // 6. Listen for changes
    darkMode.onChange(event ->
        System.out.println("Flag changed: " + event.key()));

    // 7. Disconnect when done
    client.flags().disconnect();
}
```

### Context Types

```java
// Create a context type for targeting
client.flags().createContextType("user", Map.of(
    "name", "User",
    "attributes", Map.of(
        "plan", Map.of("type", "string"),
        "country", Map.of("type", "string")
    )
));

// List and delete context types
List<Map<String, Object>> types = client.flags().listContextTypes();
client.flags().deleteContextType(typeId);
```

### Stateless Evaluation (HTTP)

For serverless or one-off checks without `connect()`:

```java
Object result = client.flags().evaluate("dark-mode", "production",
    List.of(Context.builder("user", "user-42")
        .attr("plan", "enterprise")
        .build()));
```

## Configuration

The API key is resolved using the following priority:

1. **Explicit argument:** Call `.apiKey()` on the builder or use `SmplClient.create(apiKey)`.
2. **Environment variable:** Set `SMPLKIT_API_KEY`.
3. **Configuration file:** Add `api_key` under `[default]` in `~/.smplkit`:

```ini
# ~/.smplkit

[default]
api_key = sk_api_your_key_here
```

If none of these are set, the SDK throws `SmplException` with a message listing all three methods.

```java
import java.time.Duration;

SmplClient client = SmplClient.builder()
    .apiKey("sk_api_...")
    .timeout(Duration.ofSeconds(30))  // default
    .build();
```

## Error Handling

All SDK exceptions extend `SmplException` (an unchecked `RuntimeException`):

```java
import com.smplkit.errors.*;

try {
    Config config = client.config().get("nonexistent-id");
} catch (SmplNotFoundException e) {
    // Resource not found
} catch (SmplConflictException e) {
    // Conflict (e.g., deleting config with children)
} catch (SmplValidationException e) {
    // Validation error
} catch (SmplTimeoutException e) {
    // Request timed out
} catch (SmplConnectionException e) {
    // Network connectivity issue
} catch (SmplException e) {
    // Any other SDK error
    System.err.println("Status: " + e.statusCode());
    System.err.println("Body: " + e.responseBody());
}
```

| Exception                  | Cause                        |
|----------------------------|------------------------------|
| `SmplNotFoundException`    | HTTP 404 — resource not found |
| `SmplConflictException`   | HTTP 409 — conflict           |
| `SmplValidationException` | HTTP 422 — validation error   |
| `SmplTimeoutException`    | Request timed out             |
| `SmplConnectionException` | Network connectivity issue    |
| `SmplException`           | Any other SDK error           |

## Documentation

- [Getting Started](https://docs.smplkit.com/getting-started)
- [Java SDK Guide](https://docs.smplkit.com/sdks/java)
- [API Reference](https://docs.smplkit.com/api)

## License

MIT
