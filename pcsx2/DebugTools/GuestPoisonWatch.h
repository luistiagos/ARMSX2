// SPDX-FileCopyrightText: 2026 ARMSX2 Contributors
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include "common/Pcsx2Defs.h"

// Detector de valor-veneno na RAM guest.
//
// Existe por um bug especifico: Shadow of the Colossus aborta o CPU Thread com
// `Unhandled page fault: sig=11 addr=0x12218 write=0`, reproduzindo BYTE A BYTE em dois SoCs
// diferentes (Exynos 850 / Mali-G52 e MediaTek / Mali-G615), com `ee pc=44bb910d`. Dois
// fabricantes de driver, duas versoes de Android, mesma assinatura -- portanto o defeito e do
// recompilador, nao do caminho grafico. Ver
// docs/bugs/open/sotc-jit-page-fault-addr-12218_2026-08-25T02-18.md.
//
// `0x44bb910d` nao e um PC valido: e dado interpretado como PC. A hipotese e que uma estrutura na
// RAM guest que guarda um alvo de salto seja sobrescrita por uma transferencia de DMA, e a
// corrupcao ser DETERMINISTICA e o que torna esta abordagem viavel -- basta olhar o endereco depois
// de cada transferencia, sem watchpoint de hardware e sem instrumentar o recompilador.
//
// ⚠️ O par (endereco, valor) vigiado vem da analise do crash registrada no handoff, NAO de uma
// derivacao a partir do primeiro principio. Se este detector nunca disparar num crash reproduzido,
// a primeira coisa a duvidar e o ENDERECO, nao a hipotese de DMA: o valor `0x44bb910d` esta
// diretamente observado nos dois tombstones; `0x19430` esta inferido.
namespace GuestPoisonWatch
{
	/// Zera o estado. Chamado do `hwReset()`, para que um reset da VM nao herde a deteccao anterior.
	void Reset();

	/// Chamado do `hwDmacIrq()`, ou seja, uma vez por sinalizacao de canal de DMA. Custa uma
	/// leitura de 32 bits alinhada de memoria quente; so produz saida na TRANSICAO para o valor
	/// vigiado, entao nao ha como inundar o log.
	void OnDmacIrq(int channel);
} // namespace GuestPoisonWatch
