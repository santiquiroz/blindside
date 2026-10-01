# Plan 05 review fixes: cross-plan notes for plan 06

These notes come from the review of `feat/android-shared` (plan 05). Each one changes something plan 06 consumes from `android-shared`. Plan 06 must follow them before it merges.

## 1. `/settings`: adopt the watch's settings before publishing anything

- **What changed in plan 05.** A watch installed before plan 05 has no `shared_updated_ms` key. `settingsFrom` now reads such settings with `MIGRATED_STAMP_MS` (1) instead of 0, whenever the stored prefs hold a hand. Every older build stored the hand on every write. The next write persists the 1. The watch then publishes its hand, mounts, signs and posture. A fresh phone (stamp 0) adopts them, and any real edit (stamped now) still wins.
- **What plan 06 must do.**
  - On start, read the existing `/settings` DataItems with `DataClient.getDataItems` and adopt the newest one with `adoptingNewer` (`PhoneBridge.latestSharedSettings()` on `feat/phone-app` already does this). `onDataChanged` alone misses items written before the phone app was installed.
  - Publish nothing until the phone has adopted those settings, or until the user edits a shared field. Use `publishableSharedSettings(repository.settings)` from `shared.settings` instead of a copy of its `map`/`distinctUntilChanged`/`filter(::isStamped)` chain. It skips stamp-0 settings, so a fresh phone's defaults never go out.
  - Until that first read finishes, a shared edit on the phone is stamped now and replaces every shared field on the watch, including the ones the user did not touch. Load the watch's settings before the phone shows any shared setting as editable.

## 2. Radar fan: size it from the configured belt

- **What changed in plan 05.** `toDrawModel` no longer sizes the fan from `scene.coverage`. That list holds only the radars alive now, so a flapping radar moved the origin and resized the fan mid-game. The fit now comes from the new last parameter `fitHalfAngleDeg`, whose default is `MAX_FIT_HALF_ANGLE_DEG` (90°). `fanHalfAngleFor(settings)` (or `configuredFanHalfAngleDeg(config)`) computes it from every configured mount. The live sectors are still drawn from `scene.coverage`.
- **What plan 06 must do.** Its radar view (`RadarView` on `feat/phone-app`) passes `fitHalfAngleDeg = fanHalfAngleFor(settings)`, remembered on the hand and the radars. The default already keeps the fan steady, and it matches every default belt. Only a belt whose yaws were both nudged within ±30° needs the configured value. The viewer's heat map (`toDrawModel(null, …)`) can keep the default, or pass the angle computed from the recording's config.

## 3. BLE: the phone asks for no priority once the link is up

- **What changed in plan 05.** `settledPriorityFor` returns `LinkPriority?`: `BALANCED` for the watch and `null` for the phone, and `LinkPriority.LOW_POWER` is gone. `BeltGatt` skips `requestConnectionPriority` when the value is null. Android's `LOW_POWER` is a central-initiated update to 100-125 ms with latency 2 (AOSP defaults). It came after the belt's 60-100 ms request on `06 01` and overrode it (spec §2). The phone still connects with `BALANCED`.
- **What plan 06 must do.** Nothing new. `feat/phone-app` already carries the same change in `BeltCommands.kt`, `BeltGatt.kt` and `BeltCommandsTest`, so the stacked branch should rebase without a conflict there. It must not request a priority on the phone link anywhere else after setup. Plan 04's flag 7 (and the H32 check of the phone's `itvl` 48-80 with `lat=0`) now holds once plan 05 merges.
