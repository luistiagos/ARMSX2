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
| [TASK-0019](../task/TASK-0019-mecanismo-de-atualizacao.md) | em andamento | **Updater** — mecanismo portado e build/manifesto ligados; o seam da UI aguarda a decisão do canal nightly |

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
