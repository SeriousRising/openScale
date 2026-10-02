# openScale Etekcity reliability work

Updated 2026-10-02. All requested fixes are implemented, reviewed, built and accepted on the phone. The owner now uses the fork as the sole scale app. Hardware acceptance passed after the owner unlocked the phone: a weight-only reading saved, the app automatically recovered from a later idle remote disconnect, and a second reading saved without a Bluetooth tap. Both records were checked directly in a private database snapshot.

## Repository and deployment

- Fork: https://github.com/SeriousRising/openScale
- Issue: https://github.com/SeriousRising/openScale/issues/1
- Branch: `issue-1-etekcity-missing-readings`
- Base: `9fea9c813de282a0c3ab54bca923e62ee2d0362e`
- Owner requested committing the changes and removing the original app; finalized work is on the current issue branch.
- Fork update succeeded with `adb install -r`. After explicit owner authorization, the original F-Droid app was backed up and removed. Its profile, goals, measurement types, measurements and values were verified to be preserved in the fork.
- Active fork package: `com.health.openscale.debug`. Removed original package: `com.health.openscale`. The `com.health.openscale.sync` companion remains installed.
- Sync now selects the fork. Existing user/export-only Health Connect settings were preserved; connection test succeeded and all9 measurements were sent. Sync0.6.3 uses measurement ID + type as Health Connect clientRecordId, so preserved original IDs update existing records. New fork readings are live records, no longer isolated from sync.

## Completed fixes, in requested order

1. **Save result:** the BLE facade returns a positive persisted ID, null for duplicate/ignored readings, and propagates database errors. Connector logs/counts/notifies success only for a saved ID. Review additionally exposed widget/vibration/image-cleanup errors occurring after persistence; these optional effects now log failures without negating the stored result. Sync notification failures are logged separately.
2. **Fresh composition:** shared inheritance excludes historical builtin fat, water, muscle, bone, lean mass, visceral fat and protein, plus device-owned `ble.*` values. Fresh values win; manually tracked waist/custom values still inherit. Existing history is untouched. Explicitly configured estimation formulas still apply. Historical builtin heart-rate inheritance was outside the requested composition scope and remains unchanged.
3. **Remote disconnect recovery:** before publication, unexpected disconnect retries with existing tuning limits/backoff/cooldown. User stop, handler stop, publication and close suppress recovery. Pending connect/operation jobs are canceled; replacement queue workers join their predecessors. Each link gets a child handler scope, canceled and awaited before retry so old timers cannot stop a replacement. Connection callbacks update state synchronously, preventing immediate services discovery from losing setup. Stale callbacks and late reconnect events are ignored. Scan duration itself retains existing behavior; the cap bounds retry attempts.
4. **GATT failures:** failed write/notify/read callbacks, rejected/missing operations and timeouts stop queued work and report a localized reconnect instruction. Failed payloads never reach the handler. Cancellation remains cancellation. Shared operation handling replaces duplicated wait/error paths.

## Earlier fixes retained

- Auto-connect rearms once per foreground STARTED interval using the existing setting, including same-process reopen; busy states and permission/radio gates are preserved.
- ESF-551 parser uses byte19 for final/stable and byte20 for impedance availability. Final weight-only packets save without invented composition, once per connection. Estimation formulas unchanged.
- Owner's profile inputs were corrected in production and verified in exported data; corrected history/profile copied to debug. Exact inputs are private.
- Earlier installed build passed two physical weight-only readings, including a second after scale power-off and same-process reopen without a Bluetooth tap. An early reopen instead connected to the still-awake scale, then its shutdown ended the attempt: this is the recovery gap now addressed in source/tests.

## Verification

- Starting upstream baseline:878 tests; earlier two-fix installed build:887 tests.
- Final combined run: **907 tests, zero failures/errors/skips**; lintDebug, compileDebugAndroidTestKotlin and assembleDebug passed. Build54seconds. `git diff --check` clean.
- Save regression:6 facade/connector tests; database trigger errors, positive ID, duplicates, smart ignore, assisted weighing and actual success/error notifications. CRUD suite13 tests includes2 post-save effect regressions. All19 passed.
- Inheritance regression: existing10 +1 new test; before fix eight stale fields copied; after fix11 passed.
- GATT regression:11 callback integration tests covering recovery, stop/cap/queue lifetime, failed statuses/rejection/timeout, callback ordering and old handler timers.
- Every substantive fix has captured RED→GREEN evidence. Tests use synthetic data and actual Room/facade or actual adapter callbacks with Robolectric/Blessed objects. Callback tests are not hardware or Android-stack end-to-end proof.
- Independent review of save/composition found one post-save-effect issue, fixed with regressions; review of Bluetooth found callback-order and handler-lifetime issues, both fixed with regressions. Final review reported no remaining material findings in requested scope.
- Public machine-readable validation receipt: `baselines/2026-10-02-four-fixes-validation.json`.
- Build/test instructions and device-test sequencing: `AGENTS.md`.

## Private evidence

Keep all raw health data, device identifiers, screenshots, exports and logs outside Git at `~/.local/state/openscale-diagnostics/`. Important files:

- `save-outcome-{red,green}.{log,xml}`, `save-outcome-connector-red.log`
- `post-save-effects-red.{log,xml}`, `post-save-effects-green-{MeasurementCrudUseCasesTest,MeasurementFacadeBleSaveTest}.xml`
- `composition-inheritance-{red,green}.{log,xml}`
- `gatt-recovery-{red,green}.{log,xml}`, `gatt-status-{red,green}.{log,xml}`
- `gatt-callback-order-red.{log,xml}`, `gatt-handler-lifetime-red.{log,xml}`, `gatt-review-green.{log,xml}`
- `four-fixes-final-build.log`, `four-fixes-apk.sha256`, `installed-apk.sha256`, `four-fixes-launch.log`
- `before-four-fixes.apk`, `before-four-fixes-app-data.tar` (rollback backups)
- `production-profile-corrected.zip`, `resume-private.md` (profile/history and private handoff)

## Final hardware acceptance

The owner unlocked the phone and confirmed both new weights appeared. The same dev process was preserved throughout:

1. Automatic scan on unlock received and saved a weight-only reading.
2. Background/reopen while the scale was still awake created a fresh connection. The scale then switched off without providing another measurement.
3. Android reported a remote disconnect. The adapter emitted Reconnecting (try1/3), waited the configured cooldown, and restarted scanning automatically.
4. The next weigh-in connected and saved while the app stayed foreground, without a Bluetooth tap.
5. A private read-only database snapshot passed integrity_check. Both new records contain weight and normal derived BMI/BMR/TDEE, with no copied fat/water/muscle/bone/lean-mass/visceral-fat/protein values. Current sync logs ignored both debug records.

Private evidence: `four-fixes-first-reading.log`, `four-fixes-recovery.log`, `four-fixes-second-reading.log`, `four-fixes-hardware-database-check.json`, `four-fixes-after-tests-db.tar`. The owner did not supply a scale-display number, so no physical calibration or display-value agreement is claimed. Error injection, retry caps and manual-stop cases are covered by automated tests; the hardware test specifically confirms ordinary save, missing-composition handling and idle-disconnect recovery.

All four requested fixes are complete for this scope. Current fork remains installed. The owner subsequently authorized commit and original-app removal; migration details are above.

## Sole-app migration receipts

Original database integrity check passed. All rows from User, user_goals, Measurement, MeasurementValue and MeasurementType were present identically in the fork. Original APK and current database backup are retained privately as `original-fdroid-before-removal.apk` and `production-before-removal.zip`. Package manager confirmed original removal and both fork/sync retention. Sync source permission for the fork is granted; Health Connect connection test and9-record export succeeded. Original app removal was expressly requested by the owner, superseding earlier preserve-original instructions.
