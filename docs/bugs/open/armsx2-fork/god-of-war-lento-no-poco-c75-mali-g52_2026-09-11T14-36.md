# Bug: God of War extremamente lento no POCO C75 (Helio G81 Ultra / Mali-G52 MC2)

- **Detectado em:** 2026-09-11 14:36 (relato de usuário, repassado pelo dono do produto)
- **Origem:** **herdado do upstream** — fixes de GameDB calibrados para PC — sobre hardware no piso
  do que o PS2 exige. Não é delta do fork.
- **Errors (serviço):** nenhum — não é crash, não gera telemetria
- **Classe:** fail (performance)
- **Reincidência:** específico de título; o padrão vale para qualquer jogo com `autoFlush` no GameDB
  em GPU tiler fraca
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0095](../../../task/TASK-0095-autoflush-do-god-of-war-em-gpu-fraca.md)
  — mede a hipótese do `autoFlush` e, se a troca valer, a leva ao upstream. **Não** cobre o item 4
  abaixo (`DeviceTier`), que fica sem task.
- **Relacionado:**
  [auditoria de 2026-08-10 do GoW II](../../done/gamedb-gow2-autoflush-mvuflag-custo-proibitivo-mobile_2026-08-10T16-02.md)
  — mesma hipótese, que ficou sem medição;
  [ajuste por jogo igual ao global](ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md)
  — é o defeito que faz o contorno óbvio **falhar** (item 3)

> ⚠️ **Tudo abaixo é derivado do código, não medido.** Nenhum número do aparelho do usuário foi
> colhido. A causa provável está bem amarrada ao código e a um precedente do upstream, mas a
> hipótese concorrente — gargalo na EE, não no GS — não foi descartada, e só um `PerfLog` descarta.

## O que o relato diz, e o que ele não diz

Diz: *God of War está extremamente lento*, num POCO C75.

Não diz: **qual** God of War (I ou II — o item 1 mostra que isso muda o que se aplica), o serial,
o renderizador, o upscale, os fps, nem a versão do app.

## O aparelho

| | |
|---|---|
| SoC | MediaTek Helio G81 Ultra, 12 nm |
| CPU | **2×** Cortex-A75 @ 2,0 GHz + 6× Cortex-A55 @ 1,8 GHz |
| GPU | **Mali-G52 MC2** — tiler, dois núcleos de shader |
| RAM | até 8 GB LPDDR4X |

Fonte: [GSMArena](https://m.gsmarena.com/xiaomi_poco_c75-13435.php) e a
[FAQ da Xiaomi](https://www.mi.com/global/support/faq/details/KA-517392/). EE, GS e VU1 querem
núcleo grande, e há dois, de projeto de 2017.

A GPU é da mesma família dos dois bugs abertos de Mali-G52
([tela preta no GL](gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md),
[device lost no Vulkan com upscale](mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale_2026-09-02T11-33.md)),
mas aqueles foram medidos num Exynos 850 com driver **r38p1**. A revisão do driver do C75 é
desconhecida — **não** transferir aquelas conclusões para cá sem ler a string do driver.

## O que o código diz — quatro elos, cada um aberto

### 1. Os 14 seriais de God of War carregam `autoFlush: 1`

Conferido no `bin/resources/GameIndex.yaml` do `upstream/master` em `9027cd3acf` (2026-09-10):

| jogo | seriais | `gsHWFixes` |
|---|---|---|
| **God of War** (6) | SCAJ-30010, SCED-53431, SCES-51533, SCES-53133, SCUS-97399, SCUS-97467 | `halfPixelOffset: 5`, **`autoFlush: 1`** *(Fixes sun going through walls)* |
| **God of War II** (8) | SCAJ-20190, SCAJ-30011, SCED-54680, SCES-54206, SCKA-30006, SCKA-30007, SCUS-97481, SCUS-97482 | `halfPixelOffset: 5`, `alignSprite: 1`, **`autoFlush: 1`** *(Fixes sun occlusion)*, `nativeScaling: 1`, `recommendedBlendingLevel: 3`, `recommendedHWAA1: 1` — mais `speedHacks: mvuFlag: 0` |

O custo do `autoFlush` em GPU tiler está explicado na auditoria de 2026-08-10: quebra o lote a cada
sprite auto-referente e exige barreira entre as draws, e num tiler barreira dentro de render pass
pode forçar resolve e reload de tile. **Mas `1` é `SpritesOnly`, o nível mais leve dos ativos** — o
ganho de desligá-lo pode ser menor do que o do precedente do upstream, que partia de `2`.

### 2. O overlay mobile do upstream não relaxa nenhum deles

`bin/resources-overlay/armsx2_overrides.yaml` é **do upstream** — idêntico ao `upstream/master`,
mantido por Brian Degenhardt — e é onde eles relaxam fixes caros em tiler. Nenhum dos 14 seriais
está lá. O precedente existe: `bf65e8604b`, *"drop autoFlush on Rogue Galaxy — a deliberate
speed/accuracy trade"*.

### 3. O contorno óbvio não funciona, por causa de outro bug aberto

Desligar **Auto Flush por jogo não tem efeito** para quem está com o global no padrão:

- `Settings.autoFlush` tem default `0`;
- `writeGameSettingsIni` grava no INI do jogo só as chaves que **diferem** do global;
- `0 == 0` → nada gravado → `ComputePerGameOverrides` não vê pin → o GameDB reaplica `1`.

É um caso concreto da metade ainda aberta de
[ajuste por jogo igual ao global](ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md).

**O contorno que funciona** é **Correções de Hardware Manuais → ligado, por jogo**. A chave
persistida é `EmuCore/GS/UserHacks = anyUserHackEnabled()`, que vira `true` com `manualUserHacks`;
`true ≠ false`, então é gravada; com `ManualUserHacks` o `applyGSHardwareFixes` pula todo fix para o
qual `isUserHackHWFix` devolve `true` — e `AutoFlush` cai no `default: return true`. O OSD avisa
*"Manual GS hardware renderer fixes are enabled, automatic fixes were not applied"*.

Efeito colateral: também deixam de ser aplicados `halfPixelOffset`, `alignSprite` e
`nativeScaling`. São fixes de upscale — `halfPixelOffset` está verificado como exclusivo de
`scale > 1.0f` no bug do device lost; para os outros dois, o nome e o comentário da própria
`Settings.kt` dizem o mesmo, **não verificado no core**. Em 1x, que é o que este aparelho deve usar,
não se espera diferença.

Por onde fazer: pelo **menu em jogo**, funciona em qualquer build publicado. Pela **biblioteca**,
só com a [TASK-0089](../../../task/TASK-0089-ajuste-por-jogo-na-biblioteca-cria-a-camada-de-jogo.md)
(`23498c4976`, ainda não publicada).

### 4. O `DeviceTier` não reconhece este aparelho como fraco

`DeviceTier.isLowEnd()` é o OR de `isLowRamDevice`, `coreCount() < 6` e RAM < 3,6 GB. O C75 tem
**8 núcleos** e 6–8 GB, então passa nas três e recebe `false`: nunca vê a recomendação do preset
Low-End. `mtvuDefault()` devolve `true` pelo mesmo motivo, num aparelho com dois núcleos grandes. A
heurística conta núcleos, não força de núcleo — seis dos oito são A55.

Derivado da leitura do código, não executado no aparelho. **Sem task.**

## O que dizer ao usuário hoje

1. Desempenho → perfil **Low-End**.
2. Resolução **1x nativa**, e não subir.
3. **Pelo menu em jogo**, com o God of War rodando: aba de Correções → *Correções de Hardware* →
   **Correções de Hardware Manuais: ligado**. Reiniciar o jogo. (Não adianta mexer só no *Auto
   Flush*, pelo item 3.)
4. Expectativa honesta: mesmo assim God of War num Helio G81 não chega a velocidade plena. O
   artefato que volta é o sol aparecendo através de parede.

## O que falta para fechar

- Do usuário: **qual** God of War (serial), o `emulog.txt` — a linha `PerfLog` diz se o gargalo é
  EE ou GS/GPU — e a string do driver Mali.
- A [TASK-0095](../../../task/TASK-0095-autoflush-do-god-of-war-em-gpu-fraca.md).
