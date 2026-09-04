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

## Fechamento — A/B medido no aparelho (2026-09-04)

Executado no `SM-A127M` do relato (Mali-G52, driver ARM r38, Android 13/SDK 33), APK
`githubDebug` construído da árvore em `ec6d44c05f`, 007: Everything or Nothing **`SLUS-20751`**
(a cópia deste aparelho é a NTSC-U, não a `SLES-52046` citada no registro do crash de upscale;
o GameDB dá as **mesmas** `gsHWFixes` para os dois seriais).

Os dois braços diferem **em um único bit**: `forcePs2DepthQuantization` no override por jogo.
Renderizador Vulkan à mão nos dois (o `auto` resolve OpenGL aqui desde a TASK-0072), upscale
forçado a **1x nativo** nos dois — 1,25x é o que estava salvo, e acima de 1x este aparelho
crasha por outro defeito.

### 1. A chave chega ao core — provado pelo log, antes de olhar a imagem

Mesma linha do `DevCon`, mesma sessão de boot, mesmo `renderer=14 ir=1.00`:

| braço | `Optional features:` |
|---|---|
| `forcePs2DepthQuantization=false` | `primitive_id texture_barrier framebuffer_fetch provoking_vertex_last vs_expand `**`no_ps2_z_quantization`** |
| `forcePs2DepthQuantization=true` | `primitive_id texture_barrier framebuffer_fetch provoking_vertex_last vs_expand` |

O token **some**. É esta a metade do defeito que era do nosso lado: o *"opt-out via INI"* que o
comentário do `GSDeviceVK` prometia e que não existia agora existe, está exposto na tela e foi
verificado em campo. Confirmado nas duas repetições de cada braço (4 boots).

### 2. O piso de Z **não** é a causa das linhas verticais

Comparados quadros da mesma cena nos dois braços. Métrica: energia de alternância entre colunas
vizinhas (`|2c_i − c_{i−1} − c_{i+1}|`, média) contra a mesma métrica entre linhas — listra
vertical desequilibra as duas, imagem correta não.

| quadro | colunas | linhas | razão |
|---|---|---|---|
| Vulkan, piso **desligado** — logo EA | 8,286 | 1,457 | **5,69** |
| Vulkan, piso **forçado** — logo EA | 8,270 | 1,410 | **5,87** |
| Vulkan, piso **desligado** — cena 3D "PAMIR MOUNT" | 6,814 | 1,464 | **4,65** |
| Vulkan, piso **forçado** — cena 3D "PAMIR MOUNT" | 6,399 | 1,370 | **4,67** |
| **Software (controle)** — logo MGM | 3,097 | 3,736 | **0,83** |
| **Software (controle)** — cena 3D do jato | 1,215 | 1,750 | **0,69** |

Os dois quadros do logo EA são o par mais bem casado (mesmo instante da animação): diferença de
**0,20 %** na métrica de colunas e **1,55** níveis de cinza de diferença média pixel a pixel. A
imagem é a mesma. A olho, também: as listras estão lá nos dois braços, no logo THX, no logo EA e
na cena 3D do briefing — a mesma cena que o reteste de 01/09 descreveu como *"listrada de ponta
a ponta"*.

O controle em **software** existe para calibrar a métrica e não é decoração: nele a razão cai
para **0,7–1,1** (isotrópica) e a imagem sai limpa. Ou seja, a métrica detecta a listra quando
ela some — e ela não some com o piso de Z de volta.

**Conclusão: a hipótese caiu.** A ausência do piso de Z de 32 bits **não** é a causa das linhas
verticais neste aparelho neste título. O próprio registro já dizia que não afirmava isso; agora
está medido, e custou um toggle em vez de um ciclo de APK.

A suspeita seguinte já tem nome e está fora deste registro: o GameDB pede
`halfPixelOffset: 2 # Fixes lines in cutscenes.` para `SLUS-20751` e `SLES-52046`, e esse ajuste
**só age acima de 1x** — o log dos dois braços mostra `hpo=0`, coerente com isso. Acima de 1x
este aparelho perde o device e aborta. Esse teto está registrado em
[mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale](../open/armsx2-fork/mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale_2026-09-02T11-33.md).

### 3. O que este fechamento NÃO prova

- **Nada sobre Apple GPU.** Os caminhos Metal e OpenGL da mesma chave não têm hardware aqui para
  serem exercitados. Foram lidos, não medidos.
- **Nada sobre o custo de desempenho** de ligar o piso num tiler. Não foi medido: o A/B foi de
  imagem, e num A12 com o clock cortado pelo GOS um número de FPS não separaria as causas.
- **Nada sobre outros jogos.** Um título, um aparelho.
- **Não afirma que ligar o piso é inútil.** Afirma que ele não explica *estas* linhas. A chave
  continua sendo a única forma de responder a mesma pergunta no próximo título sem gastar um APK.

### Onde os números vieram

As citações de linha da seção "Onde olhar" acima são **anteriores** à correção. Depois da
TASK-0064 a decisão está em `GSDeviceOGL.cpp:1052`, `GSDeviceVK.cpp:3887` e
`GSDeviceMTL.mm:1376`, e a chave em `Config.h:916` / `Pcsx2Config.cpp:761`.

As preferências do aparelho foram alteradas só para medir (renderer/upscale/toggle no override
por jogo de `SLUS-20751`) e **devolvidas ao valor original** ao fim —
`{"renderer":"vulkan","upscaleFloat":1.25}`, arquivo conferido byte a byte contra a cópia tirada
antes do teste. Os memory cards foram copiados antes e não foram tocados.
