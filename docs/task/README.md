# Tasks

Uma task é **uma unidade de trabalho que cabe em um commit**. Se não couber, provavelmente era
grande demais: quebre em duas. O limite é intencional — é o que faz `git revert <commit>` desfazer
exatamente uma decisão, sem arrastar outras junto.

É orientação, e não regra verificada: desde a
[TASK-0042](TASK-0042-remover-regra-um-commit-por-task.md) uma task pode ter mais de um commit, e o
índice registra todos. Voltar a uma task já commitada é melhor que emendar o commit que já existe.

Ver [`docs/README.md`](../README.md) para a regra de commit e o desenho geral da rastreabilidade.

## Convenção de nome

`TASK-NNNN-<slug-kebab-case>.md` — o número é sequencial, nunca reaproveitado, e é o identificador
estável usado nos links. O slug pode ser reescrito; o número, não.

**Único dentro deste ramo, e só dentro dele.** `feature/fork-upstream-android` e
`feature/handoff-end-to-end` não têm história comum e numeraram tasks em paralelo: existe uma
TASK-0016 em cada, sobre assuntos completamente diferentes. Enquanto isso não for decidido
([bug](../bugs/open/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md)), um `TASK-NNNN` só
identifica alguma coisa quando se sabe de que ramo se está falando.

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
- **Bugs que resolve:** [<slug do bug>](../bugs/open/<arquivo>.md) — ou `nenhum`
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
| [TASK-0001](TASK-0001-sistema-rastreabilidade.md) — sistema de rastreabilidade feature/task/bug | concluída | FEAT-0001 | — |  `c572dc095b` |
| [TASK-0002](TASK-0002-bloco-a-arquivos-perfil-gpu.md) — bloco A: arquivos de perfil de GPU do upstream | concluída | FEAT-0001 | — |  `335e4bc27a` |
| [TASK-0003](TASK-0003-bloco-b1-shader-cache-driver.md) — bloco B1: assinatura de driver no shader cache | concluída | FEAT-0001 | gs-tela-preta-silenciosa-sem-diagnostico-a07 |  `6c3f292f35` |
| [TASK-0004](TASK-0004-bloco-b2-log-boot-gs.md) — bloco B2: perfil de driver no `GSDevice` e na linha `GSBoot` | concluída | FEAT-0001 | gs-tela-preta-silenciosa-sem-diagnostico-a07 |  `e70e30b2e7` |
| [TASK-0005](TASK-0005-bloco-c-pontos-de-consumo.md) — bloco C: fbfetch decidido pelo banco de drivers | concluída | FEAT-0001 | gs-mali-tela-vermelha-e-page-fault-driver, graphicshealthmonitor-falso-positivo-cenas-escuras |  `0983a42d49` |
| [TASK-0006](TASK-0006-diagnostico-boot-gs.md) — diagnóstico de boot do GS sem depender do log | concluída | FEAT-0001 | jni-bridge-nao-resolve-em-thread-nativa |  `d8d08ee0e6` |
| [TASK-0007](TASK-0007-cas-precisao-gles.md) — precisão GLES no shader CAS | concluída | FEAT-0001 | cas-shader-gles-sem-precisao-mali |  `7c3f5e775a` |
| [TASK-0008](TASK-0008-port-mfifo-spr-upstream.md) — port do MFIFO/SPR do upstream | concluída | FEAT-0001 | — (hipótese derrubada) |  `cca3bf0c69` |
| [TASK-0009](TASK-0009-publicar-versao.md) — publicar a 1.0.23 | concluída | FEAT-0001 | — |  `0bc7e826d0` |
| [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md) — corrigir o validador de rastreabilidade | aberta | FEAT-0002 | checktraceability-grep-casa-corpo-do-commit, checktraceability-fix-nao-insere-task-ausente-do-indice | — |
| [TASK-0011](TASK-0011-impor-regra-de-commit-mecanicamente.md) — impor a regra de commit mecanicamente | aberta | FEAT-0002 | rastreabilidade-sem-verificacao-de-git-para-task | — |
| [TASK-0012](TASK-0012-portao-de-boot-nao-perde-informacao.md) — portão de boot honra falha de init nativa e `onNewIntent` | concluída | — | app-falha-de-init-nativa-sem-consumidor, bootsplash-singletop-descarta-intent-novo |  `3e25c6adb1` |
| [TASK-0013](TASK-0013-detector-valor-veneno-dma.md) — detector de valor-veneno no DMA (instrumenta o crash do SotC) | concluída | — | — (instrumenta, não corrige) |  `a71a51e17d` |
| [TASK-0014](TASK-0014-comparador-superficie-jni.md) — comparador da superfície JNI contra o upstream | concluída | FEAT-0001 | — |  `353dae44f1` |
| [TASK-0015](TASK-0015-manifesto-catalogo-curado.md) — manifesto de catálogo curado + `sort_manifest.py` que o preserva | concluída | — | — |  `ee775b3015` |
| [TASK-0016](TASK-0016-base-do-fork.md) — base do fork sobre a árvore Android do upstream | concluída | FEAT-0001 | — | `9388c6a261` |
| [TASK-0017](TASK-0017-identidade-do-produto.md) — identidade do RetroSystem PS2 no fork | concluída | FEAT-0001 | — | `7c6215dff0` |
| [TASK-0038](TASK-0038-fila-de-download-visivel.md) — fila de download visível, e progresso que chega na tela | concluída | FEAT-0001 | fila-download-sem-tela-e-sem-progresso-ao-vivo | `7e4f9d41ce` |
| [TASK-0040](TASK-0040-fila-de-download-em-tela-propria.md) — fila de download em tela própria | concluída | FEAT-0001 | fila-download-congela-tela-ao-pausar | `a3c7cccf51` |
| [TASK-0041](TASK-0041-permissao-de-notificacao-do-download.md) — permissão de notificação do download | concluída | FEAT-0001 | notificacao-de-download-invisivel-sem-pedir-permissao | `f1d206eacf` |
| [TASK-0042](TASK-0042-remover-regra-um-commit-por-task.md) — sai a regra "uma task = um commit" | concluída | FEAT-0002 | — | `d569063f37` `8395d24ab6` |
| [TASK-0043](TASK-0043-aviso-anti-revenda-do-upstream.md) — boot sem faixas sobrepostas | concluída | FEAT-0001 | — | `0538bd2c94` |
| [TASK-0044](TASK-0044-telemetria-de-boot-e-de-assets.md) — telemetria de boot e de assets | concluída | FEAT-0001 | — | `ea3c0bddf8` |
| [TASK-0045](TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md) — baixar só formato que o emulador abre, e manter a capa | concluída | — | catalogo-download-entrega-formato-nao-bootavel, biblioteca-jogo-baixado-perde-a-capa | — |
| [TASK-0046](TASK-0046-encerrar-thread-mtvu-no-shutdown.md) — encerrar a thread da MTVU no shutdown da VM | concluída | — | mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm | — |
| [TASK-0048](TASK-0048-descompactar-7z-e-zip-no-download.md) — descompactar `.7z` e `.zip` depois do download | em andamento | — | — | — |
| [TASK-0049](TASK-0049-carregar-savestates-0x9A54.md) — carregar savestates `0x9A54` da 1.0.23 no fork | aberta | — | savestate-formato-9a54-rejeitado-pelo-fork | — |
| [TASK-0050](TASK-0050-detectar-limite-de-clock-do-aparelho.md) — avisar quando o aparelho segura o clock da CPU | concluída | — | gos-samsung-limita-clock-a-metade-em-jogo | — |

> **O índice está 22 linhas atrasado.** As tasks TASK-0018 a TASK-0037 e a TASK-0039 existem, estão
> commitadas e não aparecem acima. Não é descuido de quem as escreveu: `check_traceability.py --fix`
> só preenche o hash de linhas **que já estão** na tabela e não insere as que faltam — o defeito
> registrado em [`checktraceability-fix-nao-insere-task-ausente-do-indice`](../bugs/open/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md)
> e endereçado pela [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md), que segue aberta.
> Enquanto ela não sai, a listagem confiável é `git log --grep='^TASK-'`.
>
> Os hashes desta tabela são resolvidos **só do que `HEAD` alcança**. Não é detalhe: números de task
> colidem entre ramos — há uma TASK-0016 aqui e outra, sobre assunto completamente diferente, em
> `feature/handoff-end-to-end` ([bug](../bugs/open/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md)).
