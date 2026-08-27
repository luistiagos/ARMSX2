// SPDX-FileCopyrightText: 2026 ARMSX2 Contributors
// SPDX-License-Identifier: GPL-3.0+

#include "DebugTools/GuestPoisonWatch.h"

#include "Common.h"
#include "Hardware.h"
#include "MemoryTypes.h"
#include "R5900.h"

#include "common/Console.h"

#include "fmt/format.h"

#include <array>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace
{
struct PoisonSite
{
	const char* name;
	u32 address; ///< offset fisico dentro da RAM principal do EE
	u32 value;
};

// Uma entrada. Acrescentar outra e uma linha; a estrutura existe justamente porque o endereco
// abaixo e a parte incerta da hipotese (ver o cabecalho).
constexpr std::array<PoisonSite, 1> s_sites = {{
	{"sotc-jump-target", 0x19430u, 0x44bb910du},
}};

std::array<bool, s_sites.size()> s_armed{};

const char* ChannelName(int channel)
{
	switch (channel)
	{
		case DMAC_VIF0: return "VIF0";
		case DMAC_VIF1: return "VIF1";
		case DMAC_GIF: return "GIF";
		case DMAC_FROM_IPU: return "fromIPU";
		case DMAC_TO_IPU: return "toIPU";
		case DMAC_SIF0: return "SIF0";
		case DMAC_SIF1: return "SIF1";
		case DMAC_SIF2: return "SIF2";
		case DMAC_FROM_SPR: return "fromSPR";
		case DMAC_TO_SPR: return "toSPR";
		case DMAC_STALL_SIS: return "stall-SIS";
		case DMAC_MFIFO_EMPTY: return "mfifo-empty";
		case DMAC_BUS_ERROR: return "bus-error";
		default: return "?";
	}
}

/// Os registradores do canal. `nullptr` para os codigos que nao sao canal (stall, MFIFO vazio,
/// erro de barramento) -- eles chegam pelo mesmo `hwDmacIrq`, e confundi-los com um canal produziria
/// um `madr` de outro lugar.
const DMACh* ChannelRegisters(int channel)
{
	switch (channel)
	{
		case DMAC_VIF0: return &vif0ch;
		case DMAC_VIF1: return &vif1ch;
		case DMAC_GIF: return &gifch;
		case DMAC_FROM_IPU: return &ipu0ch;
		case DMAC_TO_IPU: return &ipu1ch;
		case DMAC_SIF0: return &sif0ch;
		case DMAC_SIF1: return &sif1ch;
		case DMAC_SIF2: return &sif2dma;
		case DMAC_FROM_SPR: return &spr0ch;
		case DMAC_TO_SPR: return &spr1ch;
		default: return nullptr;
	}
}

void Emit(const std::string& line)
{
	// Fora do gate de Log::GetMaxLevel() de proposito, pelo mesmo motivo da linha GSBoot: numa
	// instalacao padrao todos os sinks nascem em NONE, entao Console.Warning desapareceria antes do
	// logcat. A reproducao deste bug e `adb logcat`, e ela precisa funcionar sem o usuario ligar
	// nada.
#ifdef __ANDROID__
	__android_log_print(ANDROID_LOG_WARN, "NDK_LOG", "PoisonWatch: %s", line.c_str());
#endif
	Console.Warning("PoisonWatch: %s", line.c_str());
}
} // namespace

void GuestPoisonWatch::Reset()
{
	s_armed.fill(false);
}

void GuestPoisonWatch::OnDmacIrq(int channel)
{
	if (!eeMem)
		return;

	for (size_t i = 0; i < s_sites.size(); i++)
	{
		const PoisonSite& site = s_sites[i];
		if ((site.address + sizeof(u32)) > sizeof(eeMem->Main))
			continue;

		const u32 current = *reinterpret_cast<const u32*>(&eeMem->Main[site.address]);
		const bool poisoned = (current == site.value);
		if (poisoned == s_armed[i])
			continue; // sem transicao: nada novo a dizer

		s_armed[i] = poisoned;
		if (!poisoned)
		{
			Emit(fmt::format("{} limpo em 0x{:08X} apos canal {} (ciclo {})", site.name, site.address,
				ChannelName(channel), cpuRegs.cycle));
			continue;
		}

		// Os quatro words ao redor ajudam a identificar QUAL estrutura foi atingida: um alvo de
		// salto isolado e um ponteiro sobrescrito tem vizinhancas bem diferentes.
		const u32 base = site.address & ~0xFu;
		const u32* window = reinterpret_cast<const u32*>(&eeMem->Main[base]);

		const DMACh* ch = ChannelRegisters(channel);
		std::string dma;
		if (ch)
		{
			dma = fmt::format("madr=0x{:08X} qwc={} tadr=0x{:08X} chcr=0x{:08X}", ch->madr, ch->qwc,
				ch->tadr, ch->chcr._u32);
		}
		else
		{
			// Nao e um canal: e stall/MFIFO vazio/erro de barramento chegando pelo mesmo caminho.
			dma = "sem registradores de canal (condicao, nao transferencia)";
		}

		Emit(fmt::format("{} DETECTADO: [0x{:08X}]=0x{:08X} canal={} {} ee_pc=0x{:08X} ciclo={} "
						 "janela[0x{:08X}]={:08X} {:08X} {:08X} {:08X}",
			site.name, site.address, current, ChannelName(channel), dma, cpuRegs.pc, cpuRegs.cycle,
			base, window[0], window[1], window[2], window[3]));
	}
}
