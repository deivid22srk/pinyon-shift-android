- [x] On native render, the main screen is completely green (fixed fa5079d)
  - Not native: byte-identical with native off. Caused by
    `--pinyon_shift_skip_opening_movies=true`: the skip hook
    (`PinyonShiftCompleteOpeningMovie`, 0x82E5D8AC) ends *every* XMedia movie,
    including the title loop `media/ui/videos/PressStart.wmv`, so its YUV
    planes stay zero (RGB 0,77,0). With movies playing the title is correct.
    Fix idea: only skip `media/ui/videos/splash_intros/*.wmv`.
- [x] Native render not working, defaulting to Xenos (obsolete)
  - The Xenos renderer and the renderer choice were removed (`e5dc399`,
    `83e5312`); the native executor is the only renderer. The old opt-in
    flags and the SNR03 vegetation fallback no longer exist. The route
    matrix (`fh1-fmv`, `fh1-opening-sync`, `fh1-rewind-sync`,
    `fh1-race-sync`, `fh1-modes-sync`, `fh1-buy-car`) passes natively with
    zero pack misses and zero executor skips.
- [x] (fixed fa5079d) When going back to title screen on native render, the "Single Player" select screen is completely corrupted in texture super pink noisy
  - Same root cause as the green title (movie skip): stale data in the
    PressStart.wmv planes. Identical in compat-only runs; clean with movies on.
- [ ] The intro/title videos still flicker (build 42 on-device evidence; torn-frame detection pinned by this branch, pending on-device validation)
  - Build 42's retention never engaged: the owner's build 42 log holds
    zero "retained last complete frame" lines while the title video still
    flickered black (screenshot 7 s after the FMV resolve is 88% black).
    The zero-chunk probe is blind to the steady state: after the first
    decoded frame, the tail below the decoder cursor holds the PREVIOUS
    frame's non-zero pixels, so every mid-rewrite snapshot has no zero
    chunks yet is still torn (new rows above the cursor, stale rows
    below). Uploading those is the flicker itself.
  - The pinned SDK keeps the bytes of the last uploaded snapshot on the
    texture as a baseline and diffs each new snapshot block-wise:
    identical snapshots skip the upload; changes reaching the last block,
    or with an all-zero tail (letterbox bars, fades), upload and
    re-baseline; a changed prefix with an unchanged non-zero tail whose
    boundary moved since the previous snapshot is a decode in progress and
    retains the last complete frame. Any plane-sized upload establishes
    the baseline (the zero-chunk probe can never mark a letterboxed plane
    complete), and the classification self-corrects from a torn baseline.
    The first seconds of the very first video can still be black while the
    runtime translates shaders (no on-device shader pack yet);
    `fh1_fmv_debug` (now default on) logs the classification to tell the
    cases apart.
- [ ] The app died with a GPU fault during the save/scene transition (build 42, single occurrence, pending a reproducing log)
  - `Vulkan Warning (tu_knl_kgsl.cc): GPU faulted or hung
    (VK_ERROR_DEVICE_LOST)` on the Turnip driver ~4 s after a profile
    save, amid heavy PSO compilation; the process restarted to the
    picker. Not yet known whether a translated shader faults on Turnip or
    the KGSL watchdog tripped; needs a repeat occurrence or a logcat with
    the Turnip debug properties reachable to correlate.
- [ ] Car selection on an event has either pink correupted textures or mangled car textures/models
  - Not a texture decode bug. The cards are the profile's
    `Thumbnails/Thumbnail_N.xdc` files, which the game renders, resolves and
    compresses from guest memory when it saves a car. Builds before SDK
    `0bf0658` never copied that resolve back into guest memory, so the files
    hold stale memory: the stripes (XR-04 in
    docs/native-renderer/XENOS_RETIREMENT_BACKLOG.md). New cards are correct
    (about 34 KB each); cards saved by older builds stay striped (275-845 KB
    of compressed noise) until the game saves them again. Repairing those is
    NP-0.6 in docs/NATIVE_PORT_BACKLOG.md.
