# Development environment

How to set up a machine and run Fulguris locally: build, install on a device,
launch, and run the UI tests.

For the day-to-day loop as driven by AI agents — and the rationale behind the
`scripts/` tooling — see [AGENTS.md](../AGENTS.md). This document is the
human-friendly setup guide; it works on Windows, macOS and Linux.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Gradle 8.14.3 requires 17+. Set `JAVA_HOME` or have `java` on `PATH`. |
| Android SDK | platform **36**, plus `platform-tools` (`adb`) and `build-tools` | `compileSdk = 36` (see `gradle/libs.versions.toml`). `minSdk = 21`. |
| Python | 3.x | Standard library only — the `scripts/` need **no** `pip` packages. |
| Git | any recent | Needed for the submodules below. |

A physical device (phone / Android TV) or an emulator with **USB debugging**
enabled, or reachable over network `adb` (`ip:5555`).

## 1. Clone and initialize the submodules

The app builds against two library submodules, so a plain clone is not enough:

```bash
git clone --recurse-submodules https://github.com/Slion/Fulguris.git
# already cloned? initialize them afterwards:
git submodule update --init --recursive
```

| Submodule | Path | Purpose |
|---|---|---|
| Preference | `subs/Preference` | `:Preference` library (required by `app/build.gradle`) |
| AppIntro | `subs/AppIntro` | `:AppIntro` library (required by `app/build.gradle`) |
| l10n | `subs/l10n` | Localization data |
| wiki | `subs/wiki` | The project wiki |

`settings.gradle` includes `:Preference` and `:AppIntro` from `subs/`; if these
directories are empty the build fails during configuration.

## 2. Point Gradle at the Android SDK

Gradle finds the SDK through `local.properties` (gitignored — never commit it)
or the `ANDROID_HOME` environment variable. Create `local.properties` in the
repo root:

```properties
# Use forward slashes, even on Windows
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

Alternatively set `ANDROID_HOME` / `ANDROID_SDK_ROOT`. Gradle will auto-download
any missing SDK component (e.g. `build-tools`) **if** the SDK licenses are
accepted — see [Troubleshooting](#missing-build-tools--licenses).

## 3. Build, install and run

**Use the `scripts/tools/` helpers, not `gradlew` directly** — they handle
device selection, the APK path and the UTF-8 quirks of the terminal.

```bash
python scripts/tools/build.py                 # build the APK only
python scripts/tools/install.py --build --all # build + install on every connected device
python scripts/tools/launch.py  --restart --all   # force-stop + relaunch, clean state
python scripts/tools/capture.py --all         # screenshot each device to scripts/tools/out/
```

The scripts build the **`slionsFullAgentDebug`** variant by default — the
`agent` publisher flavor's debug build (app id `net.slions.fulguris.full.agent.debug`).
It installs *alongside* your own debug/release install instead of clobbering it.
See [features/agent-variant.md](features/agent-variant.md). Pass
`--build-type agentRelease` (or `downloadDebug`, `playstoreRelease`) to build
another variant, or `--device SERIAL` to target one device.

APK output for the default variant:
`app/build/outputs/apk/slionsFullAgent/debug/*.apk`.

Handy helpers while iterating:

```bash
python scripts/tools/ui.py state            # address-bar focus / keyboard / webview state
python scripts/tools/ui.py key 23           # send a key code (23 = D-pad center), then print state
python scripts/tools/ui.py text example.com # type into the focused field
```

## 4. Run the UI tests

```bash
python scripts/tests/run.py --list              # list available tests
python scripts/tests/run.py --device SERIAL     # the fast 'smoke' group (default)
python scripts/tests/run.py --all --group all   # the entire suite on every device
python scripts/tests/run.py --device SERIAL --group cursor   # one feature group
```

Results are written per device model under `scripts/tests/results/` (committed —
that's how regressions are tracked). See [AGENTS.md](../AGENTS.md#the-scripts)
for the full group list and conventions.

## 5. Android Studio

Open the repo root as a project. The IDE's default build variant is pinned to
**`slionsFullDownloadDebug`** (set via `isDefault = true` in `app/build.gradle`),
so **Run** from the IDE builds the normal download flavor, not the agent one.
The agent variant stays available in the *Build Variants* panel when you need it.

## Troubleshooting

### Gradle distribution download times out

`gradlew` downloads the Gradle distribution from `services.gradle.org`, which is
slow or unreachable on some networks (e.g. behind the GFW). On a restricted
network you may see:

```
Exception in thread "main" java.io.IOException: Downloading from
https://services.gradle.org/distributions/gradle-8.14.3-bin.zip failed: timeout
```

Workaround — fetch the distribution from a mirror and prime the wrapper cache so
the repo stays untouched. The cache slot is named after a hash of the URL in
`gradle/wrapper/gradle-wrapper.properties`; the failed run has already created it:

```bash
# Find the (created) cache slot for the version the wrapper wants:
#   ~/.gradle/wrapper/dists/gradle-8.14.3-bin/<hash>/
DIST="$HOME/.gradle/wrapper/dists/gradle-8.14.3-bin/<hash>"   # pick the existing <hash> dir
curl -L --retry 5 -o "$DIST/gradle-8.14.3-bin.zip" \
     https://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip
python -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" \
     "$DIST/gradle-8.14.3-bin.zip" "$DIST"
rm -f "$DIST"/gradle-8.14.3-bin.zip.{part,lck}
touch "$DIST/gradle-8.14.3-bin.zip.ok"   # marker that tells the wrapper it's ready
```

Then run `./gradlew --version` to confirm, and build as usual. Mirrors that
worked: `mirrors.cloud.tencent.com/gradle/`, `mirrors.huaweicloud.com/gradle/`.
(Changing `distributionUrl` in `gradle-wrapper.properties` also works but is a
tracked-file change that would need reverting.)

### `adb devices` hangs

If `adb devices` prints `List of devices attached` and then hangs, the adb server
has a stuck transport (often a stale network device). Restart it:

```bash
adb kill-server && adb start-server && adb devices
adb disconnect <ip>:5555   # drop a specific unreachable network device
```

Make sure `platform-tools` is on `PATH` (or call `adb` by absolute path).

### Missing build-tools / licenses

If Gradle cannot auto-download a missing SDK component, install it via the SDK
manager and accept licenses:

```bash
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

Or open **SDK Manager** in Android Studio and tick "Show Package Details".
