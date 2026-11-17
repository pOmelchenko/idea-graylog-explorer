# Contributing

Open an issue describing the problem, expected behavior, plugin version and IDE build. Share minimal synthetic logs rather than production data. For vulnerabilities, use [SECURITY.md](SECURITY.md).

## Development

Use JDK 25 and the Gradle wrapper. SDK and plugin versions are pinned in `gradle.properties`.

```bash
./gradlew check buildPlugin verifyPluginStructure verifyPlugin
```

An installed PhpStorm can be used with `-PideHome=/path/to/phpstorm`. The Python build command delegates to Gradle; it is not a separate compiler or packager. Once dependencies are cached, `python3 build.py --offline --ide-home /path/to/phpstorm` can build offline. The isolated Xvfb smoke runner consumes the packaged runtime JARs; see the README.

The core tests are executable Java integration tests (`CoreTests`), not a JUnit suite. `check` invokes `integrationTest`; the empty standard `test` task is intentionally disabled. Tests use loopback HTTP fixtures and do not need a real Graylog server.

## Changes

- Keep application controllers independent of IntelliJ/Swing. Put HTTP and IDE-threading concerns behind the existing ports, and use deterministic controller tests for cancellation and stale completions.
- Keep IDE UI changes native and respect light/dark themes and editor ownership.
- Do not perform network or blocking credential access on the UI thread.
- Preserve query/time/stream scope across source filters, pagination and exception scans.
- Never present an incomplete period export as a complete exception index.
- Never infer a variable binding solely from an exception message; retain the line hint when source-backed mapping is ambiguous.
- Add focused tests for changed behavior, run the relevant UI smoke scenario when needed, and update the changelog.
- Keep API tokens, signing keys, local SDK paths, build output and real logs out of Git.

Use conventional commit messages such as `fix: preserve source filter when paging`. Pull requests should explain the resulting behavior and the checks performed.

Contributions are submitted under the project's [MIT license](LICENSE).

A fresh EAP SDK may show the JetBrains user agreement before opening the test project. Accept it only with the repository owner’s authorization; a timeout on that screen is not a successful UI run. The runner accepts only an explicitly supplied exact version (`--accept-eap-agreement 3.1`) and writes it to isolated Java preferences. The repository owner authorized that version for CI; a different bundled version fails before recording acceptance.
