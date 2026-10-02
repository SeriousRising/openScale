# openScale fork workflow

Read NEXT.md when resuming; STATUS.md records the scope, installed build and validation evidence.
Preserve uncommitted work. Commit/push only when the owner asks.

## Build and regression checks

Run from android_app with the installed full JDK; the system Java lacks javac:

```sh
JAVA_HOME="$HOME/.local/share/jdks/jdk-21.0.12.1+1" \
ANDROID_HOME="$HOME/.local/share/android-sdk" \
./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug
```

Serialize Gradle runs between agents. Use `--tests '*ClassName'` for the RED/GREEN loop,
then run the combined checks above. Robolectric callback tests prove adapter behavior;
record physical-device acceptance separately.

## Phone tests

ADB lives at `$HOME/.local/share/android-sdk/platform-tools/adb`.
The owner uses the fork under `com.health.openscale.debug` as the sole scale app.
The original `com.health.openscale` was removed with explicit authorization after
backup and data comparison. Install fork updates with the existing signing key using
`adb install -r android_app/app/build/outputs/apk/debug/openScale-debug.apk`.
Preserve the fork’s data with `-r`. Backups of the removed original app are private.

For a foreground-rearm test, let the scale switch off, background/reopen the app
without force-stopping it, verify the PID is unchanged, then take another reading.
For idle-disconnect recovery, connect while the scale is awake, let it power off
without a reading, wait for recovery to scan again, then weigh without touching Bluetooth.
Only the owner can perform the physical steps; cue them after the app is ready.
Check actual saved records and user confirmation, not merely a connection or save-log line.

Raw logs, profile exports, screenshots, health values and device identifiers belong
under `$HOME/.local/state/openscale-diagnostics/`, outside this public repository.
Use synthetic profiles and packets in tests. Capture logs before restarting the app;
its file logger resets on startup. openScale Sync now selects `com.health.openscale.debug` and exports to Health Connect.
New readings in this package are live records, not isolated test data.
