# Security reporting

dyltun is experimental. The current development version is the only version receiving fixes; no stable support window or backport policy has been established.

For a suspected vulnerability, use GitHub's **Security → Advisories → Report a vulnerability** when private vulnerability reporting is enabled for the repository. If that option is unavailable, open a minimal issue requesting a private reporting channel without posting exploit details, credentials or captured private traffic. A dedicated security contact has not been configured yet.

Useful details include the affected commit/version, Android API, protocol/framing, a small sanitized reproduction, observed impact and relevant configuration. Keep payloads and packet captures minimal and remove personal data and secrets.

Maintainers should enable private vulnerability reporting after creating the GitHub repository. See [release preparation](docs/RELEASING.md) for repository setup.
