# smplkit Java SDK

The official Java SDK for [smplkit](https://docs.smplkit.com) — configuration management for modern applications.

## Requirements

- Java 17 or later

## Installation

### Gradle (Kotlin DSL)

```kotlin
implementation("com.smplkit:smplkit-sdk:0.0.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'com.smplkit:smplkit-sdk:0.0.0'
```

### Maven

```xml
<dependency>
    <groupId>com.smplkit</groupId>
    <artifactId>smplkit-sdk</artifactId>
    <version>0.0.0</version>
</dependency>
```

## Quick start

```java
import com.smplkit.SmplkitClient;
import com.smplkit.config.Config;
import com.smplkit.config.CreateConfigParams;

import java.util.List;
import java.util.Map;

// Build the client (implements AutoCloseable)
try (SmplkitClient client = SmplkitClient.builder()
        .apiKey("sk_api_...")
        .build()) {

    // Get a config by ID
    Config config = client.config().get("550e8400-e29b-41d4-a716-446655440000");

    // Get a config by key
    Config byKey = client.config().getByKey("user_service");

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
    client.config().delete("550e8400-e29b-41d4-a716-446655440000");
}
```

## Error handling

All SDK exceptions extend `SmplException` (an unchecked `RuntimeException`):

```java
import com.smplkit.errors.*;

try {
    Config config = client.config().get("nonexistent-id");
} catch (SmplNotFoundException e) {
    // HTTP 404 — resource not found
} catch (SmplConflictException e) {
    // HTTP 409 — conflict (e.g., deleting config with children)
} catch (SmplValidationException e) {
    // HTTP 422 — validation error
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

## Configuration

```java
SmplkitClient client = SmplkitClient.builder()
    .apiKey("sk_api_...")
    .baseUrl("https://config.smplkit.com")  // default
    .timeout(Duration.ofSeconds(30))         // default
    .build();
```

## Documentation

Full documentation is available at [docs.smplkit.com](https://docs.smplkit.com).

## License

MIT
