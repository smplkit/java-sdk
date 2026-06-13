# smplkit SDK Examples

Runnable examples demonstrating the [smplkit Java SDK](https://github.com/smplkit/java-sdk).

> **Note:** These examples require valid smplkit credentials and a live environment — they are not self-contained demos.

## Prerequisites

1. Java 17+
2. A valid smplkit API key, provided via one of:
   - `SMPLKIT_API_KEY` environment variable
   - `~/.smplkit` configuration file (see SDK docs)
3. At least two environments configured (e.g., `staging`, `production`).

## Structure

There is **one** client per product, reached from `SmplClient` (and
`AsyncSmplClient`): `client.config`, `client.flags`, `client.logging`,
`client.audit`, and `client.jobs`. Management/CRUD lives directly on each
product client — `client.config.new_/get/list/delete`, the `client.flags.new*`
builders, and `client.logging.loggers` / `client.logging.logGroups`. Each
product can also be used via a standalone client (`AuditClient`, `JobsClient`).

Config/Flags/Logging keep a **management** + **runtime** showcase pair (the two
sides — CRUD vs. evaluation — are genuinely different). Audit and Jobs have **one**
showcase each — they have no runtime/management split (one client, full surface).

| Product | Management | Runtime | Setup |
|---------|-----------|---------|-------|
| **Flags** | `FlagsManagementShowcase.java` | `FlagsRuntimeShowcase.java` | `setup/FlagsRuntimeSetup.java` |
| **Config** | `ConfigManagementShowcase.java` | `ConfigRuntimeShowcase.java` | `setup/ConfigRuntimeSetup.java` |
| **Logging** | `LoggingManagementShowcase.java` | `LoggingRuntimeShowcase.java` | `setup/LoggingManagementSetup.java` |
| **Audit** | `AuditShowcase.java` — single; events, discovery, categories, and forwarders | | _(none)_ |
| **Jobs** | `JobsShowcase.java` — single; job CRUD, runs, usage | | _(none)_ |

**Management showcases** demonstrate the programmatic CRUD API directly on the
product client: creating resources with `new*()` + `save()`, fetching with
`get(id)`, listing, mutating, and deleting. No `install()` needed — management
methods are stateless HTTP calls.

**Runtime showcases** demonstrate the developer experience: a per-product
`install()` (`client.config` connects lazily / `client.flags` connects lazily /
`client.logging.install()`), local evaluation, live updates via WebSocket, and
change listeners. Each runtime showcase imports its setup helper to create
server-side state, then cleans up after itself.

## Running

```bash
# Single-client products (Audit, Jobs — full surface, no runtime/management split)
make audit_showcase
make jobs_showcase

# Management / CRUD (directly on client.config / client.flags / client.logging)
make flags_management_showcase
make config_management_showcase
make logging_management_showcase

# Runtime (imports its setup helper automatically)
make flags_runtime_showcase
make config_runtime_showcase
make logging_runtime_showcase
```

Each target runs `./gradlew :examples:run -PmainClass=com.smplkit.examples.<ClassName>`.
Each script creates temporary resources, exercises all SDK features, then cleans up after itself.
