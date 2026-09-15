# FEAT-0003: Colher o upstream de setembro de 2026

- **Status:** em andamento
- **Criada em:** 2026-09-08
- **Concluída em:** —

## Objetivo

Trazer, em blocos serializados e testados um a um, o que o `ARMSX2/ARMSX2` produziu entre
**31/08/2026** e **07/09/2026** e que vale para o nosso produto — correções de campo, precisão de
emulação, entradas de GameDB e trabalho de controle — **sem** abrir uma frente de merge gigante.

## De onde vem o levantamento

Medido em 2026-09-08, com `upstream` já buscado:

| Grandeza | Valor |
|---|---|
| Merge-base nosso com `upstream/master` | `ce96af5046` — 31/08/2026, *"Touch controls: don't auto-hide while the pad is being used"* |
| `upstream/master` na medição | `fd8a8403bc` — 07/09/2026 |
| Commits que não temos | **339** (324 sem merge) |
| Destes, vindos de **um só PR** (#660 `gs-classic-tiler`, merge 06/09) | **279** |
| Commits até a tag `2.6.8` (`aa2a43fffc`, 03/09) | **14** |
| Arquivos tocados pelos dois lados (superfície de conflito) | **25** |

**O número 339 engana.** Não são 339 coisas para escolher: são ~45 itens escolhíveis mais um PR
gigante. É essa constatação que permite dividir o trabalho em cinco blocos entregáveis em vez de
uma frente única.

Comandos que reproduzem a medição:

```bash
git fetch upstream --prune
git merge-base HEAD upstream/master                      # ce96af5046
git rev-list --count upstream/master ^HEAD               # 339
git rev-list --count --no-merges a100539924^2 ^a100539924^1   # 279 (o PR #660)
git rev-list --count --no-merges aa2a43fffc ^HEAD        # 14  (até a 2.6.8)
```

## Por que em blocos serializados

Duas razões, e as duas foram medidas nesta árvore:

1. **A superfície de conflito é concentrada nos nossos arquivos mais editados.** `native-lib.cpp`,
   `NativeApp.java`, `MainActivityRuntime.kt`, `I18n.kt`, `build.gradle.kts` e
   `GSGPUDriverProfile.cpp` estão nos dois lados. Um merge único empilha todos os conflitos de uma
   vez, e nenhum deles fica testável isoladamente.
2. **Cada bloco tem um critério de validação diferente** — um precisa de um pad físico, outro de um
   jogo específico salvando, outro só de um build que linka. Misturar apaga a capacidade de dizer
   *o que* funcionou.

Por isso cada bloco é **uma task, entregue por uma sessão nova, com contexto limpo**, e a seguinte
só começa quando a anterior estiver commitada e validada no aparelho.

## Tasks

| Task | Status | Descrição |
|---|---|---|
| [TASK-0090](../task/TASK-0090-duas-correcoes-de-campo-do-upstream.md) | concluída | **Bloco 1** — afinidade não anula mais o Sustained Performance (validado no aparelho); a correção de page cache da extração entrou, mas está **dormente**: o nosso app não tem entrada para o Quick Loading |
| [TASK-0091](../task/TASK-0091-adotar-remocao-da-captura-de-video.md) | concluída | **Bloco 2** — remoção de captura de vídeo/ffmpeg adotada (cherry-pick sem conflito); os 7 critérios validados no aparelho. O delta contra `upstream/master` caiu de 773 para 606 arquivos (−40.118 linhas). O contorno do Android era **do upstream** (`f06e144f57`), não nosso |
| [TASK-0092](../task/TASK-0092-gamedb-e-precisao-ee-vu.md) | aberta | **Bloco 3** — entradas de GameDB (Jak X, Shaolin Monks, GoW II) e a precisão de EE FPU / divisão da VU |
| [TASK-0093](../task/TASK-0093-trabalho-de-controle-do-armsx3.md) | em andamento | **Bloco 4** — o trabalho de controle vindo do ARMSX3 e o fallback de rumble para pads sem motor |
| [TASK-0094](../task/TASK-0094-avaliar-e-adotar-o-pr-660-gs.md) | em andamento | **Bloco 5** — decidir e executar a adoção do PR #660 (`gs-classic-tiler`, 279 commits de GS) |

### Ordem, e por que ela é esta

1. **TASK-0090** primeiro: duas correções de ~20 linhas cada, conflito nenhum, e as duas resolvem
   sintoma que o usuário sente hoje (aparelho quente; app morto depois de extrair).
2. **TASK-0091** em seguida: é a única que **reduz** o nosso delta em vez de aumentá-lo, e mexe em
   build — melhor fazer isso com a árvore ainda pouco alterada.
3. **TASK-0092**: poucas linhas, ganho de compatibilidade, e valida-se com jogos concretos.
4. **TASK-0093**: primeira que traz volume (+1068 linhas) e primeira que precisa de hardware
   externo (pad).
5. **TASK-0094** por último: é a que pode não acontecer. Depende de as quatro anteriores terem
   deixado a árvore num estado em que o merge do #660 seja legível.

## O que esta feature deliberadamente NÃO cobre

Registrado para não parecer esquecimento. Cada item vira task própria **depois** do Bloco 5, e o
motivo de ficar fora é sempre o mesmo: não cabe numa sessão junto com o resto.

| Fora de escopo | Commit(s) | Por que fica fora |
|---|---|---|
| Texture packs `tar+zstd` | `23cc699bbe` | +1983 linhas, 17 arquivos, decoder zstd novo na árvore. Task própria. |
| KTX1 com mip-chain para ASTC | `610e11b082` | +868 linhas. Temos `GSTextureASTC.h`, falta `GSTextureKTX.h`. Depende de decidir se queremos o pipeline de texture pack do upstream. |
| Recursos (GS/GameDB/Patch) no binário | `4b0c679919` | Muda de onde o app lê recurso — precisa de par com a nossa cópia de assets. |
| `pcsx2-gsrunner` como executável NDK | `6532d77309` | Ferramenta de medição, não de produto. Útil, não urgente. |
| Correções de build libretro | #655, #657, #659, #663 | Tocam os nossos `CMakeLists.txt`, mas servem ao core libretro, que não construímos. |
| Branch `cache-dxstg-followups` | `5f50eab28e` | **Contém um bug real de EE D-cache que nunca chegou ao master.** Merece task própria e uma conversa com o upstream. |
| Branch `dynamic-ee-cyclerate` (39 commits) | — | Não mergeada lá. Esperar. |
| Branch `gs-tile-renderer` (443 commits) | — | Renderizador experimental. Não considerar. |

## Restrições que valem para todas as tasks desta feature

1. **Correção de motor nasce como contribuição ao upstream, não como edição local** (CLAUDE.md).
   Nestas tasks estamos do lado bom dessa regra: estamos *consumindo* o upstream. Se durante a
   implementação aparecer a vontade de "ajustar um detalhe" no código deles, isso é sinal de que
   falta um patch lá, não aqui.
2. **`git cherry-pick` do commit deles, não reescrita à mão.** Preserva autoria e faz o próximo
   `git merge upstream/master` reconhecer o que já veio.
3. **Nada é commitado sem a task no assunto** — `TASK-00NN: <resumo no imperativo>`, seguido de
   `python scripts/check_traceability.py`.
4. **Teste no aparelho é obrigatório e é ponta a ponta.** "Compila" não é validação. Cada task
   abaixo diz qual é o critério e como medi-lo.

### O que o Bloco 1 revelou, e vale para os blocos seguintes

A TASK-0090 descobriu que o **Quick Loading não tem entrada no nosso app**: o merge da TASK-0067
manteve o nosso `HomeScreen.kt` inteiro e, com ele, descartou o único chamador de `QuickLoadSetup`.
Por decisão do usuário (2026-09-11), a TASK-0090 fechou com o Defeito 1 validado e o Defeito 2
registrado como código dormente; devolver a entrada e medir o page cache ficou com a
[TASK-0096](../task/TASK-0096-devolver-a-entrada-do-quick-loading.md), **fora** da sequência
serializada — ela não pertence à colheita de setembro, e sim a uma perda de 01/09.

**Para cada bloco daqui em diante:** antes de dar um commit do upstream por "entregue", achar **o
chamador dele na nossa árvore**. Um recurso cuja entrada mora num arquivo que mantivemos inteiro no
merge (o `HomeScreen.kt` é o caso conhecido) compila, entra no binário e não existe para o usuário.

## Critério de conclusão da feature

As cinco tasks concluídas, **ou** a TASK-0094 explicitamente abandonada com o motivo registrado —
que é um desfecho legítimo, dado o tamanho do #660.
