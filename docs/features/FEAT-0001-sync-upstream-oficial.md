# FEAT-0001: Convergir com o ARMSX2 oficial (perfil de GPU, banco de drivers, ANGLE)

- **Status:** em andamento
- **Criada em:** 2026-08-24
- **Concluída em:** —

## Objetivo

Parar de manter uma divergência própria no caminho gráfico do Android e passar a consumir a
infraestrutura que o `ARMSX2/ARMSX2` já construiu: identidade de driver resolvida, banco de bugs de
driver, política de framebuffer fetch como função pura e testada, cache de shader chaveado por
driver e o renderer ANGLE.

## Justificativa

Quatro rodadas de correção gráfica entre 1.0.17 e 1.0.22 trocaram um sintoma por outro nos Samsung
Galaxy A07 e A15 — preto, vermelho, falso positivo do monitor visual e, por fim, "o jogo nem abre
mais". A análise completa está em
[`plano-grafico-mali-convergencia-upstream.md`](../plano-grafico-mali-convergencia-upstream.md).

A causa estrutural é única: **todas as nossas decisões gráficas partem do nome do GPU ou do nome do
jogo, nunca da versão do driver** — que é onde o defeito realmente mora. O upstream resolveu isso
com infraestrutura, e o código já está buscado no repositório desde 18/08/2026
(`upstream/master` = `be72a8e1eb`), sem uso.

## Bugs que motivaram

- [gs-tela-preta-silenciosa-sem-diagnostico-a07](../bugs/open/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md) — tela preta no A07, sem log que permita diagnosticar
- [gs-mali-tela-vermelha-e-page-fault-driver](../bugs/open/gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md) — tela vermelha no A15 e page fault dentro de `libGLES_mali.so`
- [graphicshealthmonitor-falso-positivo-cenas-escuras](../bugs/open/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md) — 38 trocas indevidas de renderer em cenas escuras legítimas

## Tasks

| Task | Status | Descrição |
|---|---|---|
| [TASK-0001](../task/TASK-0001-sistema-rastreabilidade.md) | concluída | Sistema de rastreabilidade feature/task/bug (pré-requisito de processo) |
| [TASK-0002](../task/TASK-0002-bloco-a-arquivos-perfil-gpu.md) | concluída | **Bloco A** — trazer os 10 arquivos autocontidos do upstream (perfil de GPU, banco de drivers, política de fbfetch, testes) sem ligar em nada |
| [TASK-0003](../task/TASK-0003-bloco-b1-shader-cache-driver.md) | concluída | **Bloco B1** — assinatura de driver no `GLShaderCache` + bump de `SHADER_CACHE_VERSION` (correção da tela branca) |
| [TASK-0006](../task/TASK-0006-diagnostico-boot-gs.md) | concluída | Diagnóstico de boot do GS emitido sem depender do log ligado (destrava a TASK-0004) |
| [TASK-0004](../task/TASK-0004-bloco-b2-log-boot-gs.md) | concluída | **Bloco B2** — perfil de driver publicado no `GSDevice.h` e na linha de boot do GS, sem alterar decisão de renderização |
| [TASK-0007](../task/TASK-0007-cas-precisao-gles.md) | concluída | Precisão GLES no shader CAS (corrige o sharpening em Mali) |
| [TASK-0008](../task/TASK-0008-port-mfifo-spr-upstream.md) | concluída | Port do MFIFO/SPR do upstream (não corrigiu o SotC; mantido por convergência) |
| [TASK-0009](../task/TASK-0009-publicar-versao.md) | concluída | Publicar a versão com as correções desta branch (1.0.23 / versionCode 37) |
| [TASK-0005](../task/TASK-0005-bloco-c-pontos-de-consumo.md) | concluída | **Bloco C** — a decisão de fbfetch passa a vir do banco de drivers; a regra de MGS3 por título e a troca automática de renderer deixam de existir |
| [TASK-0014](../task/TASK-0014-comparador-superficie-jni.md) | concluída | Comparador da superfície JNI contra o upstream, por nome **e** assinatura |
| [TASK-0016](../task/TASK-0016-base-do-fork.md) | concluída | **Base do fork** — branch a partir da árvore deles, com a nossa infraestrutura de processo dentro |
| [TASK-0017](../task/TASK-0017-identidade-do-produto.md) | concluída | **Identidade** — applicationId, versionCode, nome, ícone e o produto no i18n |
| [TASK-0018](../task/TASK-0018-telemetria-no-fork.md) | concluída | **Telemetria** — envio ao `/logErr`, `ApplicationExitInfo` e decodificação de tombstone, sem tocar no core |
| [TASK-0019](../task/TASK-0019-mecanismo-de-atualizacao.md) | concluída | **Updater** — mecanismo portado e build/manifesto ligados |
| [TASK-0020](../task/TASK-0020-seam-do-updater.md) | concluída | **Seam do updater** — aponta para o nosso `version.json`; fim do canal nightly |
| [TASK-0021](../task/TASK-0021-assinatura-e-publicador.md) | concluída | **Assinatura + publicador** — chave de produção e trava `-Announce` |
| [TASK-0022](../task/TASK-0022-primeira-impressao-do-app.md) | concluída | **Primeira impressão** — marca, tela de entrada, fundo escuro e o título do toolbar |
| [TASK-0023](../task/TASK-0023-catalogo-de-roms.md) | concluída | **Catálogo de ROMs** — as 12.628 entradas, o download em primeiro plano e o ciclo baixar → jogar |
| [TASK-0024](../task/TASK-0024-catalogo-como-tela-inicial.md) | concluída | **Catálogo como tela inicial** — a grade que o app abre, e pausar/retomar/cancelar um download |
| [TASK-0025](../task/TASK-0025-grade-unica-catalogo-na-biblioteca.md) | concluída | **Grade única** — o catálogo fundido na biblioteca, com tarja de baixado e filtro |
| [TASK-0026](../task/TASK-0026-bios-embarcada.md) | concluída | **BIOS embarcada** — o arquivo que o `copyAssetAll("bios")` do fork já esperava |
| [TASK-0027](../task/TASK-0027-pasta-no-gerenciador-de-arquivos.md) | concluída | **Pasta no gerenciador de arquivos** — o `DocumentsProvider` da árvore anterior |
| [TASK-0028](../task/TASK-0028-creditos-na-tela-sobre.md) | concluída | **Créditos** — a lista de pessoas que a tela Sobre do fork não mostrava |
| [TASK-0029](../task/TASK-0029-filtro-visivel-na-barra.md) | concluída | **O filtro se anuncia** — um estado global que escondia 12.627 cartões sem dizer nada |
| [TASK-0030](../task/TASK-0030-adotar-pasta-de-dados-da-versao-anterior.md) | concluída | **Pasta de dados legada** — quem escolheu pasta própria não a perde ao atualizar |
| [TASK-0031](../task/TASK-0031-detector-de-veneno-no-fork.md) | concluída | **Detector de veneno da DMA** — a instrumentação do crash de Shadow of the Colossus |
| [TASK-0032](../task/TASK-0032-rotulo-na-grade.md) | concluída | **Título sob a capa** — variantes regionais deixam de parecer cartões repetidos |
| [TASK-0033](../task/TASK-0033-enxugar-menu-lateral.md) | concluída | **Menu enxuto** — saem BIOS de inicialização, os três links do upstream, novidades e amigos |
| [TASK-0034](../task/TASK-0034-campo-de-busca-no-topo.md) | concluída | **Busca no topo** — o campo que a versão anterior tinha, mais as teclas do teclado traduzidas |
| [TASK-0035](../task/TASK-0035-remover-cards-github-pcsx2-creditos.md) | concluída | **Tela Sobre enxuta** — saem os cards GitHub, PCSX2 e Créditos |
| [TASK-0036](../task/TASK-0036-musica-de-fundo-desligada-por-padrao.md) | concluída | **Música da biblioteca desligada por padrão** — o toggle continua em Configurações |
| [TASK-0037](../task/TASK-0037-pastas-de-rom-como-tela.md) | concluída | **Pastas de ROM como tela** — sai o assistente de "Próximo/Voltar" do menu do dia a dia |
| [TASK-0038](../task/TASK-0038-fila-de-download-visivel.md) | concluída | **Fila de download visível** — a seção da versão anterior volta, e o progresso passa a chegar na tela |
| [TASK-0039](../task/TASK-0039-credito-da-musica-so-quando-toca.md) | concluída | **Crédito condicional** — a tela Sobre só cita a música quando o toggle está ligado |
| [TASK-0040](../task/TASK-0040-fila-de-download-em-tela-propria.md) | concluída | **Downloads como tela** — a fila sai de cima da grade e ganha destino próprio, como a aba "Salvos" |
| [TASK-0041](../task/TASK-0041-permissao-de-notificacao-do-download.md) | concluída | **Notificação de download visível** — a permissão passa a ser pedida, e o texto deixa de dizer "ARMSX2" |

A troca automática de renderer saiu na própria TASK-0005. ANGLE e `AndroidGpuProfileOverride` exposto
nas Configurações continuam por numerar, e agora dependem menos de log de campo do que da decisão de
transplantar ou não — ver [`spike-transplante-upstream-2026-08-26.md`](../spike-transplante-upstream-2026-08-26.md).

## Bugs originados por esta feature

Nenhum até agora.

## Critérios de conclusão

1. Um evento de boot gráfico de um Galaxy A07 **e** de um A15 chega à telemetria com GPU, driver e
   versão preenchidos — o aparelho passou a ser observável.
2. A abertura do jogo no A07 volta a funcionar e sobrevive a uma troca de driver simulada.
3. Zero eventos de troca automática de renderer.
4. MGS3 no A15 renderiza correto **e** com FPS igual ou melhor que 1.0.16, em Vulkan.
5. Nenhuma regressão de FPS em Shadow of the Colossus em Mali.
6. Nenhuma regra gráfica restante decidida por nome de GPU ou título de jogo.
