# Bug: em Mali no Vulkan o piso de Z de 32 bits do PS2 é desligado, e o opt-out documentado não existe

- **Detectado em:** 2026-08-31 16:30 (leitura de código, disparada pelo relato de linhas verticais
  no 007: Everything or Nothing — Galaxy A12 `SM-A127M`, Mali-G52, driver r38)
- **Origem:** análise da cadeia de três defeitos no mesmo aparelho (lento → tela preta → linhas)
- **Errors (serviço):** nenhum — não é crash, não gera telemetria
- **Classe:** fail
- **Reincidência:** não registrado antes
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0064](../../task/TASK-0064-devolver-o-controle-do-piso-de-z.md)

## Sintoma

O mesmo aparelho, o mesmo jogo, a mesma configuração, e duas imagens diferentes conforme o
renderizador:

| renderizador | resultado |
|---|---|
| OpenGL (forçado à mão) | imagem correta — mas o jogo cai na tela preta do outro defeito |
| Vulkan (o que o `auto` escolhe hoje) | imagem com linhas, verificado com upscale em **1x nativo** |

O A/B foi executado no aparelho em 2026-08-31: forçando OpenGL nas Configurações **as linhas somem
e a tela preta volta**. Os dois defeitos são o par GL/Vulkan do mesmo aparelho, não dois problemas
independentes.

## O defeito

Os dois backends discordam sobre uma decisão de **emulação**, não de backend, e a discordância é só
para Mali:

[`GSDeviceOGL.cpp:1046`](../../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1046)

```cpp
m_features.no_ps2_z_quantization = GSConfig.DisablePS2DepthQuantization || vendor_id_apple;
```

[`GSDeviceVK.cpp:3877`](../../../pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L3877)

```cpp
m_features.no_ps2_z_quantization =
    GSConfig.DisablePS2DepthQuantization || IsDeviceMali() || IsDeviceAppleGPU();
```

Ou seja: **Mali no GL mantém o piso de Z de 32 bits do PS2; Mali no Vulkan o perde.** O comentário
do lado GL admite a divergência em voz alta:

> Mali is deliberately not included: the Vulkan path opts it out for early-ZS, but that has not been
> tested on a Mali GL driver.

O que se perde está escrito em
[`GSRendererHW.cpp:5790-5795`](../../../pcsx2/GS/Renderers/HW/GSRendererHW.cpp#L5790-L5795):

> Even when Z is read-only, Z floor must be enabled with ZTST_GREATER since otherwise there can be
> **false passing** if the incoming Z is not floored when the buffer value is floored.

### E não há como voltar atrás

O comentário do lado Vulkan promete uma saída que não existe:

> Default-on for Mali; **opt-out via INI** for Z-precision-sensitive titles.

A expressão é um `||`. `DisablePS2DepthQuantization` só consegue empurrar `no_ps2_z_quantization`
para `true` — nunca de volta para `false`. Em Mali o resultado é `true` para qualquer valor da
chave. Somando: o app Android **não expõe** `DisablePS2DepthQuantization` em lugar nenhum
(`grep` em `platforms/android/app/src/main/{java,cpp}` não encontra a chave), então nem a metade
que funciona está ao alcance do usuário.

O mesmo vale para Apple GPU, pelo mesmo motivo, no Metal
([`GSDeviceMTL.mm:1358`](../../../pcsx2/GS/Renderers/Metal/GSDeviceMTL.mm#L1358)) e no GL.

## Por que isto importa além deste aparelho

A troca de renderizador é feita hoje por uma regra da tabela de drivers
(`gl-arm-g52-r38-auto-vulkan`, [`GSGPUDriverProfile.cpp:358`](../../../pcsx2/GS/Renderers/Common/GSGPUDriverProfile.cpp#L358)),
que casa com **todo Mali-G52 em driver r38.x**. Quem escreveu a regra estava escolhendo um backend
para resolver uma tela preta; ninguém decidiu mudar como a profundidade é emulada nesses aparelhos.
A mudança veio de carona, é global, e é invisível — não há log nem tela que diga que o piso de Z
saiu.

Essa é a razão de o conserto ser o controle, e não um valor novo: enquanto a decisão for tomada por
identidade de GPU dentro do device, cada troca de renderizador continua trocando semântica de
emulação sem que ninguém peça.

## O que este bug NÃO afirma

**Não afirma que o piso de Z ausente é a causa das linhas verticais.** A ligação entre os dois é
temporal e circunstancial: as linhas aparecem exatamente no backend que descarta o piso, e somem no
que o mantém. Isso é consistente com `false passing` no teste de profundidade, e é consistente com
outras diferenças GL↔Vulkan que a mesma troca provoca — `dual_source_blend`, o caminho de
`framebuffer_fetch`, `test_and_sample_depth`, `stencil_buffer`.

O conserto desta task torna a pergunta **respondível pelo usuário em uma rodada**, o que hoje é
impossível. Se com o piso forçado de volta as linhas sumirem no Vulkan, a causa está nomeada. Se
não sumirem, uma hipótese cara foi eliminada por um toggle em vez de por um APK.

O registro do backlog do A55 é explícito de que **duas hipóteses de escritório sobre este mesmo A12
foram ao aparelho e voltaram erradas**. Esta não vai ser a terceira: ela vai como instrumento.

## Reprodução

1. Galaxy A12 (`SM-A127M`), Mali-G52, driver ARM r38.
2. Renderizador em `auto` — a regra `gl-arm-g52-r38-auto-vulkan` resolve para Vulkan.
3. Abrir 007: Everything or Nothing, upscale 1x. As linhas aparecem.
4. Configurações → Renderer → OpenGL. As linhas somem, a tela preta aparece.

## Onde olhar

| arquivo | linha | o quê |
|---|---|---|
| `pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp` | 1046 | piso ligado em Mali |
| `pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp` | 3877 | piso desligado em Mali, sem volta |
| `pcsx2/GS/Renderers/Metal/GSDeviceMTL.mm` | 1358 | mesmo formato, Apple GPU |
| `pcsx2/GS/Renderers/HW/GSRendererHW.cpp` | 5790 | o que o piso protege (`false passing`) |
| `pcsx2/Config.h` | 908 | `DisablePS2DepthQuantization` |

## Reteste depois do merge com o upstream (2026-09-01)

Retestado no mesmo A12 `SM-A127M`, com a árvore já no upstream de 31/08
([TASK-0067](../../task/TASK-0067-merge-com-o-upstream.md), 72 commits) e APK `githubDebug` novo.
**As linhas continuam.** `renderer=14` confirmado no log, device Vulkan inicializado, upscale em
1x nativo e `forcePs2DepthQuantization = false` — as mesmas condições do A/B de 31/08.

Onde elas aparecem, para quem for reproduzir: na sequência do cano do revólver o círculo branco sai
**estriado** em vez de sólido, e a cena 3D do briefing fica listrada de ponta a ponta. Não é
transitório: reproduziu em duas capturas separadas por 35 s.

Os dois fixes de GS do upstream já estavam na árvore antes do merge, e os 72 commits novos não
trouxeram outro. A hipótese "está consertado lá e a gente não puxou" está eliminada; a
[TASK-0064](../../task/TASK-0064-devolver-o-controle-do-piso-de-z.md) segue sendo o caminho.

## Alcance reduzido (2026-09-02)

A [TASK-0072](../../task/TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md) retirou a
regra `gl-arm-g52-r38-auto-vulkan`, que era o que empurrava esses aparelhos para o Vulkan. Com ela
fora, o `auto` volta a resolver OpenGL no Mali-G52 r38 — e **no OpenGL o piso de Z é mantido**.

Ou seja: a exposição involuntária descrita acima (todo Mali-G52 r38 emulando profundidade de forma
diferente sem ninguém decidir) **acabou**. O defeito de fundo **não**: quem escolher Vulkan à mão,
nesses ou em outros aparelhos Mali, continua sem o piso — e a chave `ForcePS2DepthQuantization` da
TASK-0064 continua sendo a forma de trazê-lo de volta, ainda **não testada em campo**.
