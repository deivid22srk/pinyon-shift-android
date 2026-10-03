# Pinyon Shift for Android

Forza Horizon (Xbox 360, USA retail disc MS-2505 / title 4D5309C9), recompiled
to run natively on Android. This repository is the Android port of
[Pinyon Shift](https://github.com/arcanite24/pinyon-shift): the game's PowerPC
code is translated ahead of time into C++ with the pinned
[ShiftGlue](https://github.com/arcanite24/shiftglue-sdk) (ReXGlue) SDK and
compiled together with the host into an APK. The GPU command stream runs
through the project's own Vulkan renderer (`rexgpu-fh1`).

**This repository contains no game code, no game assets and no generated
translations.** You need your own retail disc image to build and to play.

## Status

- CI builds the release APK on every push: **arm64-v8a only** (see the
  `pinyon-shift-apk` artifact). There is no emulator/x86 build and no
  automated device test in CI — testing happens on real hardware.
- The Gradle project still carries a local-only `emulator` flavor (x86_64,
  debug) that is not built in CI; use it for your own desktop-emulator
  experiments with `:app:assembleEmulatorDebug`.

## Building

The APK is built by GitHub Actions (`.github/workflows/build.yml`). The
pipeline is:

1. verify the three game executables (SHA-256/size, from
   `config/supported-dumps.json`) fetched at build time from the private
   `Forza1files` release through the `XEX_REPO_TOKEN` secret — the
   executables are **never committed** and are deleted from the runner after
   use is not required: they only live in the runner workspace;
2. build the `rexglue` code generator (x86-64 Linux build host tool);
3. generate the translated C++ sources from the executables
   (`.local/generated/`, ignored by git);
4. cross-compile the runtime, the FH1 Vulkan GPU plugin, the generated game
   code (main XEX + SpeechFacade + XMediaFacade modules) and the Android host
   (`libmain.so`) with the NDK for arm64-v8a;
5. package and sign the APK.

For local builds you need: JDK 21, Android SDK with NDK r27c and CMake 3.31,
plus the steps above (generator + codegen) run first. Then:

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDeviceRelease
```

## Requirements on device

- Android 10 (API 29) or newer, arm64-v8a.
- A GPU with Vulkan 1.1+ (Adreno/Mali; the manifest declares Vulkan 1.1 as
  optional and the app exits with a clear log if the device cannot provide a
  suitable device).
- Roughly 9 GB of free storage for the game content.

## Game data layout

The APK ships without game content. On the first launch a picker screen
(modeled after XenDroid's game library) asks for the game location: select
the folder extracted from your own disc (the same folder the Windows
launcher produces, ~2400 files, ~7.2 GB) and it is read **in place — nothing
is copied into the app**. The folder must contain `media/ui` (the host UI
reads fonts and button art from there); a raw `.iso` cannot be used
directly. The selection is remembered; use `Select game folder` again to
change it.

The picker resolves the SAF selection to the folder's real device path, so
the runtime needs permission to read non-media files where they are:

- Android 11+ (API 30+): grant All Files Access when prompted.
- Android 10 (API 29): grant storage access when prompted.

`Browse folders instead` walks the filesystem directly for devices whose
system picker refuses folders (MIUI/HyperOS). The legacy
`Android/data/dev.pinyon.shift/files/game/base` location (copied over USB)
keeps working when no folder was picked. Saves, caches, logs and settings
live in the app's internal storage (`files/state` inside the app sandbox).

A freshly selected folder takes effect on the next app start: the game
process reads the location once at boot, so close a running game (swipe the
app away from Recents) before switching to another folder.

## GPU driver

The picker screen has a `GPU driver` section: it installs the community
AdrenoTools driver packages (Mesa Turnip ZIPs, e.g. K11MCH1/AdrenoToolsDrivers)
and selects which one the game uses. The import validates the package's
`meta.json`, the minAPI, and the ELF libraries (arm64 shared objects only);
multi-file packages keep all their `.so` companions. The active driver is
loaded by the runtime through libadrenotools at startup, with a fallback to
the system driver (and a logged reason) when it fails. `GPU turbo` runs the
Adreno at its highest clocks while the game is shown. Everything applies on
the next game start.

How to tell the custom driver really loaded: the startup log shows
`Custom GPU driver <name>: loaded <library> through adrenotools` (and the
Vulkan device name becomes the Turnip one, e.g. `Mesa Turnip ...`, instead of
`Qualcomm ...`). If it says `adrenotools could not load`, the reason follows
on the same line and the game runs on the system driver.

Bluetooth and USB gamepads are supported through SDL3 (the project's
`config/gamecontrollerdb.txt` ships in the APK and is loaded at startup).
Touch input falls back to SDL finger events.

## Legal

Only the port glue added on top of upstream Pinyon Shift is subject to its
BSD 3-Clause license. Microsoft, Xbox, Turn 10 Studios, Playground Games,
*Forza Horizon* and their assets remain the property of their respective
owners. This port is not affiliated with or endorsed by them.
