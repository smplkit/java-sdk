# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial SDK with `SmplkitClient` builder and `ConfigClient`
- Config CRUD operations: get, getByKey, list, create, delete
- Typed exception hierarchy: `SmplException`, `SmplNotFoundException`, `SmplConflictException`, `SmplValidationException`, `SmplConnectionException`, `SmplTimeoutException`
- JSON:API envelope handling
- Bearer token authentication
- JUnit 5 test suite with mocked HTTP client
