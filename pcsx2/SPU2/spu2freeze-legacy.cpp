// SPDX-FileCopyrightText: 2026 yaps2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

// Restores the SPU2 block of an AetherSX2/NetherSX2-era save state. The block
// is a raw memcpy of that era's SPU2 globals, so it is read here as byte-exact
// era structures and mapped field-by-field onto today's; nothing about it can
// go through the normal thaw path. Kept out of spu2freeze.cpp so that the file
// upstream maintains stays untouched.

#include "SPU2/defs.h"
#include "SPU2/spu2.h"
#include "IopHw.h"
#include "IopMem.h"
#include "R3000A.h"
#include "SaveStateLegacy.h"

#include "common/Console.h"

#include <cstring>
#include <memory>

// The core and voice structures as AetherSX2-era builds wrote them. Layout per
// upstream PCSX2 7e939b75 (pcsx2/SPU2/defs.h); the 0x9A2C era at 0312e902 has
// the identical layout, differing only in method declarations and in the
// signedness of names outside V_Core, none of which moves a field. Sizes are
// static_asserted below against the offsets the format walker measured on real
// files, so a host-ABI surprise fails the build rather than the load.
namespace LegacySPU2
{
	struct VolumeLR
	{
		s32 Left;
		s32 Right;
	};

	// Superseded by a bitfield-plus-Counter form; only the register value and
	// the current level survive into it, and the bitfields decode from the
	// register value for free because they union with it.
	struct VolumeSlide
	{
		s16 Reg_VOL;
		s32 Value;
		s8 Increment;
		s8 Mode;
	};

	struct VolumeSlideLR
	{
		VolumeSlide Left;
		VolumeSlide Right;
	};

	// Widened to s32 since. u128-aligned in the original via a union overlay
	// that nothing reads back.
	struct VoiceGates
	{
		s16 DryL, DryR, WetL, WetR;
	};

	// The original overlays these on a u128 — two u64s, so 8-aligned, which
	// pads the gates out from the voice gates that precede them. Getting this
	// wrong slides the gates and all four volumes 4 bytes without changing the
	// size of the core, because the alignment padding in front of the voice
	// array silently absorbs it.
	struct alignas(8) CoreGates
	{
		s16 InpL, InpR, SndL, SndR, ExtL, ExtR;
		s16 _u128_overlay_pad[2];
	};

	struct ADSR
	{
		u32 reg32;
		s32 Value;
		u8 Phase;
		bool Releasing;
	};

	// Maps field-for-field except the decoder internals: the sample-decode
	// pipeline was re-architected since (PV1..PV4/SCurrent gave way to a decode
	// FIFO), so the position inside the current 28-sample block does not carry
	// across and the block restarts — a sub-millisecond hiccup per voice, once.
	struct Voice
	{
		u32 PlayCycle;
		u32 LoopCycle;
		u32 PendingLoopStartA;
		bool PendingLoopStart;
		VolumeSlideLR Volume;
		ADSR Adsr;
		u16 Pitch;
		u32 LoopStartA;
		u32 StartA;
		u32 NextA;
		s32 Prev1;
		s32 Prev2;
		bool Modulated;
		bool Noise;
		s8 LoopMode;
		s8 LoopFlags;
		s32 SP;
		s32 SPc;
		s32 PV4;
		s32 PV3;
		s32 PV2;
		s32 PV1;
		s32 OutX;
		s32 NextCrest;
		u64 SBuffer; // s16* in the original — a host pointer, never dereferenced
		s32 SCurrent;
	};

	struct Reverb
	{
		s16 IN_COEF_L, IN_COEF_R;
		u32 APF1_SIZE, APF2_SIZE;
		s16 APF1_VOL, APF2_VOL;
		u32 SAME_L_SRC, SAME_R_SRC, DIFF_L_SRC, DIFF_R_SRC;
		u32 SAME_L_DST, SAME_R_DST, DIFF_L_DST, DIFF_R_DST;
		s16 IIR_VOL, WALL_VOL;
		u32 COMB1_L_SRC, COMB1_R_SRC, COMB2_L_SRC, COMB2_R_SRC;
		u32 COMB3_L_SRC, COMB3_R_SRC, COMB4_L_SRC, COMB4_R_SRC;
		s16 COMB1_VOL, COMB2_VOL, COMB3_VOL, COMB4_VOL;
		u32 APF1_L_DST, APF1_R_DST, APF2_L_DST, APF2_R_DST;
	};

	// Pre-clipped reverb pointers, dropped since — recomputed on demand now.
	struct ReverbBuffers
	{
		s32 _offsets[28];
		bool NeedsUpdated;
	};

	struct CoreRegs
	{
		u32 PMON, NON, VMIXL, VMIXR, VMIXEL, VMIXER, ENDX;
		u16 MMIX, STATX, ATTR, _1AC;
	};

	struct Core
	{
		u32 Index;
		VoiceGates VoiceGates_[24];
		CoreGates DryGate;
		CoreGates WetGate;
		VolumeSlideLR MasterVol;
		VolumeLR ExtVol;
		VolumeLR InpVol;
		VolumeLR FxVol;
		Voice Voices[24];
		u32 IRQA;
		u32 TSA;
		u32 ActiveTSA;
		bool IRQEnable;
		bool FxEnable;
		bool Mute;
		bool AdmaInProgress;
		s8 DMABits;
		u8 NoiseClk;
		u32 NoiseCnt;
		u32 NoiseOut;
		u16 AutoDMACtrl;
		s32 DMAICounter;
		u32 LastClock;
		u32 InputDataLeft;
		u32 InputDataTransferred;
		u32 InputPosWrite;
		u32 InputDataProgress;
		Reverb Revb;
		ReverbBuffers RevBuffers;
		s32 RevbDownBuf[2][64];
		s32 RevbUpBuf[2][64];
		u32 RevbSampleBufPos;
		u32 EffectsStartA;
		u32 EffectsEndA;
		u32 ExtEffectsStartA;
		u32 ExtEffectsEndA;
		u32 ReverbX;
		s32 EffectsBufferSize;
		u32 EffectsBufferStart;
		CoreRegs Regs;
		s32 LastEffectLeft, LastEffectRight;
		u8 CoreEnabled;
		u8 AttrBit0;
		u8 DmaMode;
		bool DmaStarted;
		u32 AutoDmaFree;
		u64 DMAPtr; // u16* in the original — a host pointer, never dereferenced
		u64 DMARPtr; // ditto
		u32 ReadSize;
		bool IsDMARead;
		u32 KeyOn;
		u16 psxSoundDataTransferControl;
		u16 psxSPUSTAT;
	};

	struct Tail
	{
		u16 SpdifOut, SpdifInfo, SpdifUnknown1, SpdifMode;
		u16 SpdifMedia, SpdifUnknown2, SpdifProtection;
		u16 OutPos;
		u16 InputPos;
		u32 Cycles;
		u32 lClocks;
		int PlayMode;
	};

	static_assert(sizeof(Voice) == 128, "legacy V_Voice is 128 bytes");
	static_assert(offsetof(Core, DMAPtr) == 4744, "legacy V_Core DMA pointers sit at +4744");
	static_assert(sizeof(Core) == 4776, "legacy V_Core is 4776 bytes");
	static_assert(sizeof(Tail) == 32, "legacy SPU2 tail is 32 bytes");

	// The size and the DMA-pointer offset above both sit past the voice array,
	// whose 8-byte alignment absorbs any drift in front of it — so neither can
	// see a mis-sized gate or volume. These pin the region they are blind to.
	static_assert(offsetof(Core, DryGate) == 200, "gates follow the voice gates on an 8-byte boundary");
	static_assert(offsetof(Core, MasterVol) == 232, "master volume follows both core gates");
	static_assert(offsetof(Core, ExtVol) == 256, "the three plain volumes follow master volume");
	static_assert(offsetof(Core, Voices) == 280, "the voice array closes the mixer region");
	static_assert(offsetof(Core, Revb) == 3408, "reverb registers follow the DMA and noise scalars");
	static_assert(offsetof(Core, Regs) == 4688, "core registers follow the reverb buffers");

	// A volume slide keeps its register value and its current level; the slide
	// bitfields overlay the register value, and the ramp counter restarts.
	//
	// Every volume level in these eras is 31-bit (Max = 0x7FFFFFFF); today's
	// mixer runs them at 15 bits (Max = 0x7FFF) and multiplies in s32. A raw
	// copy is therefore not one binade loud — it overflows the multiply, and
	// every sample in the mix comes out as sign-flipped hash. This was the
	// "static" that survived every other bisect: a full-volume legacy level
	// amplifies anything, even a freshly reset core's near-silence, into
	// full-scale broadband noise.
	static V_VolumeSlide MapVolumeSlide(const VolumeSlide& src)
	{
		V_VolumeSlide dst;
		dst.Reg_VOL = static_cast<u16>(src.Reg_VOL);
		dst.Counter = 0;
		dst.Value = src.Value >> 16;
		return dst;
	}

	static V_VolumeLR MapVolume(const VolumeLR& src)
	{
		V_VolumeLR dst;
		dst.Left = src.Left >> 16;
		dst.Right = src.Right >> 16;
		return dst;
	}

	// The envelope value was 31-bit then and is 15-bit now, and the phase walked
	// attack=1 through release-end=6 with a separate Releasing flag, where today
	// release is a phase of its own. The flag folds in exactly as the era's own
	// Calculate() folded it on entry; the two terminal phases (sustain-end,
	// release-end) land on stopped with the envelope at zero.
	static void MapVoice(V_Voice& dst, const Voice& src)
	{
		dst.Volume.Left = MapVolumeSlide(src.Volume.Left);
		dst.Volume.Right = MapVolumeSlide(src.Volume.Right);

		dst.ADSR.reg32 = src.Adsr.reg32;
		dst.ADSR.UpdateCache();
		dst.ADSR.Counter = 0;
		dst.ADSR.Value = src.Adsr.Value >> 16;

		u8 phase = src.Adsr.Phase;
		if (src.Adsr.Releasing && phase < 5)
			phase = 5;
		switch (phase)
		{
			case 1:
				dst.ADSR.Phase = V_ADSR::PHASE_ATTACK;
				break;
			case 2:
				dst.ADSR.Phase = V_ADSR::PHASE_DECAY;
				break;
			case 3:
				dst.ADSR.Phase = V_ADSR::PHASE_SUSTAIN;
				break;
			case 5:
				dst.ADSR.Phase = V_ADSR::PHASE_RELEASE;
				break;
			default:
				dst.ADSR.Phase = V_ADSR::PHASE_STOPPED;
				dst.ADSR.Value = 0;
				break;
		}

		dst.Pitch = src.Pitch;
		dst.LoopStartA = src.LoopStartA;
		dst.StartA = src.StartA;
		dst.NextA = src.NextA;
		dst.Prev1 = src.Prev1;
		dst.Prev2 = src.Prev2;
		dst.Modulated = src.Modulated;
		dst.Noise = src.Noise;
		dst.LoopMode = src.LoopMode;
		dst.LoopFlags = src.LoopFlags;
		dst.SP = src.SP;
		dst.OutX = src.OutX;

		// As the native thaw does: point the decode buffer at the cache entry
		// for the current read address (Sampledata is an array member, so the
		// pointer stays valid across the cache wipe). The index is masked
		// because NextA comes off disk.
		dst.SBuffer = pcm_cache_data[(dst.NextA & 0xFFFFF) / pcm_WordsPerBlock].Sampledata;
	}

	// Restores a core from the era block. The mixer configuration is state a
	// game writes once — at boot, or on a scene change — and then never again,
	// so resetting it and waiting for the game means waiting forever: master
	// volume stays zero and the console stays silent for the session. The
	// streaming and DMA state is state the restored IOP driver believes is
	// live: left at reset, the driver waits forever for a completion interrupt
	// no counter is going to deliver, while the input ring loops its last
	// half-filled window as broadband noise under whatever else is playing.
	static void RestoreCore(V_Core& dst, const Core& src)
	{
		for (uint v = 0; v < V_Core::NumVoices; v++)
		{
			dst.VoiceGates[v].DryL = src.VoiceGates_[v].DryL;
			dst.VoiceGates[v].DryR = src.VoiceGates_[v].DryR;
			dst.VoiceGates[v].WetL = src.VoiceGates_[v].WetL;
			dst.VoiceGates[v].WetR = src.VoiceGates_[v].WetR;
			MapVoice(dst.Voices[v], src.Voices[v]);
		}

		const auto map_core_gate = [](V_CoreGates& d, const CoreGates& s) {
			d.InpL = s.InpL;
			d.InpR = s.InpR;
			d.SndL = s.SndL;
			d.SndR = s.SndR;
			d.ExtL = s.ExtL;
			d.ExtR = s.ExtR;
		};
		map_core_gate(dst.DryGate, src.DryGate);
		map_core_gate(dst.WetGate, src.WetGate);

		dst.MasterVol.Left = MapVolumeSlide(src.MasterVol.Left);
		dst.MasterVol.Right = MapVolumeSlide(src.MasterVol.Right);
		dst.ExtVol = MapVolume(src.ExtVol);
		dst.InpVol = MapVolume(src.InpVol);
		dst.FxVol = MapVolume(src.FxVol);

		dst.IRQA = src.IRQA;
		dst.IRQEnable = src.IRQEnable;
		dst.FxEnable = src.FxEnable;
		dst.Mute = src.Mute;
		dst.NoiseClk = src.NoiseClk;

		// Reverb registers are byte-identical across the eras; the pre-clipped
		// buffer pointers beside them are derived on demand now, and the filter
		// history is transient, so both stay at their reset values.
		static_assert(sizeof(Reverb) == sizeof(V_Reverb), "reverb registers did not change shape");
		std::memcpy(&dst.Revb, &src.Revb, sizeof(V_Reverb));
		dst.EffectsStartA = src.EffectsStartA;
		dst.EffectsEndA = src.EffectsEndA;

		static_assert(sizeof(CoreRegs) == sizeof(V_CoreRegs), "core registers did not change shape");
		std::memcpy(&dst.Regs, &src.Regs, sizeof(V_CoreRegs));

		dst.CoreEnabled = src.CoreEnabled;
		dst.AttrBit0 = src.AttrBit0;
		dst.psxSoundDataTransferControl = src.psxSoundDataTransferControl;
		dst.psxSPUSTAT = src.psxSPUSTAT;

		// The streaming input path and the in-flight DMA. The IOP driver on
		// the other side of the restored RAM armed all of this and is
		// waiting on its completion interrupt; CheckDMAProgress() picks the
		// counters up and delivers it.
		dst.TSA = src.TSA;
		dst.ActiveTSA = src.ActiveTSA;
		dst.AdmaInProgress = src.AdmaInProgress;
		dst.DMABits = src.DMABits;
		dst.AutoDMACtrl = src.AutoDMACtrl;
		dst.InputDataLeft = src.InputDataLeft;
		dst.InputDataTransferred = src.InputDataTransferred;
		dst.InputPosWrite = src.InputPosWrite;
		dst.InputDataProgress = src.InputDataProgress;
		dst.DmaMode = src.DmaMode;
		dst.DmaStarted = src.DmaStarted;
		dst.AutoDmaFree = src.AutoDmaFree;
		dst.ReadSize = src.ReadSize;
		dst.IsDMARead = src.IsDMARead;
		dst.KeyOn = src.KeyOn;
		dst.DMAICounter = src.DMAICounter;
		dst.LastClock = psxRegs.cycle;

		// These eras memcpy'd the whole core into the block, so its two DMA
		// pointers are raw pointers into another process. Left null, the
		// write path rebuilds its own from the restored IOP-side DMA
		// registers (the savestate HACKFIX in AutoDMAReadBuffer and
		// FinishDMAwrite); the read path dereferences without that heal, so
		// rebuild it here — the era stored the transfer's end address in the
		// channel TADR register, and ReadSize words of it remain.
		dst.DMAPtr = nullptr;
		dst.DMARPtr = nullptr;
		if (dst.IsDMARead && dst.ReadSize)
		{
			const u32 tadr = (dst.Index == 0) ? HW_DMA4_TADR : HW_DMA7_TADR;
			dst.DMARPtr = reinterpret_cast<u16*>(iopPhysMem((tadr - dst.ReadSize * 2) & 0x1FFFFF));
		}
	}

	// The head of the block is era-invariant: the save id, 64KB of raw register
	// memory, 2MB of sample memory, then the self-version. Only the tail
	// (V_Core/V_Voice, and the mixer scalars after it) has been re-laid-out
	// upstream, repeatedly, without the self-version ever being bumped — so the
	// self-version cannot arbitrate the tail and SPU2Savestate::ThawIt must
	// never see one of these blocks.
	static constexpr size_t UNKREGS_OFFSET = 4;
	static constexpr size_t MEM_OFFSET = 0x10004;
	static constexpr size_t VERSION_OFFSET = 0x210004;
	static constexpr size_t CORES_OFFSET = 0x210008;
	static constexpr size_t BLOCK_SIZE = CORES_OFFSET + 2 * sizeof(Core) + sizeof(Tail);
	static_assert(BLOCK_SIZE == 2172280, "every AetherSX2-era SPU2 block is this size");

	// The id and self-version every AetherSX2/NetherSX2-written block carries.
	// Spelled out rather than shared with the current save path so that bumping
	// ours does not silently start rejecting legacy files.
	static constexpr u32 SAVE_ID = 0x1227521;
	static constexpr u32 SAVE_VERSION = 0x000e;
} // namespace LegacySPU2

// Restores register and sample memory, and — mapped field-by-field through
// RestoreCore — each core's mixer, voice, streaming and DMA state, then the
// SPDIF/ring/playmode tail. What does not carry across is each voice's position
// inside its current 28-sample decode block, and the two host DMA pointers,
// which the engine rebuilds from the restored IOP-side DMA registers.
s32 SPU2freezeLegacy(const void* data, size_t size)
{
	using namespace LegacySPU2;

	if (size != BLOCK_SIZE)
	{
		Console.Error("(LegacyState) SPU2 block is %zu bytes, not the %zu an AetherSX2-era block occupies.",
			size, BLOCK_SIZE);
		return -1;
	}

	const u8* const block = static_cast<const u8*>(data);
	u32 id, version;
	std::memcpy(&id, block, sizeof(id));
	std::memcpy(&version, block + VERSION_OFFSET, sizeof(version));

	if (id != SAVE_ID || version != SAVE_VERSION)
	{
		Console.Error("(LegacyState) SPU2 block is not an AetherSX2-era block (id %08X, version %X).", id, version);
		return -1;
	}

	// Wipes the register and sample memory and re-inits both cores, so it has
	// to happen before the memory is copied back in.
	SPU2::Reset(false);

	std::memcpy(spu2regs, block + UNKREGS_OFFSET, 0x10000);
	std::memcpy(_spu2mem, block + MEM_OFFSET, 0x200000);

	// The block came out of a zip read buffer, so copy each structure out
	// rather than reading it in place at an unknown alignment.
	for (int c = 0; c < 2; c++)
	{
		Core core;
		std::memcpy(&core, block + CORES_OFFSET + c * sizeof(core), sizeof(core));
		RestoreCore(Cores[c], core);
	}

	// The digital-output registers, the input ring positions, the sample
	// counter and the play mode — kept coherent with the ring contents and
	// stream counters restored above. lClocks is resynced below instead of
	// taken from the block: it was 32 bits in these eras, and the resync
	// against the restored IOP clock is exact anyway.
	Tail tail;
	std::memcpy(&tail, block + CORES_OFFSET + 2 * sizeof(Core), sizeof(tail));
	Spdif.Out = tail.SpdifOut;
	Spdif.Info = tail.SpdifInfo;
	Spdif.Unknown1 = tail.SpdifUnknown1;
	Spdif.Mode = tail.SpdifMode;
	Spdif.Media = tail.SpdifMedia;
	Spdif.Unknown2 = tail.SpdifUnknown2;
	Spdif.Protection = tail.SpdifProtection;
	OutPos = tail.OutPos;
	InputPos = tail.InputPos;
	Cycles = tail.Cycles;
	PlayMode = tail.PlayMode;

	// The ADPCM decode cache indexes sample memory, which just changed under
	// it. (The native thaw wipes it for the same reason.)
	std::memset(pcm_cache_data, 0, pcm_BlockCount * sizeof(PcmCacheEntry));

	lClocks = psxRegs.cycle;

	return 0;
}

// ============================================================================
//  0x9A54 — RetroSystem PS2 up to 1.0.23
// ============================================================================
// A far smaller delta than the AetherSX2 one above: by 1.0.23 the mixer had
// already reached today's V_VolumeSlide / V_ADSR / V_Reverb / V_CoreRegs /
// V_SPDIF / gate shapes, so those are reused verbatim here and none of the
// volume or envelope remapping above applies. What did move afterwards is
// three things, and only those are redeclared:
//
//   * V_Voice traded the pre-decode-FIFO fields (PlayCycle, LoopCycle, the
//     pending-loop pair, SPc, PV4..PV1, NextCrest, SCurrent) for DecodeFifo
//     plus its two positions;
//   * V_Core widened LastClock to 64 bits and gained KeyOff;
//   * the block tail widened lClocks to 64 bits.
//
// None of that moved SPU2Savestate::SAVE_VERSION, which is 0x000e on both
// sides — so ThawIt would accept an 0x9A54 block and read it at the wrong
// offsets, down to the DMA pointer fix-up. Hence a mapper, as for the older
// eras.
namespace State9A54SPU2
{
	// Both host DMA pointers are stored as byte offsets into IOP memory (or the
	// all-ones sentinel for null), exactly as they are today: 1.0.23 ran the
	// same FIX_POINTER pass on the way out. So they carry across, unlike the
	// AetherSX2 era's raw host pointers.
	static constexpr u64 NULL_POINTER = ~static_cast<u64>(0);
	static constexpr u64 IOP_MEM_MASK = 0x1FFFFF;

	struct Voice
	{
		u32 PlayCycle;
		u32 LoopCycle;
		u32 PendingLoopStartA;
		bool PendingLoopStart;
		V_VolumeSlideLR Volume;
		V_ADSR ADSR;
		u16 Pitch;
		u32 LoopStartA;
		u32 StartA;
		u32 NextA;
		s32 Prev1;
		s32 Prev2;
		bool Modulated;
		bool Noise;
		s8 LoopMode;
		s8 LoopFlags;
		s32 SP;
		s32 SPc;
		s32 PV4, PV3, PV2, PV1;
		s32 OutX;
		s32 NextCrest;
		u64 SBuffer; // s16* in the original — a host pointer, never dereferenced
		s32 SCurrent;
	};

	struct Core
	{
		u32 Index;
		V_VoiceGates VoiceGates[V_Core::NumVoices];
		V_CoreGates DryGate;
		V_CoreGates WetGate;
		V_VolumeSlideLR MasterVol;
		V_VolumeLR ExtVol;
		V_VolumeLR InpVol;
		V_VolumeLR FxVol;
		Voice Voices[V_Core::NumVoices];
		u32 IRQA;
		u32 TSA;
		u32 ActiveTSA;
		bool IRQEnable;
		bool FxEnable;
		bool Mute;
		bool AdmaInProgress;
		s8 DMABits;
		u8 NoiseClk;
		u32 NoiseCnt;
		u32 NoiseOut;
		u16 AutoDMACtrl;
		s32 DMAICounter;
		u32 LastClock;
		u32 InputDataLeft;
		u32 InputDataTransferred;
		u32 InputPosWrite;
		u32 InputDataProgress;
		V_Reverb Revb;
		s16 RevbDownBuf[2][64 * 2];
		s16 RevbUpBuf[2][64 * 2];
		u32 RevbSampleBufPos;
		u32 EffectsStartA;
		u32 EffectsEndA;
		V_CoreRegs Regs;
		StereoOut32 LastEffect;
		u8 CoreEnabled;
		u8 AttrBit0;
		u8 DmaMode;
		bool DmaStarted;
		u32 AutoDmaFree;
		u64 DMAPtr;  // u16* in the original
		u64 DMARPtr; // ditto
		u32 ReadSize;
		bool IsDMARead;
		u32 KeyOn;
		u16 psxSoundDataTransferControl;
		u16 psxSPUSTAT;
	};

	// The head of the block and the scalars behind the cores are the same in
	// every era: id, 64KB of raw register memory, 2MB of sample memory, then
	// the self-version, the cores, and a 32-byte tail that 0x9A54 shares
	// byte-for-byte with the AetherSX2 one (lClocks was still 32 bits in both).
	using Tail = LegacySPU2::Tail;
	static constexpr size_t UNKREGS_OFFSET = 4;
	static constexpr size_t MEM_OFFSET = 0x10004;
	static constexpr size_t VERSION_OFFSET = 0x210004;
	static constexpr size_t CORES_OFFSET = 0x210008;
	static constexpr size_t BLOCK_SIZE = CORES_OFFSET + 2 * sizeof(Core) + sizeof(Tail);

	// Proven against the 1.0.23 headers themselves: compiling that tree's
	// SPU2/defs.h with this NDK gives sizeof(V_Voice) == 176, sizeof(V_Core) ==
	// 6000, and its DataBlock == 2174728 bytes — the three numbers below. The
	// entry size is checked against BLOCK_SIZE again at load time, so a real
	// file that disagrees is refused rather than read at the wrong offsets.
	static_assert(sizeof(Voice) == 176, "V_Voice in RetroSystem PS2 1.0.23 is 176 bytes");
	static_assert(sizeof(Core) == 6000, "V_Core in RetroSystem PS2 1.0.23 is 6000 bytes");
	static_assert(BLOCK_SIZE == 2174728, "the 0x9A54 SPU2 block is 2174728 bytes");

	static constexpr u32 SAVE_ID = 0x1227521;
	static constexpr u32 SAVE_VERSION = 0x000e;

	// Restores a host DMA pointer from the stored IOP-memory offset, the
	// inverse of the FIX_POINTER pass in SPU2Savestate::FreezeIt. Anything
	// outside IOP memory is treated as absent rather than trusted: the value
	// comes off disk and is dereferenced by the transfer paths.
	static u16* UnfixPointer(u64 stored)
	{
		if (stored == NULL_POINTER || stored > IOP_MEM_MASK)
			return nullptr;

		return reinterpret_cast<u16*>(iopPhysMem(0) + static_cast<size_t>(stored));
	}

	static void RestoreVoice(V_Voice& dst, const Voice& src)
	{
		dst.Volume = src.Volume;
		dst.ADSR = src.ADSR;
		dst.Pitch = src.Pitch;
		dst.LoopStartA = src.LoopStartA;
		dst.StartA = src.StartA;
		dst.NextA = src.NextA;
		dst.Prev1 = src.Prev1;
		dst.Prev2 = src.Prev2;
		dst.Modulated = src.Modulated;
		dst.Noise = src.Noise;
		dst.LoopMode = src.LoopMode;
		dst.LoopFlags = src.LoopFlags;
		dst.SP = src.SP;
		dst.OutX = src.OutX;

		// The sample-decode pipeline was re-architected after 1.0.23 (PV1..PV4
		// and SCurrent gave way to a decode FIFO), so the position inside the
		// current 28-sample block does not carry across and the block restarts
		// — a sub-millisecond hiccup per voice, once. The FIFO and its two
		// positions stay at the values SPU2::Reset left, and the buffer points
		// at the cache entry for the current read address, as the native thaw
		// does. The index is masked because NextA comes off disk.
		dst.SBuffer = pcm_cache_data[(dst.NextA & 0xFFFFF) / pcm_WordsPerBlock].Sampledata;
	}

	static void RestoreCore(V_Core& dst, const Core& src)
	{
		for (uint v = 0; v < V_Core::NumVoices; v++)
		{
			dst.VoiceGates[v] = src.VoiceGates[v];
			RestoreVoice(dst.Voices[v], src.Voices[v]);
		}

		dst.DryGate = src.DryGate;
		dst.WetGate = src.WetGate;
		dst.MasterVol = src.MasterVol;
		dst.ExtVol = src.ExtVol;
		dst.InpVol = src.InpVol;
		dst.FxVol = src.FxVol;

		dst.IRQA = src.IRQA;
		dst.TSA = src.TSA;
		dst.ActiveTSA = src.ActiveTSA;
		dst.IRQEnable = src.IRQEnable;
		dst.FxEnable = src.FxEnable;
		dst.Mute = src.Mute;
		dst.AdmaInProgress = src.AdmaInProgress;
		dst.DMABits = src.DMABits;
		dst.NoiseClk = src.NoiseClk;
		dst.NoiseCnt = src.NoiseCnt;
		dst.NoiseOut = src.NoiseOut;
		dst.AutoDMACtrl = src.AutoDMACtrl;
		dst.DMAICounter = src.DMAICounter;
		dst.InputDataLeft = src.InputDataLeft;
		dst.InputDataTransferred = src.InputDataTransferred;
		dst.InputPosWrite = src.InputPosWrite;
		dst.InputDataProgress = src.InputDataProgress;
		dst.Revb = src.Revb;
		std::memcpy(dst.RevbDownBuf, src.RevbDownBuf, sizeof(dst.RevbDownBuf));
		std::memcpy(dst.RevbUpBuf, src.RevbUpBuf, sizeof(dst.RevbUpBuf));
		dst.RevbSampleBufPos = src.RevbSampleBufPos;
		dst.EffectsStartA = src.EffectsStartA;
		dst.EffectsEndA = src.EffectsEndA;
		dst.Regs = src.Regs;
		dst.LastEffect = src.LastEffect;
		dst.CoreEnabled = src.CoreEnabled;
		dst.AttrBit0 = src.AttrBit0;
		dst.DmaMode = src.DmaMode;
		dst.DmaStarted = src.DmaStarted;
		dst.AutoDmaFree = src.AutoDmaFree;
		dst.DMAPtr = UnfixPointer(src.DMAPtr);
		dst.DMARPtr = UnfixPointer(src.DMARPtr);
		dst.ReadSize = src.ReadSize;
		dst.IsDMARead = src.IsDMARead;
		dst.KeyOn = src.KeyOn;
		dst.psxSoundDataTransferControl = src.psxSoundDataTransferControl;
		dst.psxSPUSTAT = src.psxSPUSTAT;

		// KeyOff did not exist at 1.0.23: a key-off is edge-triggered by a
		// register write, so there is no pending one to restore. Left at the
		// zero SPU2::Reset put there.

		// The DMA interrupt deadline is an IOP-clock stamp, and psxRegs is
		// already thawed by the time the entries load, so it widens against the
		// restored clock. Never ahead of it: CheckDMAProgress subtracts the two
		// as u64, and an ahead baseline would read as a ~2^64 elapsed.
		dst.LastClock = SaveStateLegacy::WidenCycle(src.LastClock,
			static_cast<u32>(psxRegs.cycle), psxRegs.cycle);
		if (dst.LastClock > psxRegs.cycle)
			dst.LastClock = psxRegs.cycle;
	}
} // namespace State9A54SPU2

// Restores register and sample memory, then each core's mixer, voice,
// streaming and DMA state, then the SPDIF/ring/playmode tail. Everything is
// carried except each voice's position inside its current 28-sample decode
// block, which the re-architected decoder cannot express.
s32 SPU2freeze9A54(const void* data, size_t size)
{
	using namespace State9A54SPU2;

	if (size != BLOCK_SIZE)
	{
		Console.Error("(LegacyState) SPU2 block is %zu bytes, not the %zu a 0x9A54 block occupies.",
			size, BLOCK_SIZE);
		return -1;
	}

	const u8* const block = static_cast<const u8*>(data);
	u32 id, version;
	std::memcpy(&id, block, sizeof(id));
	std::memcpy(&version, block + VERSION_OFFSET, sizeof(version));

	if (id != SAVE_ID || version != SAVE_VERSION)
	{
		Console.Error("(LegacyState) SPU2 block is not a 0x9A54 block (id %08X, version %X).", id, version);
		return -1;
	}

	// Wipes the register and sample memory and re-inits both cores, so it has
	// to happen before the memory is copied back in.
	SPU2::Reset(false);

	std::memcpy(spu2regs, block + UNKREGS_OFFSET, 0x10000);
	std::memcpy(_spu2mem, block + MEM_OFFSET, 0x200000);

	// The block came out of a zip read buffer, so copy each structure out
	// rather than reading it in place at an unknown alignment.
	for (int c = 0; c < 2; c++)
	{
		auto core = std::make_unique<Core>();
		std::memcpy(core.get(), block + CORES_OFFSET + c * sizeof(Core), sizeof(Core));
		RestoreCore(Cores[c], *core);
	}

	Tail tail;
	std::memcpy(&tail, block + CORES_OFFSET + 2 * sizeof(Core), sizeof(tail));
	Spdif.Out = tail.SpdifOut;
	Spdif.Info = tail.SpdifInfo;
	Spdif.Unknown1 = tail.SpdifUnknown1;
	Spdif.Mode = tail.SpdifMode;
	Spdif.Media = tail.SpdifMedia;
	Spdif.Unknown2 = tail.SpdifUnknown2;
	Spdif.Protection = tail.SpdifProtection;
	OutPos = tail.OutPos;
	InputPos = tail.InputPos;
	Cycles = tail.Cycles;
	PlayMode = tail.PlayMode;

	// The ADPCM decode cache indexes sample memory, which just changed under
	// it. (The native thaw wipes it for the same reason.)
	std::memset(pcm_cache_data, 0, pcm_BlockCount * sizeof(PcmCacheEntry));

	// Same domain and same guard as V_Core::LastClock above.
	lClocks = SaveStateLegacy::WidenCycle(tail.lClocks, static_cast<u32>(psxRegs.cycle), psxRegs.cycle);
	if (lClocks > psxRegs.cycle)
		lClocks = psxRegs.cycle;

	return 0;
}
