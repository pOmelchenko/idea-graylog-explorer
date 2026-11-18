# Changelog

## 0.16.2

- Carry PR #3 review fixes into the Kotlin decoder: preserve explicit throw origins and correctly unwind .NET inner stacks, with the same parser/editor/export regression fixtures.

## 0.16.1

- Resize the event message above the inspector tabs with a native divider and remember the chosen height, making long SQL and multiline log messages readable.

## 0.16.0

- Join adjacent visible events from the same source into continuous color segments, retaining rounded ends and the splitter inset. Respect sorting, filtering, selection and source-color overrides; never merge distinct sources merely because their colors match.
- Move exception parsers/models, Explorer views, connection transactions, editor decorations/PHP hints and the panel to Kotlin. Keep persistent XML beans, credential keys, extension IDs and Java callers compatible.
- Add adjacency/raster contracts and actual IDE table-renderer checks for source segments, including sorted and filtered view order.

## 0.15.1

- Preserve explicit exception file/line metadata when a text stack starts at a caller; keep caller frames and the original exception text.
- Restore .NET parent frames after inner-exception boundaries, including nested causes with inline headers.
- Cover every cause's editor location and full-period export with shared runtime fixtures and explicit-origin regressions.

## 0.15.0

- Parse JVM, Python, V8 JavaScript/TypeScript, Go, Ruby, .NET and Rust text stack frames, alongside PHP; retain raw text and normalize exception causes.
- Add an ECS object/dotted-field schema adapter with full-period CSV/JSON restoration and immutable log documents with a single cached exception-chain decode.
- Share operation-specific keys across Settings effects and Sources/index reuse; labels and Web URLs no longer invalidate results during paging.
- Use one connection/credential transaction from Settings and Explorer, including obsolete-key cleanup and rollback on concurrent Settings changes.
- Extract stream request ownership and cross-operation coordination into Kotlin controllers.
- Replace private UI-field reflection with semantic component names, read-only view snapshots and actual actions/table models.


## 0.14.0 — 2026-11-17

- Move submitted search, cancellation, paging and exception-example display into a Kotlin SearchController; retain the last successful page on failure.
- Capture immutable effective connection, transport and scan settings for each operation; validate imported values at the execution boundary.
- Publish scoped Settings changes and classify their actual effect on the active connection. Appearance and Live interval retain results; mapping/sample changes rebuild the index; network changes retain the submitted window and query draft.
- Update editor decorations by file/location and retain unchanged markers. Keep asynchronous PHP-value ownership and stale-result protection per editor, including invalidation when a dependency source changes.
- Migrate both Settings pages to Kotlin DSL bindings over detached drafts. Apply project settings atomically while preserving concurrent workspace history and saved searches.
- Add actual IDE Settings-effect checks and a 10,000-location refresh benchmark with marker identity/work-count assertions.

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
