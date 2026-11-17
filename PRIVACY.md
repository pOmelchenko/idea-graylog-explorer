# Privacy policy

Effective date: 17 November 2026.

Graylog Explorer is maintained by Pavel Omelchenko. Privacy questions: **p.Omelchenko@gmail.com**.

## Destinations and requests

The plugin connects to the Graylog API server that you configure. Requests can include an access token, query text, stream IDs, source names, field names, message IDs and time ranges. The configured server processes these requests under its own policies and may record them in its logs.

Queries run when you select/open a configured connection or initiate a search. Live mode repeats searches. When enabled, the full-period exception index reads the selected search period in the background; Sources aggregation reads the corresponding query and period. You can disable Live and Code marks, cancel the index scan, or remove a connection.

**Search in Graylog** sends the text you explicitly select as a search query. This can include source-code text if you select it. **Open Graylog** opens the configured web UI in your browser, including event-identifying query parameters when needed.

The plugin has no maintainer-operated backend, analytics, advertising SDK or telemetry endpoint. It does not send project files or log contents to the maintainer or GitHub. JetBrains IDE services, installed plugins and your browser have their own data policies.

## Received data

Graylog responses may contain personal or sensitive information, including exception messages, request identifiers, user identifiers and values embedded in messages. The plugin displays these responses in the IDE. Selected-event values may also appear as temporary editor hints.

Log pages, aggregate results and bounded exception examples are held in memory. The plugin does not implement a persistent log archive or write fetched log messages into project source files. IDE diagnostics, operating-system memory handling and manually shared screenshots or reports are outside this guarantee.

## Local settings and credentials

- Access tokens are stored through the IDE Password Safe. Its persistence and encryption depend on your IDE and operating-system configuration.
- Connection names, URLs, default search options, highlight preferences and source-color overrides are stored in IDE-level plugin settings.
- Source mappings, project overrides, saved searches, the last query and UI state are stored in the project's local IDE workspace. A query or saved search can itself contain identifying information.
- Local source files and PHP PSI are read for path mapping and source-backed hints. Entire source files are not uploaded by those features.

Removing a connection removes its credential when no other configured connection references it. Legacy credential entries may be retained to allow migration of projects not yet opened. Uninstalling the plugin does not guarantee deletion of IDE settings or credential entries.

## Sharing and changes

Only submit synthetic or redacted logs to the public issue tracker. Do not include access tokens, real user data or private server addresses. Report security issues using [SECURITY.md](SECURITY.md).

Changes to data handling will be documented in this file and the changelog. The plugin's configured requests are initiated for the user-requested log exploration features; this policy does not determine the legal basis or permissions for data already stored in your Graylog installation.
