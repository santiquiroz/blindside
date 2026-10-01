# Plan 05 review fixes: cross-plan notes for plan 06

These notes come from the review of `feat/android-shared` (plan 05). Each one changes something plan 06 consumes from `android-shared`. Plan 06 must follow them before it merges.

## 1. `/settings`: adopt the watch's settings before publishing anything

- **What changed in plan 05.** A watch installed before plan 05 has no `shared_updated_ms` key. `settingsFrom` now reads such settings with `MIGRATED_STAMP_MS` (1) instead of 0, whenever the stored prefs hold a hand. Every older build stored the hand on every write. The next write persists the 1. The watch then publishes its hand, mounts, signs and posture. A fresh phone (stamp 0) adopts them, and any real edit (stamped now) still wins.
- **What plan 06 must do.**
  - On start, read the existing `/settings` DataItems with `DataClient.getDataItems` and adopt the newest one with `adoptingNewer` (`PhoneBridge.latestSharedSettings()` on `feat/phone-app` already does this). `onDataChanged` alone misses items written before the phone app was installed.
  - Publish nothing until the phone has adopted those settings, or until the user edits a shared field. Use `publishableSharedSettings(repository.settings)` from `shared.settings` instead of a copy of its `map`/`distinctUntilChanged`/`filter(::isStamped)` chain. It skips stamp-0 settings, so a fresh phone's defaults never go out.
  - Until that first read finishes, a shared edit on the phone is stamped now and replaces every shared field on the watch, including the ones the user did not touch. Load the watch's settings before the phone shows any shared setting as editable.
