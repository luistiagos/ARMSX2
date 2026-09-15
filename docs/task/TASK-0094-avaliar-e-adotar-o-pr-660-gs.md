# TASK-0094: decidir e executar a adoção do PR #660 (`gs-classic-tiler`)

- **Status:** em andamento
- **Criada em:** 2026-09-08
- **Concluída em:** —
- **Feature:** [FEAT-0003](../features/FEAT-0003-colheita-upstream-setembro-2026.md)
- **Bugs que resolve:** —
- **Commit:** — (o vínculo é o prefixo `TASK-0094:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

> **Bloco 5 de 5 da FEAT-0003, e o único que pode legitimamente terminar em "não".**
> Só começar depois que a [TASK-0093](TASK-0093-trabalho-de-controle-do-armsx3.md) estiver
> commitada e validada.

## Resumo em uma linha

O PR #660 do upstream é **279 commits e 149 arquivos** de trabalho de GS; esta task tem **duas
fases** — primeiro decidir se adotamos, com evidência, e só então executar.

## Por que esta task é diferente das quatro anteriores

As outras quatro trazem patches pequenos e independentes. **Esta traz um bloco que não se
decompõe.** Os 279 commits foram desenvolvidos num branch e mergeados de uma vez em 06/09
(`a100539924`); ~33 deles **adicionam e removem instrumentação dentro do próprio branch** (ledger,
census, chaves A/B), ou seja, cherry-pick individual produz uma árvore que o upstream nunca testou.

**A escolha real é: merge inteiro, ou nada.**

### O tamanho verdadeiro

| Grandeza | Valor |
|---|---|
| Commits no PR (sem merge) | 279 |
| **Mudança líquida agregada** | **149 arquivos, +21.142 / −1.147** |
| Arquivos **novos** | ~30 cabeçalhos de política e kernel (`GSVertexKickKernel.h`, `GSAlphaKnownBits.h`, `GSBlockWalk.h`, `GSStreamRingMemoryPolicy.h`, …) |
| Arquivos de teste novos/alterados | 31 em `tests/ctest` |
| **Arquivos que colidem com o nosso delta** | **15** |

O número que importa é o líquido: **149 arquivos**, não 279 patches.

## O que o PR contém, por família

Lido pelos assuntos e diffstats, não pelos 279 diffs — **quem implementar deve aprofundar na fase 1**.

### a) Rasterizador software (~40 commits, `pcsx2/GS/Renderers/SW/`, 17 arquivos)

Conformidade com o hardware real, medida no console: dither em **todos** os destinos de cor e não só
nos de 16 bits; escolha entre MMAG e MMIN **por pixel** e não por primitiva; multiplicação pelo
**recíproco truncado** do console em vez de divisão por Q; blend de mip no peso de **quatro bits**
do console; passo de profundidade na grade truncante de **2^-10**; DATE de 16 bits com DATM=1, que
**nunca passava** no scanline ARM64.

### b) Vulkan stream rings (~25 commits, `GSDeviceVK.cpp` +566/−106)

Memória cacheada onde o dispositivo não tem coerente; limpeza de ring não-coerente **uma vez por
submit** em vez de por commit; pool de descritores de quadro que **cresce** em vez de descartar os
draws que não couberam; um range de stream fica livre quando o **último** leitor se aposenta, não o
primeiro.

### c) Banco de dados de driver (~6 commits) — **o mais relevante para Android**

- `3facc8904e` — **Auto passa a resolver para Vulkan** na peça Mali onde o Vulkan já é o caminho mais
  rápido
- `662576cfa6` — o *stencil kill* do Adreno vira **regra "Turnip < 26.2"** em vez de regra por GPU
- `cf0f5e9c02` — a **deny list de fbfetch da MediaTek** entra no banco, com o SoC medido **isento**
- `c12083a8ef` + `921069d2c7` — **Turnip ignora o blend constant**, em todo Adreno e todo Mesa; a
  chave `ForceBrokenBlendConstant` vira um override de bug forçado

**Esta família é a continuação direta da [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)**
— é exatamente a infraestrutura que aquela feature existiu para adotar. É também onde mora o
conflito.

### d) HW / TC vindos do PCSX2 upstream

`77bec9b5a6` (reescrever vértices com ST grande), `32f16dfdf3` (mascaramento de canal ROV com
FBMask), `c0d7a8d46e`, `51e587b2e8` (shader de conversão de profundidade), `edb0970caa`.

### e) ~33 commits que só adicionam e removem instrumentação

Ruído dentro do branch. Não entregam nada e **explicam por que o cherry-pick individual é ruim**.

## Superfície de conflito — medida em 2026-09-08

| Arquivo | Nosso delta | Delta do #660 |
|---|---|---|
| `GS/Renderers/Vulkan/GSDeviceVK.cpp` | +8 −2 | **+566 −106** |
| `tests/ctest/core/gs/gs_gpu_driver_profile_tests.cpp` | +56 −0 | **+549 −2** |
| `GS/Renderers/Common/GSGPUDriverProfile.cpp` | +31 −0 | **+195 −7** |
| **`GS/GSUtil.cpp`** | **+173 −5** | +36 −9 |
| **`GS/Renderers/OpenGL/GSDeviceOGL.cpp`** | **+131 −25** | +18 −1 |
| `GS/Renderers/Common/GSGPUProfile.h` | +14 −1 | +58 −0 |
| `GameDatabase.cpp` | +19 −58 | +10 −0 |
| `GS/GS.cpp` | +15 −2 | +20 −0 |
| `GS/GSUtil.h` | +16 −0 | +13 −4 |
| `VMManager.cpp` | +15 −0 | +5 −0 |
| `platforms/android/.../cpp/CMakeLists.txt` | +12 −1 | +5 −0 |
| `Config.h` | +8 −0 | +2 −0 |
| `Pcsx2Config.cpp` | +4 −0 | +3 −0 |
| `GS/Renderers/Metal/GSDeviceMTL.mm` | +4 −1 | +11 −0 |
| `pcsx2/CMakeLists.txt` | +2 −0 | +17 −0 |

**Dois padrões diferentes de conflito, e eles exigem estratégias diferentes:**

1. **Eles mudaram muito, nós pouco** (`GSDeviceVK.cpp`, `GSGPUDriverProfile.cpp`, os testes de
   perfil). Aqui o certo é **tomar a versão deles** e re-aplicar as nossas poucas linhas por cima —
   ou descobrir que elas ficaram obsoletas, que é o desfecho bom.
2. **Nós mudamos muito, eles pouco** (`GSUtil.cpp` +173, `GSDeviceOGL.cpp` +131). Aqui é o
   contrário: **manter o nosso** e enxertar o hunk deles. E é aqui que mora o risco de estragar algo
   que hoje funciona.

## Escopo

### Fase 1 — decidir (obrigatória, e pode terminar em "não")

**Entra:**

1. Levantar, com o `upstream/master` buscado, se o PR **regrediu alguma coisa** desde 06/09:
   commits de correção posteriores que o citem, issues abertas depois dele.
2. Ler de fato os ~6 commits da família (c), que são os que justificam a adoção para Android.
3. Estimar o esforço de conflito abrindo os hunks dos **cinco** arquivos da tabela acima com maior
   delta cruzado.
4. **Escrever a recomendação neste arquivo**, com evidência, e **levá-la ao usuário**. Não decidir
   sozinho: é a maior mudança de código da feature inteira.

**Desfechos legítimos da fase 1:**

- **Adotar inteiro** → seguir para a fase 2.
- **Adotar só a família (c)** — os ~6 commits de banco de driver, que tocam poucos arquivos e são o
  que a FEAT-0001 sempre quis. Neste caso a task **muda de escopo** e passa a ser um cherry-pick
  daqueles commits, com o resto registrado como não-feito.
- **Não adotar agora** → marcar a task como **abandonada**, com o motivo escrito. Isto é um
  resultado, não uma falha. A FEAT-0003 prevê explicitamente este desfecho.

### Fase 2 — executar (só se a fase 1 aprovar)

**Entra:**

1. `git merge upstream/master` (ou merge do ponto `a100539924`), resolvendo os 15 conflitos.
2. Build completo, testes, e a bateria de validação abaixo.

**NÃO entra, em nenhuma das fases:**

- **Cherry-pick individual dos 279 commits.** Ver "por que esta task é diferente".
- **O branch `gs-tile-renderer`** (443 commits, renderizador experimental). Fora da FEAT-0003.
- **"Melhorar" o código deles enquanto resolve conflito.** Se algo estiver errado no upstream, é
  contribuição lá — regra do `CLAUDE.md`.
- Os itens que a FEAT-0003 já lista como fora de escopo (texture packs, KTX, recursos no binário,
  gsrunner NDK, correções de build libretro).

## Como implementar

### Fase 1

```bash
git fetch upstream --prune
# O que veio no PR, em agregado
git diff --stat a100539924^1 a100539924 | tail -3
# Correções posteriores que tocam o que ele criou
git log --oneline upstream/master ^a100539924 -- pcsx2/GS/
# Os seis commits que justificam a adoção
git show 3facc8904e 662576cfa6 cf0f5e9c02 c12083a8ef 921069d2c7
# Onde vai doer
MB=$(git merge-base HEAD upstream/master)
git diff $MB..HEAD -- pcsx2/GS/GSUtil.cpp pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp
git diff a100539924^1 a100539924 -- pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp
```

Verificar também as issues abertas do upstream que possam ser fallout: **#658** (texturas em Tales
of Destiny), **#666** (Manhunt fecha o app).

### Fase 2 — a ordem que reduz o risco

1. **Fazer o merge num branch descartável primeiro** (`merge/pr660-spike`), medir quantos hunks
   conflitam de verdade, e só então decidir se vai para o branch de trabalho.
2. Resolver na ordem: primeiro os arquivos onde **eles** mudaram muito (tomar a versão deles),
   depois onde **nós** mudamos muito (manter a nossa e enxertar).
3. **Build completo obrigatório** — configuração ~120 s, build ~825 s, `-j 4` (ver TASK-0091,
   seção 3, para a linha de `cmake` inteira e o aviso sobre as dependências do shaderc num worktree
   novo).

## Como validar

Esta é a task de maior superfície de regressão da feature. A validação é proporcional.

### Critério 1 — o build fecha e os testes de GS passam

`ninja -j 4 emucore_4k` com exit 0, e os 31 arquivos de `tests/ctest` que vieram junto. Vale aqui a
mesma ressalva da [TASK-0092](TASK-0092-gamedb-e-precisao-ee-vu.md), seção 4: **se os ctest não
puderem rodar nesta máquina, dizer isso**, não fingir.

### Critério 2 — o renderer escolhido não mudou sem querer

`3facc8904e` **muda o que "Auto" resolve** numa peça Mali. Isso é uma mudança de comportamento
observável.

1. Anotar, **antes** do merge, qual renderer o "Auto" escolhe no aparelho de referência
   (linha de boot do GS — a TASK-0004 pôs o perfil de driver ali).
2. Depois do merge, anotar de novo.
3. **Se mudou, medir fps antes e depois.** Uma mudança de renderer que piora é regressão, mesmo que
   o upstream a tenha medido como melhoria noutro aparelho.

> ⚠️ O aparelho de referência é um **SM-A127M (Mali-G52)**. A FEAT-0001 nasceu de quatro rodadas de
> correção gráfica que trocaram um sintoma por outro exatamente em Samsung com Mali. **Este é o
> critério mais importante da task.**

### Critério 3 — as regras de driver novas fazem o que dizem

- fbfetch: a deny list da MediaTek entrou no banco e o SoC medido é **isento**. Conferir que o nosso
  aparelho recebe a decisão certa (a decisão de fbfetch vem do banco desde a TASK-0005).
- stencil do Adreno: a regra virou "Turnip < 26.2". Se houver um Adreno à mão, conferir; se não
  houver, **dizer que não foi conferido**.

### Critério 4 — a bateria visual

Rodar **cinco jogos**, 5 minutos cada, e comparar screenshots da **mesma cena** antes e depois.
Escolher jogos que exercitem o que o PR mudou:

| Jogo | Por quê |
|---|---|
| God of War II | blending por alpha de destino, AA1, névoa de tela cheia |
| Shadow of the Colossus | rasterizador pesado, já é um caso conhecido nossa árvore |
| Sly 3 | VU e cor (é o critério da TASK-0092 — não pode ter regredido) |
| Um jogo 2D com texto | dither e destinos de 16 bits |
| Um jogo com HUD detalhado | AA1 e bordas meio-cobertas |

**Registrar os cinco pelo nome e serial.**

### Critério 5 — desempenho

fps médio nos mesmos cinco jogos, mesmo save state, mesma cena, antes e depois. O PR mexe em stream
rings de Vulkan e no kernel de vertex kick — **é esperado que mude**. O que não é aceitável é piorar
sem explicação.

### Critério 6 — não regrediu nada dos blocos 1 a 4

Repetir, em versão curta, os critérios das TASK-0090 a TASK-0093: a sobreposição de afinidade loga,
a extração não mata o app, Jak X salva, Sly 3 tem cor, o pad vibra.

## Antes de fechar

```bash
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

**Se a fase 1 terminar em "não adotar":** marcar `Status: abandonada`, escrever o motivo com a
evidência que levou a ele, e atualizar a tabela de tasks da
[FEAT-0003](../features/FEAT-0003-colheita-upstream-setembro-2026.md). Uma decisão registrada com
motivo vale mais que um merge feito às pressas.

## Resultado

### Fase 1 — evidência coletada em 2026-09-15

Pré-condição: a TASK-0093 está commitada (`b1c22e953f`, `60ea345692`, `83ca710013`, `b4bf1c4d73` — os hashes mudaram em 2026-09-15, quando os commits foram refeitos como `cherry-pick -x` para preservar a autoria do upstream), mas ainda não está validada fisicamente porque a sessão ficou sem joystick. Por isso esta sessão não executou merge nem validação de runtime; ficou só na decisão documentada.

Comandos e achados:

- `git fetch upstream --prune`: passou.
- PR #660 (`https://github.com/ARMSX2/ARMSX2/pull/660`): GitHub mostra merge em 2026-09-06, de `gs-classic-tiler` para `master`, com 283 commits no PR. O diff agregado medido em `a100539924^1..a100539924` segue sendo `149 files changed, 21142 insertions(+), 1147 deletions(-)`.
- Depois de `a100539924`, `git log upstream/master ^a100539924 -- pcsx2/GS/` lista 27 commits ainda tocando GS. Relevantes para risco: `843835aa71` (fast stencil shadow por device), `9cb5079857` (alpha stencil counter), vários ajustes `GS/SW`, `803e4fb19d` (remove hack MediaTek Mali Tekken 5) e commits de recursos/build que também tocam GS/libretro.
- Issues upstream abertas/atualizadas depois do merge incluem regressões gráficas/perf: #671 (NFS Underground vertical bands/flicker, Android 2.6.9), #672 (Splinter Cell Double Agent vertical lines regression), #675 (Urban Chaos speed drop), #678 (iOS SMT Nocturne graphical error), #680 (Adreno 650), #683/#684 (MGS3), #688 (Bleach performance), #697 (GT3 depth readbacks). Isso não prova causalidade do #660, mas mostra que o bloco ainda está recebendo fallout/triagem.
- Issues citadas pela task: #658 está aberta (`Textures aren't loaded in one area of Tales of Destiny director's cut`, Android 16, Exynos 2400/Xclipse 940, criada 2026-09-05 e atualizada 2026-09-08); #666 está aberta (`Manhunt (SLUS-20827) closes ARMSX2`, Snapdragon 7s Gen 4/Adreno 810, criada 2026-09-07).

Família (c), os commits Android-relevantes:

- `3facc8904e`: muda `Auto` para Vulkan no Mali medido; toca `GSUtil`, `GSGPUDriverProfile` e testes. Valor alto, risco alto no nosso aparelho de referência porque muda comportamento observável.
- `662576cfa6`: transforma o stencil kill do Adreno em regra de driver `Turnip < 26.2`; escopo bom e testável.
- `cf0f5e9c02`: move deny list de fbfetch MediaTek para o banco e isenta o SoC medido; valor direto para Android, mas depende de validação em Mali/MediaTek.
- `c12083a8ef` + `921069d2c7`: registram o bug de blend constant do Turnip e substituem `ForceBrokenBlendConstant` por override de bug forçado. Valor direto para Adreno/Turnip.

Superfície de conflito medida agora:

| Arquivo | Nosso delta desde merge-base | Delta do #660 | Observação |
|---|---:|---:|---|
| `pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp` | +8 / -2 | +566 / -106 | Grande no upstream, pequeno nosso; merge-tree não conflitou neste arquivo. |
| `tests/ctest/core/gs/gs_gpu_driver_profile_tests.cpp` | +56 / -0 | +549 / -2 | Conflita. Melhor tomar upstream e re-aplicar só o que ainda fizer sentido. |
| `pcsx2/GS/Renderers/Common/GSGPUDriverProfile.cpp` | +31 / -0 | +195 / -7 | Conflita; é o centro da família (c). |
| `pcsx2/GS/GSUtil.cpp` | +173 / -5 | +36 / -9 | Conflita; aqui mora risco alto porque nosso delta é maior. |
| `pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp` | +131 / -25 | +18 / -1 | Delta grande nosso; merge-tree não conflitou, mas precisa revisão manual porque o hunk é de renderer. |

Simulação de merge sem alterar worktree:

- `git merge-tree HEAD a100539924`: 6 conflitos reais: `pcsx2/GS/GSUtil.cpp`, `pcsx2/GS/GSUtil.h`, `pcsx2/GS/Renderers/Common/GSGPUDriverProfile.cpp`, `pcsx2/PerformanceMetrics.cpp`, `platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt`, `tests/ctest/core/gs/gs_gpu_driver_profile_tests.cpp`.
- `git merge-tree HEAD upstream/master`: 7 conflitos reais; os seis acima, mais `platforms/android/app/src/main/java/com/armsx2/input/ControllerMappings.kt` por causa do trabalho de controle da TASK-0093/upstream.

Recomendação para decisão do usuário:

**Não adotar o merge inteiro do #660 agora.** O bloco é tecnicamente valioso, mas o risco está acima do que dá para aceitar nesta sessão: há 27 commits de GS posteriores ao merge, várias issues gráficas/perf abertas desde 2026-09-06, conflito direto em `GSUtil.cpp`/`GSGPUDriverProfile.cpp`, e a validação exigida pela própria task depende de aparelho/jogos/cenas que não estão prontos agora. Além disso, a TASK-0093 ainda está sem validação física por falta de joystick, então o Bloco 5 não deveria virar execução.

Se o objetivo for capturar valor Android sem carregar todo o risco, minha recomendação secundária é **mudar o escopo para adotar só a família (c)** em uma task menor: banco de driver, regras Turnip/MediaTek/fbfetch e a mudança de Auto para Vulkan. Mesmo assim, isso precisa de validação no aparelho de referência ou, no mínimo, uma rodada explícita no Android disponível antes de concluir.

Decisão pendente do usuário: abandonar a adoção inteira agora, ou autorizar uma task/escopo menor para a família (c). Nenhum merge foi feito nesta sessão.
