# Troubleshooting

## The ISO is rejected

Only the USA retail base disc listed in `config/supported-dumps.json` is
supported. A bad dump, another region, a title update, or a modified image will
not match. The launcher does not accept nearly matching images and cannot
download or repair one.

## Setup asks for administrator permission

Elevation is needed only when the Microsoft C++ Build Tools are absent. The
launcher itself, downloads, disc extraction, generation, build, saves, and logs
remain in user-writable folders.

## Windows warns about an unrecognized app

Preview launchers are not code-signed yet, so Microsoft Defender SmartScreen may
show an unrecognized-app warning. Download only from this repository's Releases
page and compare the ZIP's SHA-256 with the value published in the release notes
before deciding whether to run it.

## A download fails

Check the internet connection and run the launcher again. ReXGlue and its
submodules are retried automatically after transient GitHub/network failures.
Partially downloaded files are not trusted; every pinned archive is SHA-256
verified before use.

## The build fails

Restart Windows after a Build Tools installation, make at least 25 GB free, and
close every running Pinyon Shift preview before trying again. If it still
fails, choose **Open logs** in the launcher and attach `launcher.log` to the bug
report. This log contains build output and local paths, but no game data or
generated source. Never attach game files or generated source.

## A portable install cannot start or build

A launcher with `portable.txt` beside it (or started with `--portable`) keeps
everything in the `data` folder next to it; see "Portable install" in the
README.

- **"Portable folder is not writable"**: the launcher folder is read-only for
  your account, typically because it was extracted into Program Files or onto
  read-only media. Move the whole folder somewhere you own, such as
  `D:\Games\PinyonShift`, or delete `portable.txt` to install under
  `%LOCALAPPDATA%\PinyonShift` instead.
- **"Portable folder path is too long"**: the build creates files about 185
  characters below `data`, and Git, CMake and the compilers stop at Windows'
  260-character limit. Move the launcher folder so the `data` folder's path is
  70 characters or fewer, for example `D:\PinyonShift\data`. An existing build
  in a deeper folder still plays.
- **After moving the folder** the first rebuild configures the build folder
  again and recompiles, which takes about as long as the first build. Playing
  an existing build needs no rebuild. On a PC without Visual Studio Build
  Tools, the next build installs them again.

## Gameplay stutters

Some one-time stutter while the preview encounters new effects is expected in
this early release. If severe stutter continues after revisiting the same route,
report whether it happens only on the first pass or every pass and include the
latest performance CSV and runtime log from `.local/preview/logs`. Those two
cases have different causes, and the measurements are needed for a targeted
fix.

## Something is missing the first time a screen appears

The preview draws only with shaders prepared before launch. When the game
builds a shader that preparation did not reach, those draws are skipped for
that session and the shader is recorded. The next launch prepares graphics
again ("Preparing graphics ... This only runs when needed.") and includes it.
If the same thing stays missing after relaunching, report it with the latest
runtime log from `.local/preview/logs`.

## Graphics setup fails with a resolution scale error

The renderer supports internal resolution scales of 1x, 2x, 3x and 4x only,
with the same value horizontally and vertically. Any other `draw_resolution_scale_x`
or `draw_resolution_scale_y` in `.local/preview/config/pinyon_shift.toml`
stops graphics setup with an error that names the requested scale. Choose a
supported scale in the launcher, or remove the file to reset runtime settings.

## The game does not start

Update the GPU driver and confirm that the GPU supports DirectX 12. Remove
`.local/preview/config/pinyon_shift.toml` to reset runtime settings. Security
software may also quarantine a newly compiled unsigned executable; restore it
only after confirming it was produced by your local checkout.

## A controller is not recognized

Connect the controller before starting the game. Pinyon Shift uses SDL mappings
for DirectInput devices and includes an explicit mapping for the 8BitDo Ultimate
2C Wired Controller (`2dc8:301d`). If that model still does not respond, confirm
that `gamecontrollerdb.txt` is beside `pinyon_shift.exe`, close Steam or other
controller-remapping software temporarily, reconnect the controller, and relaunch.

When reporting another unsupported controller, include its exact name, USB
vendor/product IDs, button count, axis count, and a screenshot from a gamepad
tester. Do not attach input recordings.

## A selected Xbox menu item does not respond

Xbox menus do not use Windows pointer targeting. Use controller A or Space to
activate the selected item. Pinyon Shift also maps a left click to controller A,
so clicking while `SIGN IN` or another row is selected activates that row.
Press Enter for the Xbox Start button.

## The game crashes

Leave the launcher open while playing. It will prepare a sanitized ZIP under
`.local/preview/reports`, select that file in Explorer, and open a prefilled
GitHub issue. Attach the ZIP and describe the last actions before the crash.

Memory dumps under `.local/preview/crashes` may contain process-memory fragments
and are never placed in the public report. Keep them local unless a maintainer
arranges a private transfer for a specific investigation.

## Start over

Close the launcher and delete `.local` and `out` from the repository or the
launcher source folder under `%LOCALAPPDATA%\PinyonShift` (for a portable
install, under `data\source` beside the launcher). Your original ISO is outside
those folders and is never deleted by project scripts.

## Uninstall completely

Close the launcher and preview, then delete the extracted launcher folder and
`%LOCALAPPDATA%\PinyonShift`; a portable install is only its own folder. The
preview does not install a Windows service or registry startup entry. Microsoft
Visual Studio Build Tools are shared system tools and should be removed
separately from **Installed apps** only if no other development work uses them. The original ISO remains wherever you stored it.

## Android port

### The game folder is not accepted

The picker expects the folder extracted from the disc — the same one the
Windows launcher produces — and it must contain `media/ui`; a raw `.iso`
cannot be played. When the folder was moved, renamed or the drive is
unplugged, the picker says so and asks for a new selection. A selection made
through the SAF picker only takes effect on the next app start, because the
game process reads the location once at boot: close a running game (swipe the
app away from Recents) before switching folders.

On Android 11 and newer the app needs All Files Access to read non-media
files where they are; on Android 10 it needs the storage permission. Devices
whose system picker refuses folders (MIUI/HyperOS) can use **Browse folders
instead**, which walks the filesystem directly once All Files Access is
granted. If a folder shows as ready but files still fail to open, re-grant
the permission — some devices report it while still hiding files behind FUSE.

### The device has too little room

The game content is read in place, but saves, shader caches, logs and crash
reports live in the app's internal storage (`files/state` inside the app
sandbox) and grow over the first launches while pipelines warm up. The
picker shows a warning when internal storage has under 512 MB usable. Free
space on the device rather than deleting game content: the cache files are
rebuilt automatically, but a full volume makes the first minutes of play
stutter and can abort saving.

### The game does not start on a device with no Vulkan 1.1

The game renders through Vulkan 1.1. When the device does not report that
feature level, the picker shows a warning next to **Ready to play**, and the
runtime exits during startup with the reason in the log. This is expected on
devices whose GPU stack predates Vulkan 1.1; there is no software fallback.

### The custom GPU driver did not load

After installing an AdrenoTools package (for example a Mesa Turnip ZIP) the
startup log should show `Custom GPU driver <name>: loaded <library> through
adrenotools`, and the Vulkan device name changes to the Turnip one instead of
`Qualcomm ...`. When it says `adrenotools could not load`, the reason follows
on the same line and the game falls back to the system driver — the session
still works, just without the custom driver's behavior. The selection and
the GPU turbo switch apply on the next game start.

### The game crashed

The runtime installs a signal handler on Android that writes a report for
SIGABRT-class crashes under `files/state/crashes` inside the app's sandbox
and prints the pending ones to the log on the next boot, so a crash that
seemed silent is still recorded. Hardware faults go to the system's own
tombstone instead. Attach the most recent report file from that folder
(plus `files/state/logs/runtime.log`, which carries the build provenance) to
a GitHub issue, together with the last actions before the crash. Reports
contain the signal, fault address and a backtrace, not saves or game content.
