# Changelog

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
