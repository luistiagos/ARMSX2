# TASK-0049: carregar savestates `0x9A54` da 1.0.23 no fork

- **Status:** aberta
- **Criada em:** 2026-08-28
- **Concluída em:** —
- **Feature:** nenhuma
- **Backlog:** [MIG-0001](../backlog/migracao/MIG-0001-savestates-legados-0x9A54.md)
- **Bugs que resolve:** [savestate-formato-9a54-rejeitado-pelo-fork](../bugs/open/savestate-formato-9a54-rejeitado-pelo-fork_2026-08-27T09-10.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0049:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

A 1.0.23 gravava savestates com `g_SaveVersion = 0x9A54 << 16`
(`version1: pcsx2/SaveState.h:28`); o fork está em `0x9A59` (`pcsx2/SaveState.h:29`). O portão de
versão corta na palavra alta, então todo `.p2s` da 1.0.23 é rejeitado depois da atualização.

O upstream do fork já mantém um leitor legado — `pcsx2/SaveStateLegacy.cpp`, 1.168 linhas — para as
eras AetherSX2 (`0x9A2C`) e NetherSX2 (`0x9A34`), com o helper `WidenCycle()` que resolve o caso
difícil (alargar um contador de 32 bits que **deu a volta**).

## O desenho, e por que não é o que o MIG-0001 propõe

O MIG-0001 §3 pede estruturas `FreezeData_v9A54` declaradas como variante da era `0x9A34`. **Isso
está errado, e o registro do bug já dizia por quê.** Duas medições, refeitas nesta task contra as
duas árvores:

| o que o leitor `0x9A34` assume | o que a `0x9A54` tem |
|---|---|
| bloco vuJIT de `2 × 160` bytes | `2 × 96` — `RecStubs.cpp:vuJITFreeze` grava `std::array<u8,96>` duas vezes, e o fork grava `microVU{0,1}.prog.lpState` com `static_assert(sizeof(microRegInfo) == 96)` (`arm64/microVU-arm64.h:192`) |
| SIO como despejo cru (`SIO0_BLOB_9A34 = 32`) | `g_Sio0.DoState(sw)` — o formato **moderno**, idêntico ao do fork |
| `V_VolumeSlide {s16 Reg_VOL; s32 Value; s8 Increment; s8 Mode;}` e ADSR de 31 bits | idênticos aos de hoje (`V_VolumeSlide`, `V_ADSR` sem diferença nenhuma) |

Seguir o caminho `0x9A34` desalinharia o blob em 128 bytes no vuJIT e escalaria volume errado. O
resultado não seria erro: seria estado corrompido — pior que a rejeição de hoje.

**A `0x9A54` não é uma era antiga.** É o formato atual **menos o alargamento dos contadores de
ciclo**. O leitor certo é um espelho do `FreezeInternals()` moderno que lê os campos estreitos e
passa cada um por `WidenCycle`, reaproveitando sem cópia todos os blocos invariantes.

## O levantamento bloco a bloco

Feito comparando `version1` (`D:/projects/play2/ARMSX2/app/src/main/cpp`) com esta árvore, função a
função e struct a struct, não por varredura de cabeçalho.

**Idênticos no fio (fonte da função de freeze byte a byte igual, e os tipos que ela congela sem
diferença):** `memFreeze`, `gsFreeze`, `vuJITFreeze` (96 = 96), `vif0Freeze`, `vif1Freeze`,
`sifFreeze`, `ipuFreeze`, `ipuDmaFreeze`, `gifFreeze`, `gifPathFreeze`, `gifDmaFreeze`, `sprFreeze`,
`mtvuFreeze`, `cdrFreeze`, `cdvdFreeze`, `deci2Freeze`, `InputRecordingFreeze`, `handleFreeze`,
`FreezeBios`, `FreezeTag`, `Sio0::DoState`, `MultitapProtocol::DoState`, e a lista
`SavestateEntries[]` inteira. `vmFreeze` difere só numa chamada de log; `Sio2::DoState` só em
renomeações (`send3`→`CmdQueue` etc.) com os mesmos tipos na mesma ordem.

**Diferem, e só pelo alargamento:**

| bloco | campos `u32`/`s32` na `0x9A54` que hoje são `u64`/`s64` |
|---|---|
| `cpuRegs` | `sCycle[32]`, `cycle`, `nextEventCycle`, `lastEventCycle`, `lastCOP0Cycle`, `lastPERFCycle[2]` |
| `psxRegs` | `cycle`, `iopNextEventCycle`, `sCycle[32]` |
| bloco `Cycles` | `EEoCycle`, `nextStartCounter`, `psxNextStartCounter` |
| `rcntFreeze` | `Counter::startCycle` (×4), `SyncCounter::startCycle` (×2), `nextStartCounter` |
| `psxRcntFreeze` | `psxCounter::startCycle` (×8), `psxNextStartCounter` |
| `vuMicroFreeze` | `VURegs::cycle`, `VURegs::nextBlockCycles` (`s32`→`s64`, **delta**, extensão de sinal), `VURegs::xgkicklastcycle`, e `sCycle` de `fmacPipe`/`fdivPipe`/`efuPipe`/`ialuPipe` |
| `fpuRegs` | nada — a `0x9A54` grava `fpuRegisters` de 264 bytes e o fork grava `fpuRegistersWire`, com `static_assert(sizeof(fpuRegistersWire) == 264)`. Compatível. |

**A descoberta que o MIG-0001 não previu — `SPU2.bin`.** O bloco tem *self-version* própria
(`SAVE_VERSION = 0x000e`) e ela **não mudou** entre as duas árvores, enquanto o miolo mudou:

| | `0x9A54` | fork |
|---|---|---|
| `V_Voice` | tem `PlayCycle`, `LoopCycle`, `PendingLoopStartA`, `PendingLoopStart`, `SPc`, `PV4..PV1`, `NextCrest`, `SCurrent` | trocou tudo isso por `DecodeFifo[32]`, `DecPosWrite`, `DecPosRead` |
| `V_Core` | `u32 LastClock`, sem `KeyOff` | `u64 LastClock`, `+ u32 KeyOff` |
| tail do `DataBlock` | `u32 lClocks` | `u64 lClocks` |

Como a self-version não se move, `SPU2Savestate::ThawIt()` **aceitaria** o bloco antigo e o leria com
o layout errado — inclusive o *fix-up* de ponteiro do `SBuffer`, que dispara `pxAssert`. É o mesmo
motivo pelo qual o fork já mantém `SPU2freezeLegacy` para as eras Aether/Nether. Precisa do
equivalente para a `0x9A54`; sem ele o critério de validação do MIG-0001 ("áudio restaura sem
crash") não pode passar.

`V_VolumeSlide`, `V_ADSR`, `V_Reverb`, `V_CoreRegs`, `V_SPDIF`, `V_VoiceGates`, `V_CoreGates`,
`StereoOut32` e `PcmCacheEntry` são **idênticos** — então o mapeamento reaproveita os tipos de hoje
e não repete o erro de escala de volume que a era Aether obrigou a corrigir.

## O que foi provado no build, e como

As afirmações de layout acima não são leitura de cabeçalho: os tamanhos foram medidos compilando as
**árvores de verdade** com o mesmo NDK (`aarch64-linux-android26`, clang do NDK 28.2.13676358).
Compilando os cabeçalhos da 1.0.23:

| | 1.0.23 | mirror declarado aqui |
|---|---|---|
| `cpuRegisters` | 1008 | `cpuRegisters_9A54` = 1008 ✔ |
| `psxRegisters` | 812 | `psxRegisters_9A54` = 812 ✔ |
| `fpuRegisters` | 264 | `fpuRegistersWire` = 264 ✔ |
| `Counter` / `SyncCounter` | 28 / 12 | 28 / 12 ✔ |
| `psxCounter` | 40 | `psxCounter_9A54` = 40 ✔ |
| `fmac/fdiv/efu/ialuPipe` | 40 / 32 / 28 / 12 | idem ✔ |
| `V_Voice` | 176 | `State9A54SPU2::Voice` = 176 ✔ |
| `V_Core` | 6000 | `State9A54SPU2::Core` = 6000 ✔ |
| `SPU2Savestate::DataBlock` | 2 174 728 | `BLOCK_SIZE` = 2 174 728 ✔ |

E, compilando o mesmo teste de tamanhos contra as duas árvores, saem **iguais**: `vifStruct` 144,
`GIF_Fifo` 260, `gifStruct` 28, `GS_SIGNAL` 12, `GS_FINISH` 2, `tIPU_BP` 48, `IPU_Fifo` 288,
`tIPU_cmd` 32, `decoder_t` 3028, `IPU1Status` 2, `cdvdStruct` 65 776, `cdrStruct` 39 352,
`V_SPDIF` 14, `tlbs` 16, `cachedTlbs_t` 976, `iopMem->Sif` 256, `gifUnit.stat` 4,
`gifUnit.lastTranType` 4.

`Gif_Path` é a única exceção aparente — 416 na 1.0.23 contra 288 aqui — e não é uma: `gifPathFreeze`
grava `sizeof(gifPath) - sizeof(gifPath.mtvu)`, que dá **120 nos dois**. A diferença inteira mora
dentro do `mtvu`, que não é serializado.

Todos esses números viraram `static_assert`, então uma surpresa de ABI quebra o **build**, não o
carregamento.

## Escopo

**Entra:**

- `pcsx2/SaveStateLegacy.h` — `0x9A54` na lista; dois predicados de era (`IsAetherEra`,
  `Is9A54Era`) porque a escolha de leitor deixou de ser binária.
- `pcsx2/SaveStateLegacy.cpp` — `IsSupportedVersion` aceita `0x9A54`; `OldState` ganha
  `cpuRegisters_9A54` (alias do `_9A34`, mesmo layout), `psxRegisters_9A54` (o `_9A34` com
  `iopCycleEECarry` inserido, 812 bytes) e `CyclesBlock_9A54` (alias do `_9A34`), cada um com
  `static_assert` de tamanho; e `SaveStateBase::FreezeInternals9A54()`, o espelho do leitor moderno.
- `pcsx2/SaveState.h` — declaração de `FreezeInternals9A54`.
- `pcsx2/SaveState.cpp` — roteamento por era; `SupportsLegacy()`/`FreezeInLegacy()` passam a receber
  a versão, para que `PAD`, `USB`, `Achievements` e `GS` sigam o caminho **moderno** numa `0x9A54`
  (a `version1` grava os quatro com o mesmo código do fork) e o caminho legado só na era Aether.
- `pcsx2/SPU2/spu2.h` + `pcsx2/SPU2/spu2freeze-legacy.cpp` — `SPU2freeze9A54()`.
- `pcsx2/Counters.{h,cpp}` e `pcsx2/IopCounters.{h,cpp}` — os dois blocos `DELETEME` de reparo de
  estado envenenado saem de dentro de `rcntFreeze`/`psxRcntFreeze` e viram
  `rcntRepairPoisonedCounters()` / `psxRcntRepairPoisonedCounters()`, para o leitor `0x9A54` chamar
  os **mesmos** e não uma segunda cópia que apodrece. Também um `extern bool hBlanking, vBlanking;`
  em `IopCounters.h`: os dois já tinham ligação externa, só não eram declarados em cabeçalho nenhum.

  Isso não é enfeite. O `rcntSyncCounter` da 1.0.23 **não tem guarda de underflow** — `const u32
  change = (cpuRegs.cycle - counters[i].startCycle) / rate` — então uma baseline transitoriamente à
  frente do relógio virava `count += 0xFFFFFFFF` e contador morto por ~15 s, e o estado envenenado
  entrava em todo savestate tirado depois. **Todo arquivo `0x9A54` é da população que esse reparo
  existe para curar.**
- `tests/ctest/core/savestate_legacy_tests.cpp` — casos para `0x9A54` e para os predicados de era
  particionarem o conjunto aceito.

**NÃO entra:**

- **Gravar no formato `0x9A54`.** A compatibilidade é só de leitura; o fork continua gravando
  `0x9A59`. Um savestate carregado e regravado sobe de formato, e isso é o desejado.
- **As eras Aether/Nether.** `FreezeInternalsLegacy` fica como está.
- **Aviso na UI de que o savestate foi migrado.** É o passo recomendado pelo
  [documento de análise](../savestates-preservar-no-transplante.md) §8, mas é trabalho de Compose e
  vale uma task própria.
- **Migração de memory cards.** Formato de `.ps2` não mudou; nada a fazer.

## Como validar

**Primeiro, o que a implementação valida sozinha:**

1. O leitor termina com o mesmo teste de resíduo que o leitor Aether usa —
   `m_idx != m_memory.size()` é erro duro. Se qualquer campo do mapa estiver errado, o blob
   desalinha e o carregamento **falha em vez de corromper**. É a rede de segurança que torna
   seguro publicar antes de ter um arquivo real.
2. `SPU2freeze9A54` confere `size == sizeof(DataBlock9A54)` e a dupla `id`/`self-version` antes de
   tocar em qualquer estado. Tamanho errado → recusa limpa do bloco de áudio.

**Depois, o teste que fecha a lacuna, e sem ele não se publica:**

3. Instalar a 1.0.23, rodar um jogo real (*Shadow of the Colossus* ou *DBZ Budokai Tenkaichi 3*),
   salvar estado, guardar o `.p2s`.
4. Atualizar para o fork (⚠️ exige **desinstalar**: `versionCode` 38 não desce para 37, e isso apaga
   os dados do aparelho — copiar o `.p2s` para fora antes).
5. Carregar o savestate: cena, pontuação, posição dos personagens e **áudio** restaurados, sem
   crash. Salvar por cima e recarregar, para provar que a regravação em `0x9A59` fecha o ciclo.

> **Não publicar a correção sem o passo 3–5.** Um leitor de savestate não verificado troca uma
> falha visível por uma silenciosa; os passos 1–2 reduzem esse risco, não o eliminam.

## O que esta task ainda NÃO verificou

- **Nenhum `.p2s` real foi carregado.** Toda a prova é de fonte e de tamanho de struct.
- **A suíte `tests/ctest` não foi executada aqui.** Os casos novos em
  `tests/ctest/core/savestate_legacy_tests.cpp` compilam (checado com `-fsyntax-only` contra o
  gtest vendorado), mas o alvo `core_test` só existe no build de desktop, que não está configurado
  nesta máquina.
- **O que foi construído:** os cinco arquivos tocados compilam limpos no build nativo arm64
  (`SaveStateLegacy.cpp`, `SaveState.cpp`, `Counters.cpp`, `IopCounters.cpp`,
  `SPU2/spu2freeze-legacy.cpp`), sem aviso novo. O APK completo não foi montado.
