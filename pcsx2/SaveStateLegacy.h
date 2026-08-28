// SPDX-FileCopyrightText: 2026 yaps2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include "common/Pcsx2Types.h"

// Loader for legacy-format savestates: the upstream-PCSX2 formats of the
// AetherSX2/NetherSX2 era, and RetroSystem PS2's own pre-fork format. The
// container (zip entry set) is unchanged across all of them; what differs is
// the layout of the versioned blobs. The supported legacy majors and the blob
// readers live in SaveStateLegacy.cpp; the per-entry legacy handling
// (GS/SPU2/PAD/USB) hangs off SaveState_UnzipFromZip.
namespace SaveStateLegacy
{
	// The legacy savestate majors the in-engine reader understands:
	//   0x9A2C — upstream PCSX2 at 0312e902 (the AetherSX2 v1.5-era format)
	//   0x9A34 — upstream PCSX2 at 7e939b75 (the NetherSX2 v2.1 format)
	//   0x9A54 — RetroSystem PS2 up to 1.0.23 (this fork's own previous line)
	bool IsSupportedVersion(u32 savever);

	// Which of the two readers a supported legacy version needs. They are not
	// variants of one another: the AetherSX2 era is a parallel deserializer of
	// a genuinely older layout, while 0x9A54 is today's layout with 32-bit
	// cycle counters and goes through a mirror of the modern reader. The
	// per-entry handling differs too — an 0x9A54 state's GS/PAD/USB/Achievements
	// entries were written by the same code this build reads them with.
	bool IsAetherEra(u32 savever);
	bool Is9A54Era(u32 savever);

	// Widens a wrapping 32-bit cycle counter to 64 bits relative to a domain
	// base: the signed 32-bit delta to the old base is preserved against the
	// new base. Correct across u32 wraps, which zero-extension is not.
	inline constexpr u64 WidenCycle(u32 old_value, u32 old_base, u64 new_base)
	{
		return new_base + static_cast<s64>(static_cast<s32>(old_value - old_base));
	}
} // namespace SaveStateLegacy
