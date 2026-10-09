#include "lut_fallback.h"

#include <cstddef>
#include <cstring>
#include <fstream>
#include <string>
#include <system_error>
#include <vector>

#include <rex/logging.h>

#include "pinyon_shift_diagnostics.h"

namespace pinyon_shift {
namespace fs = std::filesystem;
namespace {

// The identity LUT: a 16x16x16 volume where texel (x, y, z) holds the color
// (x/15, y/15, z/15) - sampling it reproduces the input exactly, disabling
// any grading the shader applies through it.
constexpr uint32_t kLutSize = 16;

#pragma pack(push, 1)
struct DdsPixelFormat {
  uint32_t size;
  uint32_t flags;
  uint32_t four_cc;
  uint32_t rgb_bit_count;
  uint32_t r_bit_mask;
  uint32_t g_bit_mask;
  uint32_t b_bit_mask;
  uint32_t a_bit_mask;
};

struct DdsHeader {
  uint32_t size;
  uint32_t flags;
  uint32_t height;
  uint32_t width;
  uint32_t pitch_or_linear_size;
  uint32_t depth;
  uint32_t mip_map_count;
  uint32_t reserved[11];
  DdsPixelFormat pixel_format;
  uint32_t caps;
  uint32_t caps2;
  uint32_t caps3;
  uint32_t caps4;
  uint32_t reserved2;
};
#pragma pack(pop)

constexpr uint32_t kDdsMagic = 0x20534444u;  // 'DDS '

// DDSD_CAPS | DDSD_HEIGHT | DDSD_WIDTH | DDSD_PITCH | DDSD_PIXELFORMAT |
// DDSD_DEPTH.
constexpr uint32_t kDdsFlags = 0x1u | 0x2u | 0x4u | 0x8u | 0x1000u | 0x800000u;
// DDPF_ALPHAPIXELS | DDPF_RGB.
constexpr uint32_t kPixelFlags = 0x1u | 0x40u;
// DDSCAPS_COMPLEX | DDSCAPS_TEXTURE.
constexpr uint32_t kCaps = 0x8u | 0x1000u;
// DDSCAPS2_VOLUME.
constexpr uint32_t kCaps2 = 0x200000u;

std::vector<uint8_t> IdentityLutDds() {
  DdsHeader header{};
  header.size = sizeof(DdsHeader);
  header.flags = kDdsFlags;
  header.height = kLutSize;
  header.width = kLutSize;
  // Pitch of one slice face: 16 texels * 4 bytes (used for uncompressed).
  header.pitch_or_linear_size = kLutSize * 4;
  header.depth = kLutSize;
  header.mip_map_count = 0;
  header.pixel_format.size = sizeof(DdsPixelFormat);
  header.pixel_format.flags = kPixelFlags;
  header.pixel_format.rgb_bit_count = 32;
  // A8R8G8B8 masks.
  header.pixel_format.r_bit_mask = 0x00FF0000u;
  header.pixel_format.g_bit_mask = 0x0000FF00u;
  header.pixel_format.b_bit_mask = 0x000000FFu;
  header.pixel_format.a_bit_mask = 0xFF000000u;
  header.caps = kCaps;
  header.caps2 = kCaps2;

  std::vector<uint8_t> dds;
  dds.reserve(4 + sizeof(DdsHeader) + kLutSize * kLutSize * kLutSize * 4);
  const uint8_t* magic_bytes = reinterpret_cast<const uint8_t*>(&kDdsMagic);
  dds.insert(dds.end(), magic_bytes, magic_bytes + 4);
  const uint8_t* header_bytes = reinterpret_cast<const uint8_t*>(&header);
  dds.insert(dds.end(), header_bytes, header_bytes + sizeof(DdsHeader));
  // Slices (z), then rows (y), then texels (x); little-endian A8R8G8B8 is
  // stored as B, G, R, A bytes.
  for (uint32_t z = 0; z < kLutSize; ++z) {
    for (uint32_t y = 0; y < kLutSize; ++y) {
      for (uint32_t x = 0; x < kLutSize; ++x) {
        dds.push_back(uint8_t(x * 255u / (kLutSize - 1u)));  // B
        dds.push_back(uint8_t(y * 255u / (kLutSize - 1u)));  // G
        dds.push_back(uint8_t(z * 255u / (kLutSize - 1u)));  // R
        dds.push_back(0xFFu);                                // A
      }
    }
  }
  return dds;
}

bool TrackHasGradingLut(const fs::path& track_dir) {
  std::error_code error;
  fs::directory_iterator it(track_dir, fs::directory_options::skip_permission_denied, error);
  if (error) {
    return true;  // Unreadable: don't touch it.
  }
  for (; it != fs::directory_iterator(); it.increment(error)) {
    if (error) {
      return true;
    }
    std::string name = it->path().filename().string();
    // Case-insensitive prefix/suffix check (the title uses
    // colorgradinglookupNN.dds).
    for (char& c : name) {
      c = c >= 'A' && c <= 'Z' ? c - 'A' + 'a' : c;
    }
    if (name.starts_with("colorgradinglookup") && name.ends_with(".dds")) {
      return true;
    }
  }
  return false;
}

}  // namespace

size_t SeedIdentityLuts(const fs::path& game_root) {
  const fs::path tracks_root = game_root / "Media" / "tracks";
  std::error_code error;
  if (!fs::is_directory(tracks_root, error)) {
    return 0;
  }
  const std::vector<uint8_t> identity_dds = IdentityLutDds();
  size_t written = 0;
  fs::directory_iterator track_it(tracks_root, fs::directory_options::skip_permission_denied,
                                  error);
  if (error) {
    return 0;
  }
  for (; track_it != fs::directory_iterator(); track_it.increment(error)) {
    if (error) {
      break;
    }
    if (!track_it->is_directory(error)) {
      continue;
    }
    const fs::path& track_dir = track_it->path();
    if (TrackHasGradingLut(track_dir)) {
      continue;
    }
    const fs::path lut_path = track_dir / "colorgradinglookup00.dds";
    std::ofstream out(lut_path, std::ios::binary | std::ios::trunc);
    if (!out) {
      REXLOG_WARN("LUT fallback: could not write {}", lut_path.string());
      continue;
    }
    out.write(reinterpret_cast<const char*>(identity_dds.data()),
              std::streamsize(identity_dds.size()));
    out.close();
    if (out.good()) {
      ++written;
      REXLOG_INFO("LUT fallback: wrote identity colour-grading LUT {} (the disc "
                  "copy is missing the track's colorgradinglookup files; re-copy "
                  "Media/tracks from the disc extraction to use the original "
                  "grading)",
                  lut_path.string());
      diagnostics::RecordEvent("colourgrading.lut_seeded",
                               {{"path", lut_path.string()},
                                {"bytes", std::to_string(identity_dds.size())}});
    }
  }
  return written;
}

}  // namespace pinyon_shift
