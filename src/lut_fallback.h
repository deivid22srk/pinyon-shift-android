#pragma once

#include <cstdint>
#include <filesystem>

namespace pinyon_shift {

// Identity colour-grading LUT fallback (BUG-03/BUG-10.2, session 20261009).
// The title samples 3D colour-grading look-up tables from
// `game:\media\dynamicpost\colourgradingmaps\` and per-track
// `game:\Media\tracks\<track>\colorgradinglookupNN.dds`; with the files
// missing (MTP disc-copy drops) the grading runs on empty input, which shows
// as blown highlights and near-black panels. The session log shows the
// Colorado per-track LUT missing (files.log 212: `colorgradinglookup00.dds`
// entry not found at 17:23:14, right as the track loads).
//
// This seeds a synthetic identity LUT next to the missing ones, on the HOST
// game folder, before the title enumerates them - the title then grades with
// a neutral table (no-op grading) instead of an empty one. The identity DDS
// is a legacy DX9 volume texture (A8R8G8B8, 16x16x16, no mips); the exact
// format of the original files is not verifiable from the session evidence,
// so the seeding is gated by the
// `pinyon_shift_identity_lut_fallback` cvar (default off) - if the title's
// parser rejects the synthetic file, it degrades exactly as it does today
// with the file missing.
//
// Returns the number of LUT files written.
size_t SeedIdentityLuts(const std::filesystem::path& game_root);

}  // namespace pinyon_shift
