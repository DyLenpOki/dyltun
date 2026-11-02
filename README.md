# dyltun

English | [简体中文](README.zh-CN.md)

A Kotlin library for processing Android `VpnService` TUN traffic, with IPv4 TCP/UDP forwarding, plaintext HTTP/1.1 hooks and extensible protocol routing.

Created by GPT 6.1 Sol in extra-high reasoning mode.

**Experimental: `0.1.0-SNAPSHOT`.** APIs may change between snapshots. Runtime verification currently focuses on Android API 34; multi-version device compatibility and long-running performance are still being evaluated. See [implementation status](docs/IMPLEMENTATION.md) for evidence and limits.

## Features

- IPv4 TCP/UDP forwarding through sockets protected by `VpnService.protect()`.
- HTTP/1.1 request and response hooks for URL, headers and bounded body changes.
- Local responses with `flow.response = HttpResponse.make(...)`, plus an optional upstream-error hook.
- Per-direction encoded/decoded body limits, buffering deadlines and a session-wide body budget.
- TLS ClientHello/SNI matching with domain collections and MQTT CONNECT-based redirection.
- Protocol inspection and routing interfaces for application-specific extensions.

The library owns packet processing and forwarding. The host application owns VPN consent, the foreground service, TUN configuration and application selection.

## Build and try the sample

Use **JDK 21**, Android SDK Platform **36** and Build Tools **36.0.0**. The checked-in Gradle Wrapper uses Gradle 9.4.1 and AGP 9.2.1 with built-in Kotlin support. Java source/bytecode compatibility is 17; the library's minimum Android API is 21.

On Linux/macOS:

```shell
bash ./gradlew :dyltun:testDebugUnitTest :dyltun:lintDebug :sample:lintDebug :sample:assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :dyltun:testDebugUnitTest :dyltun:lintDebug :sample:lintDebug :sample:assembleDebug
```

Install `sample/build/outputs/apk/debug/sample-debug.apk`, grant VPN consent and use the sample's test buttons. It captures only `198.18.0.1/32`, provides `/local-health` locally, and redirects TCP/UDP DNS to `1.1.1.1:53`.

See [build instructions](docs/BUILDING.md) for SDK setup, device tests and troubleshooting. The [CI workflow](.github/workflows/ci.yml) checks JVM tests, lint, release packaging, the sample and the Android test APK; device tests run separately.

## Add the library to another application

**There is no public Maven release yet.** Publish the checkout to your local Maven repository first:

```shell
bash ./gradlew :dyltun:publishReleasePublicationToMavenLocal
```

On Windows, use `.\gradlew.bat` for the same task. In the host application's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content { includeModule("com.dylenpoki", "dyltun") }
        }
        google()
        mavenCentral()
    }
}
```

In the host application's module:

```kotlin
dependencies {
    implementation("com.dylenpoki:dyltun:0.1.0-SNAPSHOT")
}
```

The host must use the same user's local Maven repository and a compiler compatible with the library's Kotlin metadata. An independent host has been verified with Kotlin 2.2.10. See the [API guide](docs/API.md) for source integration, complete service setup and the public API.

## Configure a session

Build reusable configuration separately, then start a session inside a `VpnService` subclass after obtaining consent, starting the foreground service and establishing the TUN FD:

```kotlin
import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.dyltun
import com.dylenpoki.dyltun.dyltunConfig

val config = dyltunConfig {
    hooks = object : HttpHooks {
        override fun request(flow: HttpFlow) {
            if (flow.request.path == "/local-health") {
                flow.response = HttpResponse.make(
                    statusCode = 200,
                    content = """{"status":"ok"}""",
                    headers = HttpHeaders.of("Content-Type" to "application/json; charset=utf-8")
                )
            }
        }

        override fun response(flow: HttpFlow) {
            flow.response?.headers?.set("X-Dyltun", "enabled")
        }
    }

    bodyLimits = BodyLimits(
        request = BodyLimit(512L * 1024, 2L * 1024 * 1024),
        response = BodyLimit(2L * 1024 * 1024, 8L * 1024 * 1024),
        maxTotalBufferedBytes = 32L * 1024 * 1024
    )

    routing {
        tlsSni(
            domains = listOf("blocked.example.com", "blocked.example.org"),
            includeSubdomains = true,
            action = RoutingDecision.Reset
        )
        mqtt(action = RoutingDecision.Redirect(Endpoint("127.0.0.1", 1883)))
    }
}

// Inside VpnService, once fd has been established:
session = dyltun(fd, config)
```

The root configuration offers optional `hooks`, `bodyLimits`, `protocols` and `routing`. These value-action TLS/MQTT rules supply default inspectors; explicit `protocols { install(...) }` registrations take precedence. Use `rule { context -> ... }` for custom routing. The existing inline `dyltun(fd) { ... }`, nullable Hooks overload and action-lambda routing remain supported. Use `dyltun(fd)` for forwarding without HTTP hooks. MQTT redirection requires a broker already listening at the configured endpoint; the sample does not include a broker.

The returned session owns the FD. Call `session.close()` when stopping, revoking or destroying the VPN; use `awaitClosed()` from a coroutine if full cleanup must complete. A synchronous configuration failure leaves the FD with the caller. See the compiled [full configuration example](sample/src/main/kotlin/com/dylenpoki/dyltun/sample/FullApiExample.kt) and runnable [VPN service](sample/src/main/kotlin/com/dylenpoki/dyltun/sample/DemoVpnService.kt).

## Current limits

| Area | Current boundary |
|---|---|
| IP | IPv4 forwarding; IPv6, complete ICMP/PMTU handling and network migration remain unfinished |
| HTTP | Plaintext HTTP/1.1; no HTTPS decryption or HTTP/2/3 content hooks; each transaction opens its own upstream socket |
| Body | Bounded identity/gzip buffering; overflow can stream the original content or reject it |
| TLS inspection | Visible ClientHello/SNI only; ECH, missing SNI and QUIC are not reliably identified |
| Resources | Currently 64 active TCP transports and 128 UDP flows; additional queue and memory limits are documented |
| Android | API 21+ declared; keep the default nonblocking TUN FD or set `setBlocking(false)` on API 21–29 |

## Documentation

The detailed guides are currently in Chinese; Kotlin API names and examples are shared between both READMEs.

| Document | Purpose |
|---|---|
| [API](docs/API.md) | Integration, HTTP models, body limits, routing, sessions and extension interfaces |
| [Building](docs/BUILDING.md) | Toolchain, build/test commands and sample usage |
| [Implementation status](docs/IMPLEMENTATION.md) | Implemented behavior, resource limits and verification |
| [Design](docs/DESIGN.md) | Architecture and runtime contracts |
| [Flow diagrams](docs/FLOW.md) | Startup, packet forwarding, HTTP transactions and ownership |
| [Refactoring](docs/REFACTORING.md) | Compatibility constraints and the simplification results |
| [Roadmap](docs/ROADMAP.md) | Remaining work and acceptance criteria |
| [Changelog](CHANGELOG.md) | Software changes and unreleased fixes |
| [Design history](docs/DESIGN_HISTORY.md) | Earlier design decisions |
| [Release guide](docs/RELEASING.md) | Repository setup and release preparation |

## Project layout and contributions

`dyltun/` contains the Android library and its JVM/device tests, `sample/` contains the runnable host, `docs/` contains the guides, and `.github/` contains CI and issue/PR templates.

Issues and contributions may be written in English or Chinese. Include a version/commit, environment, minimal reproduction and redacted logs. See [contribution guidelines](CONTRIBUTING.md) and [security reporting](SECURITY.md).

## License status

No project license has been selected yet, and no project `LICENSE` file is included. The Gradle Wrapper retains its own license notices.
