# Changelog

## 0.13.0 — 2026-11-17

- Split Explorer into connections, events and event-details views with independent source catalog and period-index controllers.
- Introduce Graylog API and background-task ports; add deterministic cache, cancellation, retry and stale-completion tests.
- Use Kotlin for the controllers, native Settings forms and connection dialog. Retain persistent settings formats and plugin IDs.
- Use one Gradle Java/Kotlin build; the Python command delegates to Gradle and UI tests consume the actual packaged JARs.
- Separate Password Safe update/rollback planning from Settings UI; preserve connection/profile order and invalidate connection tests on selection changes.
- Remove obsolete page-only grouping, unreachable renderer branches and duplicated smoke reflection helpers. Reuse formatters and avoid repeated exception decoding during indexing.
- Fix pagination integer overflow, source-filter self replacement and HTML interpretation in plain log cells and tooltips.

## 0.12.1 — 2026-11-17

- Verify restartless updates and repeated dynamic reloads with an independent test controller and an unmodified production JAR.
- Document dynamic update support and the IDE fallback when unloading fails.

- Declare incompatibility with the old `dev.graylog.jetbrains` prototype so IDE startup does not register the same Graylog tool window and action twice.
- Add an isolated UI fixture that keeps the prototype installed but disabled while testing the public plugin.

## 0.12.0 — 2026-11-17

First public-source preparation release.

- Publish the project metadata, MIT license, privacy policy and contributor documentation.
- Add reproducible Gradle builds, archive validation, Plugin Verifier and optional signing/publication tasks.
- Add GitHub CI and a separate manually invoked Marketplace publication workflow.
- Replace application-specific exception fixtures with synthetic demo paths and values.
- Use the public plugin ID `dev.omelchenko.graylogexplorer`.

This release includes the features developed in the local prototype: native log exploration, multi-source filters in the connection tree and a movable sortable popup, complete-period exception indexing, structured exception chains, source mappings, IDE/project settings and conservative PHP logged-value inlays.

## 0.11.0 — 2026-11-17

- Show source colors, counts and multi-selection in the connection tree.
- Share source filtering and server aggregation between tree and popup.
- Support title dragging, persisted popup size and numeric column sorting.

## 0.10.0 — 2026-11-16

- Map selected-event string values to PHP exception arguments using local PSI.
- Keep ordinary line messages when variable mapping cannot be established.
- Clear variable hints on source edits and expose a separate settings option.

## 0.9.0 — 2026-11-16

- Add server-wide source aggregation and multi-source filtering across the selected period.
- Preserve complete-period exception indexing independently of the loaded page.
