# Android rendering fixes — 2026-10-04

Investigation of the rendering artifacts reported on device (Forza Horizon,
Motorola Edge 30 Fusion, Adreno 660, Turnip Mesa 26.3.0-devel): the colored
band on the top edge of the "Press Start" and menu screens, FMV
flicker/torn frames, and the blown-out exposure in the cockpit camera. The
root causes below were established by reading the pinned SDK's Vulkan paths
(`thirdparty/shiftglue-sdk`); every fix states how to validate it on device.

Branch: `fix/android-rendering-fmv-textures-20261004` (from
`auto/android-improvements-20261004-fmv-tearing`, SDK `d2db0a7` → `74a1de0`).

## 1. Colored band on the top edge of the Press Start / menu screens

**Symptom.** A strip of colored noise along the top edge of the frame with
the rest black; the strip grows or reappears while the title/menu background
video should be playing.

**Root cause (proven in code).** The title's software decoder rewrites each
video plane top to bottom in guest memory. On the FIRST frame after the
planes are cleared (video start, title loop restart) a snapshot taken
mid-rewrite holds new rows above the decoder cursor and never-written zeros
below. The FMV snapshot classification
(`src/graphics/vulkan/texture_cache.cpp`, `TryLoadTextureDataFromCpu`)
uploaded any snapshot whose tail below the lowest changed block is all zero
("a complete frame whose bottom rows are legitimately black"), and every
upload re-baselined the torn strip — so the next snapshot differed one band
further down and uploaded again. The strip grew snapshot by snapshot and
survived the build 42/43 fixes (bea41bd targets the steady state where the
tail below the cursor holds the PREVIOUS frame's non-zero pixels; the
first-frame case has a zero tail instead). The presenter path is not
involved: the final render pass clears the whole swapchain image and the
letterbox rects have no off-by-one, so the band arrives inside the guest
output image.

**Fix (SDK 05bab12).** The baseline diff now also tracks the first changed
block. When the tail below the change is all zero:

- changed region is a prefix (frame content starting at row 0) → upload as
  before (bottom letterbox bar, fade);
- changed region is a middle band and the baseline above the band is all
  zero → upload (static top letterbox bar);
- changed region is a middle band and the baseline above the band holds
  frame content → the decoder cursor is descending over rows it already
  wrote this frame — retain the last complete frame and skip the upload.
  The completion of the rewrite still reaches the bottom and uploads; a
  frozen decoder never reaches the retain branch (its snapshot is
  byte-identical and is skipped earlier).

**Validate on device.** With `fh1_fmv_debug` on (default on this branch):
start the title and watch the first seconds. Expected log signatures:
`fh1 fmv partial snapshot with zero tail retained last complete frame`
(the new retain) instead of a burst of `fh1 fmv plane load` uploads; on
screen, no colored strip at the top of Press Start or the menus; the
background video appears complete once the first frame finishes decoding
(a brief black before that is the pre-pack shader warm-up, see "Known
limits").

## 2. FMV tearing during gameplay and cutscenes

**Root causes addressed.**

1. Snapshot-vs-decoder races in the steady state were already handled by
   the fast path (`f4df688`) and the baseline-diff retention (`bea41bd`,
   `13a5cfa`) pinned by the base branch; the zero-tail first-frame case
   above is the remaining hole, now closed (SDK 05bab12).
2. Present tearing: the swapchain preferred `VK_PRESENT_MODE_IMMEDIATE_KHR`
   whenever the driver exposed it (`vulkan_allow_present_mode_immediate`
   defaulted to true). Through the Android compositor, IMMEDIATE can
   present mid-scanout — visible tearing on fast FMV motion.

**Fix (SDK 8d49a07).** `vulkan_allow_present_mode_immediate` now defaults
to `!REX_PLATFORM_ANDROID` (same pattern as `vulkan_texture_load_compute_copy`).
Android picks MAILBOX next (tear-free, drops late frames); desktop hosts
keep the old default; setting the cvar to true on Android restores the old
behavior for testing.

**Validate on device.** The startup log prints the chosen mode:
`VulkanPresenter: Created ... presentation mode ...` — expect `MAILBOX` (or
`FIFO`) instead of `IMMEDIATE`. FMV playback and camera pans should show no
horizontal tearing.

## 3. Cockpit blown-out exposure / near-black panel textures

**Status: diagnostic added, root cause needs one device check.** The
renderer does no color grading — the title samples 3D look-up tables from
`game:\media\dynamicpost\colourgradingmaps\`. MTP disc copies are known to
drop that folder (build 33 log: `STATUS_NO_SUCH_FILE 0xc000000f`);
`repairMtpDroppedDirectories` recreates the folder, but a copy that lost the
FILES too leaves it empty and the title grades with missing LUTs — which
shows exactly as broken exposure/bloom (flat washed highlights, near-black
panels). Three mitigations landed:

- **Repo 10636d1**: the game folder ready screen warns when
  `media/dynamicpost/colourgradingmaps` holds no files and asks for a
  re-copy from the disc extraction.
- **SDK c7c490d**: a failed `vmaCreateImage` in `CreateTexture` (out of
  memory, unsupported layout) logged nothing and bound a zero image — a
  silent source of black/broken textures. It now logs the key, extent,
  format and mip count.
- **Pipeline-cache wait (`26208bf`, already in the base branch)**: draws no
  longer skip for warming-up pipelines, which previously presented stale
  EDRAM content ("garbage bands", flicker) during the first boots.

**Validate on device.** Open the game folder screen: no grading warning →
the LUT files are present. In the runtime log, no
`VulkanTextureCache: Failed to allocate ... texture image` errors. If the
warning appears, re-copy `media/dynamicpost/colourgradingmaps` from the
disc extraction (with All Files Access, or over USB after granting MTP
time to finish) and check the cockpit camera again.

## 4. Distant-terrain mosaic, blurry track, grainy text

Already addressed on the base branch, awaiting the same on-device
validation round (no new code here): per-level `vkCmdCopyBufferToImage`
uploads (`f4df688`, mosaic in distant LODs), anisotropic filtering default
16x (`2ddd2bc`, schema 28 migration), warm-pipeline waits instead of draw
skips (`26208bf`). The scratched car thumbnails in the car selection are
stale save data (`Thumbnail_N.xdc` written by older builds), not a renderer
bug — `BUGS.md` documents the repair plan.

## Known limits / not fixed here

- **Black first seconds of the first video after a cold boot** — 371 PSOs
  translated at runtime while no `.pnsp` shader pack exists on device; the
  presenter skips frames that used placeholder pipelines
  (`vulkan_async_skip_incomplete_frames`) so nothing is presented until
  real pipelines exist. The pack generation (NP-15.2) is the real
  mitigation and needs a Windows host; republishing the last guest output
  was evaluated and deferred as too invasive without on-device testing.
- **Presentation resolve (`k_16_16_16_16`) partial updates** — if the
  controls screen still shows horizontal lines after the FMV fixes, the
  next `fh1_fmv_debug` log (and a `fh1_resolve_dump_dir` capture) decides
  between resolve sub-rect staleness and a UI texture path; extending the
  torn-frame retention to that resolve is prepared as the follow-up.
- **Turnip GPU fault after profile saves (single occurrence)** — needs a
  reproducing logcat with Turnip debug properties (`BUGS.md`).
- **Minimap stripes** — no device evidence yet; the resolve dump above
  covers it (tile-run alignment → unowned tiles; global color swap →
  copy_dest_swap; half-row offset → sample select).

## Commits

| Repo | Commit | Subject |
|------|--------|---------|
| shiftglue-sdk | `05bab12` | fix(vulkan): retain fmv frames over zero-tailed partial snapshots |
| shiftglue-sdk | `c7c490d` | feat(vulkan): log failed texture image allocations |
| shiftglue-sdk | `8d49a07` | fix(ui): avoid the immediate present mode by default on android |
| shiftglue-sdk | `74a1de0` | fix(vulkan): escape the zero-tail fmv retain when the band stops moving |
| pinyon-shift-android | `5e5c254` | fix(release): pin the sdk revision to the fmv zero-tail retention |
| pinyon-shift-android | `10636d1` | fix(android): warn when the colour grading maps folder is empty |
| pinyon-shift-android | `1ab1b9c` | fix(release): pin the sdk revision to the fmv zero-tail escape |

CI: `build.yml` dispatched on `fix/android-rendering-fmv-textures-20261004`
(workflow_dispatch; the APK artifact `pinyon-shift-apk` carries all of the
above).
