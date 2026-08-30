// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#include "HostSys.h"
#include "Console.h"
#include "VectorIntrin.h"
#include "fmt/format.h"

#ifndef __APPLE__
#include "cpuinfo.h"
#endif

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#if defined(ARCH_ARM64) && !defined(_MSC_VER) && defined(__linux__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
// Older uapi headers predate the bit; its value is fixed by the kernel ABI.
#ifndef HWCAP_EVTSTRM
#define HWCAP_EVTSTRM (1 << 2)
#endif
#endif

static u32 PAUSE_TIME = 0;

static void MultiPause()
{
#ifdef ARCH_X86
	_mm_pause();
	_mm_pause();
	_mm_pause();
	_mm_pause();
	_mm_pause();
	_mm_pause();
	_mm_pause();
	_mm_pause();
#elif defined(ARCH_ARM64) && defined(_MSC_VER)
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
	__isb(_ARM64_BARRIER_SY);
#elif defined(ARCH_ARM64)
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
	__asm__ __volatile__("isb");
#else
#error Unknown architecture.
#endif
}

static u32 MeasurePauseTime()
{
	// A tick isn't a fixed time unit (see GetCPUTicks()), so this loop works in raw
	// ticks and only converts to ns once it has enough. One MultiPause takes 20ns on
	// a fast Haswell, 400ns on a slow Skylake, 83ns for the eight isb on a Cortex-A78C.
	// Start small and double the batch until the tick delta clears 100.
	for (int testcnt = 64; true; testcnt *= 2)
	{
		u64 start = GetCPUTicks();
		for (int i = 0; i < testcnt; i++)
		{
			MultiPause();
		}
		u64 time = GetCPUTicks() - start;
		if (time > 100)
		{
			u64 nanos = (time * 1000000000) / GetTickFrequency();
			return (nanos / testcnt) + 1;
		}
	}
}

__noinline static void UpdatePauseTime()
{
	u64 wait = GetCPUTicks() + GetTickFrequency() / 100; // Wake up processor (spin for 10ms)
	while (GetCPUTicks() < wait)
		;
	u32 pause = MeasurePauseTime();
	// Take a few measurements in case something weird happens during one
	// (e.g. OS interrupt)
	for (int i = 0; i < 4; i++)
		pause = std::min(pause, MeasurePauseTime());
	PAUSE_TIME = pause;
	DevCon.WriteLn("MultiPause time: %uns", pause);
}

u32 ShortSpin()
{
	u32 inc = PAUSE_TIME;
	if (inc == 0) [[unlikely]]
	{
		UpdatePauseTime();
		inc = PAUSE_TIME;
	}

	u32 time = 0;
	// Sleep for approximately 500ns
	for (; time < 500; time += inc)
		MultiPause();

	return time;
}

#if defined(ARCH_ARM64) && !defined(_MSC_VER)
// Stop executing until another core stores to `word`, using the local exclusive
// monitor as the watchpoint: LDAXR arms it, and the store that clears it raises
// the event WFE is waiting on. A store landing between the LDAXR and the WFE
// clears the monitor too, so the wake cannot be missed.
//
// WFE is not a yield or a kernel block: the thread stays runnable, so a
// co-resident thread is preempted exactly as it was against the isb spin
// (measured: it keeps ~50% of its throughput either way, against 100% when
// the waiter blocks in a futex instead).
//
// The SEVL/WFE pair comes first because the event register is one sticky bit:
// anything that cleared the monitor earlier leaves it set, and the WFE below
// would then return without parking. SEVL sets it, the first WFE consumes it.
//
// Nothing clears the monitor on the way out, deliberately. Clearing it is
// itself a wake-up event, so a CLREX here would set the event register for the
// next iteration and the loop would stop parking altogether — on a Cortex-A78C
// waiting on a poster 100µs away, 3.5 wake-ups per wait as written against 6708
// with a CLREX added. Linux's arm64 __cmpwait omits it for the same reason.
//
// A monitor that never fires is a latency cost rather than a hang ONLY where the
// periodic event stream runs (~33µs on the Cortex-A78C this was tuned on). That is a
// kernel option, not a guarantee, so callers reach this through HasEventStream() below
// — without the event stream a WFE with no poster left never returns at all.
static void MonitoredWait(const std::atomic<s32>& word, s32 expected)
{
	s32 seen;
	__asm__ __volatile__(
		"sevl\n"
		"wfe\n"
		"ldaxr %w0, [%1]\n"
		"cmp   %w0, %w2\n"
		"b.ne  1f\n"
		"wfe\n"
		"1:\n"
		: "=&r"(seen)
		: "r"(&word), "r"(expected)
		: "cc", "memory");
	(void)seen;
}
#endif

#if defined(ARCH_ARM64) && !defined(_MSC_VER) && defined(__linux__)
// Whether the architected timer's event stream is running, i.e. whether WFE has a
// periodic wake-up at all.
//
// MonitoredWait() above parks in WFE with exactly two ways out: a store that clears
// the exclusive monitor, or the event stream. The store is not guaranteed - a waiter
// whose poster has gone quiet (MTVU with the VM paused, ring buffer empty) has nobody
// left to write - so the event stream is the only backstop, and it is a kernel option
// (CONFIG_ARM_ARCH_TIMER_EVTSTREAM), not an architectural promise. Where it is off,
// that WFE never returns.
//
// That is not a latency cost, it is a hang with a misleading shape: WFE is not a yield,
// so the thread stays runnable and the kernel keeps charging it CPU time. It shows up as
// a thread pegged at 100% of a core in state R - measured on an Exynos 850 (8x Cortex-A55),
// MTVU burning 724 ticks in ~7s of wall clock with the VM paused and the screen off.
// The SPIN_TIME_NS budget cannot save it: WaitForWorkWithSpin() only checks that budget
// BETWEEN calls to ShortSpinOn(), so a call that never returns is never accounted for and
// the m_sema.Wait() below it is never reached.
//
// AT_HWCAP's HWCAP_EVTSTRM is the kernel telling us directly (same bit as the "evtstrm"
// flag in /proc/cpuinfo). Without it, fall back to the isb spin, which is bounded by
// construction and lets the caller reach its sleep.
//
// Resolved on first use rather than at static-init time, deliberately: the answer has to
// reach the log, and at static-init the log file is not open yet, so the warning would be
// written into nothing.
static bool HasEventStream()
{
	static const bool present = []() {
		const bool p = (getauxval(AT_HWCAP) & HWCAP_EVTSTRM) != 0;
		if (!p)
			Console.Warning("HostSys: ARM64 event stream absent, WFE spin disabled");
		return p;
	}();
	return present;
}
#endif

u32 ShortSpinOn(const std::atomic<s32>& word, s32 expected)
{
#if defined(ARCH_ARM64) && !defined(_MSC_VER)
#if defined(__linux__)
	if (!HasEventStream())
		return ShortSpin();
#endif
	const u64 start = GetCPUTicks();
	MonitoredWait(word, expected);
	// Charge unmeasurably short waits as one tick, not zero: the caller
	// accumulates this against SPIN_TIME_NS, and a zero would stall that count
	// forever.
	const u64 elapsed = std::max<u64>(GetCPUTicks() - start, 1);
	return static_cast<u32>((elapsed * 1000000000) / GetTickFrequency());
#else
	(void)word;
	(void)expected;
	return ShortSpin();
#endif
}

static u32 GetSpinTime()
{
	if (char* req = getenv("WAIT_SPIN_MICROSECONDS"))
	{
		return 1000 * atoi(req);
	}
	else
	{
		return 50 * 1000; // 50µs
	}
}

const u32 SPIN_TIME_NS = GetSpinTime();

#ifdef __APPLE__
// https://alastairs-place.net/blog/2013/01/10/interesting-os-x-crash-report-tidbits/
// https://opensource.apple.com/source/WebKit2/WebKit2-7608.3.10.0.3/Platform/spi/Cocoa/CrashReporterClientSPI.h.auto.html
struct crash_info_t
{
	u64 version;
	u64 message;
	u64 signature;
	u64 backtrace;
	u64 message2;
	u64 reserved;
	u64 reserved2;
};
#define CRASH_ANNOTATION __attribute__((used, section("__DATA,__crash_info")))
#define CRASH_VERSION 4
extern "C" crash_info_t gCRAnnotations CRASH_ANNOTATION = { CRASH_VERSION };
#endif

void AbortWithMessage(const char* msg)
{
#ifdef __APPLE__
	gCRAnnotations.message = reinterpret_cast<size_t>(msg);
	// Some macOS's seem to have issues displaying non-static `message`s, so throw it in here too
	gCRAnnotations.backtrace = gCRAnnotations.message;
#endif
	abort();
}

#ifndef __APPLE__
// MacOS version is in DarwinMisc

#ifdef __aarch64__
// cpuinfo library often returns empty/unknown names on ARM Linux.
// Fall back to reading MIDR fields from /proc/cpuinfo.
static std::string DetectArmCPUName()
{
	FILE* f = fopen("/proc/cpuinfo", "r");
	if (!f)
		return {};

	u32 implementer = 0, part = 0;
	char line[256];
	while (fgets(line, sizeof(line), f))
	{
		if (sscanf(line, "CPU implementer : %x", &implementer) == 1)
			continue;
		if (sscanf(line, "CPU part : %x", &part) == 1)
			break; // got both from first core
	}
	fclose(f);

	// Map common implementer+part to names
	if (implementer == 0x41) // ARM Ltd
	{
		switch (part)
		{
			case 0xd03: return "ARM Cortex-A53";
			case 0xd04: return "ARM Cortex-A35";
			case 0xd05: return "ARM Cortex-A55";
			case 0xd07: return "ARM Cortex-A57";
			case 0xd08: return "ARM Cortex-A72";
			case 0xd09: return "ARM Cortex-A73";
			case 0xd0a: return "ARM Cortex-A75";
			case 0xd0b: return "ARM Cortex-A76";
			case 0xd0c: return "ARM Neoverse N1";
			case 0xd0d: return "ARM Cortex-A77";
			case 0xd40: return "ARM Neoverse V1";
			case 0xd41: return "ARM Cortex-A78";
			case 0xd44: return "ARM Cortex-X1";
			case 0xd46: return "ARM Cortex-A510";
			case 0xd47: return "ARM Cortex-A710";
			case 0xd48: return "ARM Cortex-X2";
			case 0xd4d: return "ARM Cortex-A715";
			case 0xd4e: return "ARM Cortex-X3";
			case 0xd80: return "ARM Cortex-A520";
			case 0xd81: return "ARM Cortex-A720";
			case 0xd82: return "ARM Cortex-X4";
		}
	}
	else if (implementer == 0x51) // Qualcomm
	{
		switch (part)
		{
			case 0x802: return "Qualcomm Kryo 385 Gold";
			case 0x803: return "Qualcomm Kryo 385 Silver";
			case 0xc00: return "Qualcomm Falkor";
			case 0x001: return "Qualcomm Oryon";
		}
	}
	else if (implementer == 0x61) // Apple
	{
		switch (part)
		{
			case 0x022: return "Apple M1 Icestorm";
			case 0x023: return "Apple M1 Firestorm";
			case 0x032: return "Apple M2 Blizzard";
			case 0x033: return "Apple M2 Avalanche";
		}
	}

	if (implementer != 0 && part != 0)
		return fmt::format("ARM (impl 0x{:02X} part 0x{:03X})", implementer, part);
	return {};
}
#endif

static CPUInfo CalcCPUInfo()
{
	CPUInfo out;
	const cpuinfo_package* pkg = cpuinfo_get_package(0);
	out.name = (pkg && pkg->name[0] != '\0') ? pkg->name : "Unknown";

#if defined(__ANDROID__)
	// cpuinfo's bundled SoC database may not recognise newer chips (e.g. QCS8550),
	// leaving the package name empty or "Unknown". Fall back to the Android SoC build
	// properties so the OSD shows a real name instead of "Unknown".
	if (out.name.empty() || out.name.find("Unknown") != std::string::npos)
	{
		char model[PROP_VALUE_MAX] = {};
		char manuf[PROP_VALUE_MAX] = {};
		__system_property_get("ro.soc.model", model);
		__system_property_get("ro.soc.manufacturer", manuf);
		if (model[0] != '\0')
			out.name = (manuf[0] != '\0') ? (std::string(manuf) + " " + model) : std::string(model);
		else if (manuf[0] != '\0')
			out.name = manuf;
	}
#endif

#ifdef __aarch64__
	// cpuinfo often returns empty/unknown on ARM Linux — use MIDR fallback
	if (out.name.empty() || out.name.find("Unknown") != std::string::npos || out.name == "unknown")
	{
		std::string arm_name = DetectArmCPUName();
		if (!arm_name.empty())
			out.name = std::move(arm_name);
	}
#endif


	out.num_threads = cpuinfo_get_processors_count();
	out.num_clusters = cpuinfo_get_clusters_count();
	out.num_big_cores = 0;
	out.num_small_cores = 0;
	const cpuinfo_cluster* clusters = cpuinfo_get_clusters();
	uint64_t big_freq = 0;
	for (uint32_t i = 0; i < out.num_clusters; i++)
	{
		const cpuinfo_cluster& cluster = clusters[i];
		if (cluster.frequency > big_freq)
		{
			out.num_small_cores += out.num_big_cores;
			out.num_big_cores = cluster.core_count;
			big_freq = cluster.frequency;
		}
		else if (cluster.frequency == big_freq)
		{
			out.num_big_cores += cluster.core_count;
		}
		else
		{
			out.num_small_cores += cluster.core_count;
		}
	}
	return out;
}

const CPUInfo& GetCPUInfo()
{
	static const CPUInfo info = CalcCPUInfo();
	return info;
}
#endif
