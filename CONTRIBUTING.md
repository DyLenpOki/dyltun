# Contributing

English and Chinese reports and pull requests are welcome. Read the [current limits](docs/IMPLEMENTATION.md), [API contract](docs/API.md) and [license status](README.md#license-status) before preparing a change.

## Report a problem

Use the bug-report form and include the dyltun version/commit, Android API/device, host toolchain, relevant VPN configuration and a small reproduction. Distinguish a failure to connect upstream from a failure while parsing or modifying HTTP. Remove credentials, cookies, tokens and private traffic from reports. For vulnerabilities, use [SECURITY.md](SECURITY.md).

## Prepare a change

- Keep protocol/runtime code in Kotlin and build scripts in Kotlin DSL. YAML/XML remain configuration formats.
- Use explicit imports in source code, tests and documentation examples; do not use wildcard imports.
- Keep packet/TCP state on the packet loop and application callbacks on the Hook executor. Preserve FD, buffer and socket ownership.
- Keep public changes small and update the API guide, examples and Unreleased changelog when behavior changes.
- Add a regression test for a bug when practical. Use deterministic local servers and clocks; JVM tests should not require internet access.
- HTTP test servers must recognize Content-Length/chunked framing. A test that waits for TCP EOF to complete an ordinary request can hide an incorrect early FIN.
- Keep machine paths, signing keys, traffic captures and generated packages out of the repository.

## Validate

Use JDK 21 and the checked-in Wrapper. On Linux/macOS:

```shell
bash ./gradlew --no-daemon --continue :dyltun:testDebugUnitTest :dyltun:lintDebug :dyltun:assembleDebugAndroidTest :dyltun:publishReleasePublicationToMavenLocal :sample:lintDebug :sample:assembleDebug
```

On Windows, use `.\gradlew.bat` with the same tasks. Device-dependent changes also need the relevant Android tests on a connected device:

```shell
bash ./gradlew :dyltun:connectedDebugAndroidTest
```

These Android tests use socketpairs and real platform networking; they do not replace VPN consent, packet forwarding, network-switching or long-running device verification. Describe what was tested and what remains unverified in the PR. Keep review discussion respectful and focused on behavior and evidence.

See [BUILDING.md](docs/BUILDING.md) for SDK setup and reports. CI runs JVM tests, lint and package builds; it does not run a device/emulator test job.
