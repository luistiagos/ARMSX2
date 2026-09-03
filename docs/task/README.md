# Tasks

Uma task é **uma unidade de trabalho que cabe em um commit**. Se não couber, provavelmente era
grande demais: quebre em duas. O limite é intencional — é o que faz `git revert <commit>` desfazer
exatamente uma decisão, sem arrastar outras junto.

É orientação, e não regra verificada: desde a
[TASK-0042](TASK-0042-remover-regra-um-commit-por-task.md) uma task pode ter mais de um commit, e o
índice registra todos. Voltar a uma task já commitada é melhor que emendar o commit que já existe.

Ver [`docs/README.md`](../README.md) para a regra de commit e o desenho geral da rastreabilidade.

## Convenção de nome

`TASK-NNNN-<slug-kebab-case>.md` — o número é sequencial dentro deste ramo e nunca reaproveitado
aqui. O slug pode ser reescrito; o número, não.

**Único dentro deste ramo, e só dentro dele — e isso agora é verificado.**
`feature/fork-upstream-android` e `feature/handoff-end-to-end` não têm história comum e numeraram
tasks em paralelo: **nove números colidem**, de TASK-0016 a TASK-0024, cada um com dois commits de
assuntos completamente diferentes. Um link `[TASK-NNNN]` só identifica alguma coisa quando se sabe
de que ramo se está falando.

Desde a [TASK-0078](TASK-0078-numero-de-task-identifica-neste-ramo.md), o validador não deixa mais
isso passar despercebido: **a partir da TASK-0016**, uma task `concluída` precisa de um commit
alcançável de `HEAD`. O commit homônimo do outro ramo não a satisfaz, e a mensagem de reprovação
diz exatamente isso. Abaixo da TASK-0016 a busca larga continua valendo, porque aquelas tasks foram
concluídas na linha anterior do produto, cujos commits o fork não alcança por construção.

O outro ramo não foi tocado: renumerar ou reservar faixa lá é decisão de lá.

## Status

| Status | Significa |
|---|---|
| `aberta` | Escrita, ainda não começada. |
| `em andamento` | Código sendo escrito. Ainda sem commit. |
| `concluída` | Commitada. O campo **Commit** tem um hash que existe no repositório. |
| `revertida` | O commit foi desfeito. O campo **Revertida por** aponta para o commit do revert. |

## Template

```markdown
# TASK-NNNN: <título no imperativo, o que a task faz>

- **Status:** aberta
- **Criada em:** YYYY-MM-DD
- **Concluída em:** —
- **Feature:** [FEAT-NNNN](../features/FEAT-NNNN-<slug>.md) — ou `nenhuma`
- **Bugs que resolve:** [<slug do bug>](../bugs/open/armsx2-fork/<arquivo>.md) — ou `nenhum`
- **Commit:** — (preencher via `--amend` antes do push)
- **Revertida por:** —
- **Publicado em:** — (versionName / versionCode em que chegou ao cliente)

## Objetivo

Uma ou duas frases: o que muda no produto ou no código depois desta task.

## Escopo

**Entra:**
- ...

**NÃO entra:**
- ... (o que ficou de fora de propósito, e para qual task foi empurrado)

## Como validar

Comandos, testes ou observação de campo que provam que a task funcionou. Se a validação depende de
dispositivo real, dizer qual e o que observar.

## Resultado

Preenchido ao concluir: o que de fato aconteceu, incluindo o que não saiu como planejado.
```

## Índice

Os hashes desta tabela são resolvidos do git pelo assunto do commit
(`git log --grep='^TASK-NNNN:'`), nunca escritos dentro do próprio commit da task.

| Task | Status | Feature | Resolve | Commit |
|---|---|---|---|---|
| [TASK-0001](TASK-0001-sistema-rastreabilidade.md) — sistema de rastreabilidade feature/task/bug | concluída | FEAT-0001 | — | `c572dc095b` |
| [TASK-0002](TASK-0002-bloco-a-arquivos-perfil-gpu.md) — bloco A: arquivos de perfil de GPU do upstream | concluída | FEAT-0001 | — | `335e4bc27a` |
| [TASK-0003](TASK-0003-bloco-b1-shader-cache-driver.md) — bloco B1: assinatura de driver no shader cache | concluída | FEAT-0001 | gs-tela-preta-silenciosa-sem-diagnostico-a07 | `6c3f292f35` |
| [TASK-0004](TASK-0004-bloco-b2-log-boot-gs.md) — bloco B2: perfil de driver no `GSDevice` e na linha `GSBoot` | concluída | FEAT-0001 | gs-tela-preta-silenciosa-sem-diagnostico-a07 | `e70e30b2e7` |
| [TASK-0005](TASK-0005-bloco-c-pontos-de-consumo.md) — bloco C: fbfetch decidido pelo banco de drivers | concluída | FEAT-0001 | gs-mali-tela-vermelha-e-page-fault-driver, graphicshealthmonitor-falso-positivo-cenas-escuras | `0983a42d49` |
| [TASK-0006](TASK-0006-diagnostico-boot-gs.md) — diagnóstico de boot do GS sem depender do log | concluída | FEAT-0001 | jni-bridge-nao-resolve-em-thread-nativa | `d8d08ee0e6` |
| [TASK-0007](TASK-0007-cas-precisao-gles.md) — precisão GLES no shader CAS | concluída | FEAT-0001 | cas-shader-gles-sem-precisao-mali | `7c3f5e775a` |
| [TASK-0008](TASK-0008-port-mfifo-spr-upstream.md) — port do MFIFO/SPR do upstream | concluída | FEAT-0001 | — (hipótese derrubada) | `cca3bf0c69` |
| [TASK-0009](TASK-0009-publicar-versao.md) — publicar a 1.0.23 | concluída | FEAT-0001 | — | `0bc7e826d0` |
| [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md) — corrigir o validador de rastreabilidade | concluída | FEAT-0002 | checktraceability-grep-casa-corpo-do-commit, checktraceability-fix-nao-insere-task-ausente-do-indice | `5c81aa34f1` `a9b6622acf` |
| [TASK-0011](TASK-0011-impor-regra-de-commit-mecanicamente.md) — impor a regra de commit mecanicamente | concluída | FEAT-0002 | rastreabilidade-sem-verificacao-de-git-para-task | `067af7f609` |
| [TASK-0012](TASK-0012-portao-de-boot-nao-perde-informacao.md) — portão de boot honra falha de init nativa e `onNewIntent` | concluída | — | app-falha-de-init-nativa-sem-consumidor, bootsplash-singletop-descarta-intent-novo | `3e25c6adb1` |
| [TASK-0013](TASK-0013-detector-valor-veneno-dma.md) — detector de valor-veneno no DMA (instrumenta o crash do SotC) | concluída | — | — (instrumenta, não corrige) | `a71a51e17d` |
| [TASK-0014](TASK-0014-comparador-superficie-jni.md) — comparador da superfície JNI contra o upstream | concluída | FEAT-0001 | — | `353dae44f1` |
| [TASK-0015](TASK-0015-manifesto-catalogo-curado.md) — manifesto de catálogo curado + `sort_manifest.py` que o preserva | concluída | — | — | `ee775b3015` |
| [TASK-0016](TASK-0016-base-do-fork.md) — base do fork sobre a árvore Android do upstream | concluída | FEAT-0001 | — | `9388c6a261` |
| [TASK-0017](TASK-0017-identidade-do-produto.md) — identidade do RetroSystem PS2 no fork | concluída | FEAT-0001 | — | `7c6215dff0` |
| [TASK-0018](TASK-0018-telemetria-no-fork.md) — trazer a telemetria de produção para o fork | concluída | FEAT-0001 | — | `e8db9aa4cb` |
| [TASK-0019](TASK-0019-mecanismo-de-atualizacao.md) — trazer o mecanismo de atualização pelo nosso canal para o fork | concluída | FEAT-0001 | — | `1794ab1864` |
| [TASK-0020](TASK-0020-seam-do-updater.md) — apontar o updater para o nosso canal e encerrar o conceito de nightly | concluída | FEAT-0001 | — | `3bc0d6cc47` |
| [TASK-0021](TASK-0021-assinatura-e-publicador.md) — assinar o fork com a chave de produção e reescrever o publicador com trava | concluída | FEAT-0001 | — | `9f85b85d8a` |
| [TASK-0022](TASK-0022-primeira-impressao-do-app.md) — ajustar a primeira impressão do app — marca, tela de entrada e fundo | concluída | FEAT-0001 | — | `74c70a0921` |
| [TASK-0023](TASK-0023-catalogo-de-roms.md) — trazer o catálogo de ROMs para o fork e fechar o ciclo baixar → jogar | concluída | FEAT-0001 | — | `1303705176` |
| [TASK-0024](TASK-0024-catalogo-como-tela-inicial.md) — abrir o app no catálogo e dar controle sobre o download em andamento | concluída | FEAT-0001 | — | `2220e13ec0` |
| [TASK-0025](TASK-0025-grade-unica-catalogo-na-biblioteca.md) — fundir o catálogo dentro da biblioteca — uma grade só | concluída | FEAT-0001 | — | `bb4dee7381` |
| [TASK-0026](TASK-0026-bios-embarcada.md) — embarcar a BIOS no APK, como na versão anterior | concluída | FEAT-0001 | — | `d07584ec35` |
| [TASK-0027](TASK-0027-pasta-no-gerenciador-de-arquivos.md) — expor a pasta de dados no gerenciador de arquivos do sistema | concluída | FEAT-0001 | — | `34c3a998f7` |
| [TASK-0028](TASK-0028-creditos-na-tela-sobre.md) — trazer os créditos da equipe para a tela Sobre | concluída | FEAT-0001 | — | `7b1437a0cb` |
| [TASK-0029](TASK-0029-filtro-visivel-na-barra.md) — fazer o filtro "Só os baixados" se anunciar na barra | concluída | FEAT-0001 | — | `5d3653df2a` |
| [TASK-0030](TASK-0030-adotar-pasta-de-dados-da-versao-anterior.md) — adotar a pasta de dados escolhida na versão anterior | concluída | FEAT-0001 | — | `6f45e34eb3` |
| [TASK-0031](TASK-0031-detector-de-veneno-no-fork.md) — trazer o detector de valor-veneno da DMA para o fork | concluída | FEAT-0001 | — | `45691d5ba9` |
| [TASK-0032](TASK-0032-rotulo-na-grade.md) — mostrar o título sob a capa, para as variantes regionais deixarem de parecer repetidas | concluída | FEAT-0001 | — | `73f464522f` |
| [TASK-0033](TASK-0033-enxugar-menu-lateral.md) — retirar do menu lateral as seis linhas que não são do produto | concluída | FEAT-0001 | — | `4565d0e451` |
| [TASK-0034](TASK-0034-campo-de-busca-no-topo.md) — campo de busca no topo da biblioteca | concluída | FEAT-0001 | — | `21cd767961` |
| [TASK-0035](TASK-0035-remover-cards-github-pcsx2-creditos.md) — retirar da tela Sobre os cards Repositório no GitHub, Projeto PCSX2 e Créditos | concluída | FEAT-0001 | — | `f6fc690700` |
| [TASK-0036](TASK-0036-musica-de-fundo-desligada-por-padrao.md) — música de fundo da biblioteca desligada por padrão | concluída | FEAT-0001 | — | `ed1a7a1cca` |
| [TASK-0037](TASK-0037-pastas-de-rom-como-tela.md) — gerir as pastas de ROM numa tela, não no assistente de primeira execução | concluída | FEAT-0001 | — | `d36766eceb` |
| [TASK-0038](TASK-0038-fila-de-download-visivel.md) — fila de download visível, e progresso que chega na tela | concluída | FEAT-0001 | fila-download-sem-tela-e-sem-progresso-ao-vivo | `7e4f9d41ce` |
| [TASK-0039](TASK-0039-credito-da-musica-so-quando-toca.md) — crédito da música da biblioteca só aparece quando ela toca | concluída | FEAT-0001 | — | `2189e8e742` |
| [TASK-0040](TASK-0040-fila-de-download-em-tela-propria.md) — fila de download em tela própria | concluída | FEAT-0001 | fila-download-congela-tela-ao-pausar | `a3c7cccf51` |
| [TASK-0041](TASK-0041-permissao-de-notificacao-do-download.md) — permissão de notificação do download | concluída | FEAT-0001 | notificacao-de-download-invisivel-sem-pedir-permissao | `f1d206eacf` |
| [TASK-0042](TASK-0042-remover-regra-um-commit-por-task.md) — sai a regra "uma task = um commit" | concluída | FEAT-0002 | — | `d569063f37` `8395d24ab6` |
| [TASK-0043](TASK-0043-aviso-anti-revenda-do-upstream.md) — boot sem faixas sobrepostas | concluída | FEAT-0001 | — | `0538bd2c94` |
| [TASK-0044](TASK-0044-telemetria-de-boot-e-de-assets.md) — telemetria de boot e de assets | concluída | FEAT-0001 | — | `ea3c0bddf8` |
| [TASK-0045](TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md) — baixar só formato que o emulador abre, e manter a capa | concluída | — | catalogo-download-entrega-formato-nao-bootavel, biblioteca-jogo-baixado-perde-a-capa | `186cdde1c6` |
| [TASK-0046](TASK-0046-encerrar-thread-mtvu-no-shutdown.md) — encerrar a thread da MTVU no shutdown da VM | concluída | — | mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm | `c333afa9f1` |
| [TASK-0047](TASK-0047-agrupar-versoes-do-mesmo-titulo.md) — agrupar versões do mesmo título e escolher a versão num painel | em andamento | — | biblioteca-mesmo-titulo-repetido-uma-vez-por-regiao | — |
| [TASK-0048](TASK-0048-descompactar-7z-e-zip-no-download.md) — descompactar `.7z` e `.zip` depois do download | em andamento | — | — | — |
| [TASK-0049](TASK-0049-carregar-savestates-0x9A54.md) — carregar savestates `0x9A54` da 1.0.23 no fork | aberta | — | savestate-formato-9a54-rejeitado-pelo-fork | `62b5fc1a0e` |
| [TASK-0050](TASK-0050-detectar-limite-de-clock-do-aparelho.md) — avisar quando o aparelho segura o clock da CPU | concluída | — | gos-samsung-limita-clock-a-metade-em-jogo | `5c91eb8452` `5e64ed41dd` |
| [TASK-0051](TASK-0051-acao-para-o-limite-do-aparelho.md) — dar ao usuário a ação que desarma o limite | concluída | — | gos-samsung-limita-clock-a-metade-em-jogo | `1fb585c75a` `13dff0ba1e` |
| [TASK-0052](TASK-0052-avisar-do-limite-a-cada-sessao.md) — avisar do limite a cada jogo, e conferir o GOS de verdade | em andamento | — | gos-samsung-limita-clock-a-metade-em-jogo | `0ceab6e352` `30320436c6` |
| [TASK-0053](TASK-0053-aviso-do-limite-vira-dialogo.md) — o aviso do limite vira diálogo com os passos | em andamento | — | gos-samsung-limita-clock-a-metade-em-jogo | `1bb871d638` `4b53209584` |
| [TASK-0054](TASK-0054-assistente-do-limite-do-aparelho.md) — aviso curto com assistente passo a passo, que cabe deitado | em andamento | — | gos-samsung-limita-clock-a-metade-em-jogo | `f48289a944` `6ba6fd5f61` `fdaec8fa11` `ecdbc69d3c` |
| [TASK-0055](TASK-0055-contadores-de-desempenho-que-nao-mentem.md) — o `PerfLog` diz a verdade sobre EE, GS, VU e GPU | em andamento | — | — | `ea80c1041e` `6da3643193` |
| [TASK-0056](TASK-0056-wfe-sem-event-stream-trava-o-spin.md) — não estacionar num `WFE` que ninguém promete acordar | revertida | — | — | `9e9e27a203` `d71a0631d9` |
| [TASK-0057](TASK-0057-limitar-a-taxa-do-fundo-2d-da-biblioteca.md) — limitar a taxa do fundo 2D da biblioteca | em andamento | — | — | `b3fec407c4` `323e5043af` `4dcfb14c4f` `8c0aa08de0` |
| [TASK-0058](TASK-0058-medir-release-contra-debug.md) — medir `githubRelease` contra `githubDebug` | concluída | — | — | `faf8e2ac6a` `1958e32775` `b0813f663b` |
| [TASK-0059](TASK-0059-assistente-ensina-a-desabilitar-o-gos.md) — assistente abre no início do app e ensina o conserto que funciona | concluída | — | gos-samsung-limita-clock-a-metade-em-jogo | `1457ca969f` `e32ce285e4` |
| [TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) — relógio de ticks quando `CNTFRQ_EL0` lê zero | concluída | — | cntfrq-el0-lido-como-zero-zera-todo-relogio-de-ticks | `5a786dfeb1` `b159c8333b` |
| [TASK-0061](TASK-0061-cobertura-de-capa-2d-do-catalogo.md) — medir a capa 2D que aparece, e não o campo preenchido | em andamento | — | — | — |
| [TASK-0062](TASK-0062-teclado-virtual-toque-fora-e-latencia.md) — teclado virtual: toque fora fecha, e a tecla deixa de esperar o dedo subir | em andamento | — | teclado-virtual-scrim-de-tamanho-zero | `c2b443a275` |
| [TASK-0063](TASK-0063-fundo-da-biblioteca-para-de-animar.md) — o fundo 2D da biblioteca para de animar | concluída | — | — | `4fb68f57d8` `1e503a761a` |
| [TASK-0064](TASK-0064-devolver-o-controle-do-piso-de-z.md) — devolver o controle do piso de Z do PS2, que o Mali no Vulkan tira sem volta | em andamento | — | mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta | `460a369d50` `3c08cfa9c5` |
| [TASK-0065](TASK-0065-veredito-do-renderer-em-todo-relato.md) — o veredito do renderer automático em todo relato, e a regra `auto-vulkan` registrada | em andamento | — | veredito-do-renderer-automatico-so-chega-a-relato-quando-ha-crash | `2a6fbbee39` |
| [TASK-0066](TASK-0066-rede-de-seguranca-do-renderer-automatico.md) — rede de segurança do renderer automático, que a linha anterior tinha e o fork perdeu | em andamento | — | renderer-automatico-sem-rede-de-seguranca-no-fork | `1e4d1fd35b` |
| [TASK-0067](TASK-0067-merge-com-o-upstream.md) — `git merge upstream/master`: 72 commits, 5 conflitos, GS todo em auto-merge | concluída | FEAT-0001 | — | `6a86b38ebf` `e047ce36fe` `72ee16014d` `ce5ccabe7a` |
| [TASK-0068](TASK-0068-realce-do-teclado-sem-recompor-o-grid.md) — mover o realce do teclado deixa de recompor as quarenta teclas | em andamento | — | — | `ecb65afa4e` |
| [TASK-0069](TASK-0069-laco-de-quadro-do-analogico-so-existe-com-analogico.md) — o laço de quadro do analógico só deve existir quando há analógico | em andamento | — | — | `1fc406684a` |
| [TASK-0070](TASK-0070-onda-xmb-em-gl-tambem-para-de-animar.md) — a onda XMB em GL também para de animar | em andamento | — | — | `ff096183e8` |
| [TASK-0071](TASK-0071-passo-do-direcional-nao-recompoe-a-pagina.md) — um passo do direcional recompõe duas linhas, não a página inteira | em andamento | — | configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread | `c67fb87bff` `34baa8f625` `cd9da551a8` |
| [TASK-0073](TASK-0073-lancamento-externo-entrega-file-uri-cru-ao-core.md) — o lançamento externo entrega `file://` cru ao core, e o jogo não boota | concluída | — | intent-view-externo-abre-o-app-e-nao-boota-o-jogo | `7586e4afaa` `754bbbbeb7` |
| [TASK-0072](TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md) — retirar a regra `gl-arm-g52-r38-auto-vulkan`: o defeito é do título, não do driver | em andamento | — | — | `e1fa90a93c` |
| [TASK-0074](TASK-0074-musica-do-menu-de-pausa-nasce-desligada.md) — a música do menu de pausa nasce desligada | concluída | — | — | `9f699f9390` `fb9ee67b55` |
| [TASK-0075](TASK-0075-publicacao-do-fork-em-trilha-propria.md) — o fork publica em trilha própria, sem tocar nos clientes da linha antiga | concluída | — | — | `6bb695c0c3` `99d6150596` |
| [TASK-0076](TASK-0076-icone-do-app-volta-a-ser-so-o-padrao.md) — o ícone do app volta a ser só o padrão, e o seletor sai | concluída | — | — | `8d0964b416` `695228cd38` `a3b78a343e` |
| [TASK-0077](TASK-0077-aviso-do-gos-ganha-nao-mostrar-de-novo.md) — o aviso do GOS ganha "não mostrar de novo", e o item de menu vira a porta de volta | concluída | — | gos-samsung-limita-clock-a-metade-em-jogo | `ed3e7e7c46` `fe66b785f3` |

> **O índice não pode mais ficar atrasado em silêncio.** Ele já esteve — 22 linhas de fora, das
> TASK-0018 a TASK-0037 e a TASK-0039 —, porque o `--fix` só sabia substituir uma linha existente e
> anunciava sucesso quando não havia o que substituir. Desde a
> [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md) ele **insere** a linha que falta, e
> `check_traceability.py` **reprova** quando uma task concluída não tem linha aqui. Ver
> [`checktraceability-fix-nao-insere-task-ausente-do-indice`](../bugs/open/armsx2-fork/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md).
>
> As colunas `Status`, `Feature` e `Commit` são **derivadas** — reescritas a cada `--fix` a partir do
> arquivo da task e do git. `Task` e `Resolve` são prosa e ficam como foram escritas. A exceção é a
> célula `Commit` das TASK-0001 a TASK-0015: os commits delas nasceram na linha anterior do produto,
> que não tem história comum com o fork, então `HEAD` não os alcança e o `--fix` **preserva** o que
> estiver escrito em vez de apagar.
>
> Os hashes desta tabela são resolvidos **só do que `HEAD` alcança**. Não é detalhe: números de task
> colidem entre ramos — há uma TASK-0016 aqui e outra, sobre assunto completamente diferente, em
> `feature/handoff-end-to-end` ([bug](../bugs/open/armsx2-fork/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md)).
