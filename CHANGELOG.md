# Changelog

This file records software changes. There are no published releases yet; the current build version is `0.1.0-SNAPSHOT`. The design baseline version is separate and is recorded in [design history](docs/DESIGN_HISTORY.md).

## Unreleased

### Added

- Reusable `dyltunConfig {}` and `VpnService.dyltun(fd, config)`, retaining all existing startup forms.
- Value-action TLS domain-collection and MQTT routes with automatic default inspectors and explicit-inspector precedence; lambda custom rules alongside `ConnectionRule`.
- Kotlin Android library and runnable `VpnService` sample under `com.dylenpoki.dyltun`.
- IPv4 TCP/UDP forwarding, bounded fragment reassembly and protected upstream sockets.
- Plaintext HTTP/1.1 hooks, local responses, optional upstream-error recovery, identity/gzip body changes and bounded streaming/rejection policies.
- TLS ClientHello/SNI inspection, domain-collection matching, MQTT CONNECT inspection and extensible protocol/routing interfaces.
- Session lifecycle/events, bounded queues and resource budgets.
- JVM and Android tests, API/design/build guides, contribution templates and CI configuration.

### Changed

- Split HTTP connection orchestration, single-exchange handling and shared response output into separate internal classes, with explicit exchange results and upstream-recovery phases. Packet/TCP algorithms and thread ownership remain unchanged.

### Fixed

- Ordinary HTTP request completion no longer half-closes the upstream TCP socket before receiving the response. This prevents premature termination by HTTP frontends that return 400 or close before a complete response line.
- HTTP request/response test peers now use message framing instead of requiring TCP EOF to receive a request.
- HEAD rewrite behavior, upgrade framing/negotiation protection and safe Body REJECT responses.

### Status

- Experimental APIs; public Maven distribution and a stable release are pending.
- Verification details and unsupported behavior are tracked in [implementation status](docs/IMPLEMENTATION.md) and the [roadmap](docs/ROADMAP.md).
- No project license has been selected.
