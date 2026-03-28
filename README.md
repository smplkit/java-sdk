# smplkit Java SDK

The official Java SDK for [smplkit](https://smplkit.com) — simple application infrastructure for developers.

## Installation

> **Note:** The package is not yet available on Maven Central — coming soon. The coordinates below will work once publishing is live.

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

## Configuration

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
