# Third-party notices

Pinyon Shift downloads the following third-party projects during local setup.
Their own licenses apply; they are not relicensed by this repository.

- [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk)
- [LLVM](https://github.com/llvm/llvm-project)
- [extract-xiso](https://github.com/XboxDev/extract-xiso)
- [Git for Windows](https://github.com/git-for-windows/git)
- [Microsoft Visual Studio Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/)

Exact versions, source URLs, and archive hashes are recorded in
`config/release-toolchain.json`. ReXGlue's transitive dependencies are fetched
by its pinned source tree and retain their upstream notices.

## Bundled fonts (Forza-style launcher UI)

The Android app bundles the following open-source fonts in
`android/app/src/main/res/font/`, used for the launcher screen
typography. Both are licensed under the SIL Open Font License 1.1
(OFL-1.1) with no Reserved Font Name; the license text is available at
https://openfontlicense.org and from each font's source repository.

- Exo 2 — copyright 2013 The Exo Project Authors
  (https://github.com/googlefonts/exo2), SIL Open Font License 1.1.
  Files: `exo2_extrabold_italic.ttf`, `exo2_bold_italic.ttf`.
- Rajdhani — copyright 2014 Indian Type Foundry
  (https://github.com/itfoundry/rajdhani), SIL Open Font License 1.1.
  Files: `rajdhani_bold.ttf`, `rajdhani_semibold.ttf`,
  `rajdhani_medium.ttf`, `rajdhani_regular.ttf`.
