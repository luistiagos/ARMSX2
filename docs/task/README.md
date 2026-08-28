# Tasks

Uma task é **uma unidade de trabalho que cabe em um commit**. Se não couber, ela era grande demais:
quebre em duas. Esse limite é intencional — é o que faz `git revert <commit>` desfazer exatamente
uma decisão, sem arrastar outras junto.

Ver [`docs/README.md`](../README.md) para a regra de commit e o desenho geral da rastreabilidade.

## Convenção de nome

`TASK-NNNN-<slug-kebab-case>.md` — o número é sequencial, nunca reaproveitado, e é o identificador
estável usado nos links. O slug pode ser reescrito; o número, não.

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
| [TASK-0001](TASK-0001-sistema-rastreabilidade.md) — sistema de rastreabilidade feature/task/bug | concluída | FEAT-0001 | — | `c572dc095b` |
| [TASK-0002](TASK-0002-bloco-a-arquivos-perfil-gpu.md) — bloco A: arquivos de perfil de GPU do upstream | concluída | FEAT-0001 | — | `335e4bc27a` |
| [TASK-0003](TASK-0003-bloco-b1-shader-cache-driver.md) — bloco B1: assinatura de driver no shader cache | concluída | FEAT-0001 | gs-tela-preta-silenciosa-sem-diagnostico-a07 | `6c3f292f35` |
| [TASK-0004](TASK-0004-bloco-b2-log-boot-gs.md) — bloco B2: perfil de driver no `GSDevice` e na linha `GSBoot` | concluída | FEAT-0001 | gs-tela-preta-silenciosa-sem-diagnostico-a07 | `e70e30b2e7` |
| [TASK-0005](TASK-0005-bloco-c-pontos-de-consumo.md) — bloco C: fbfetch decidido pelo banco de drivers | concluída | FEAT-0001 | gs-mali-tela-vermelha-e-page-fault-driver, graphicshealthmonitor-falso-positivo-cenas-escuras | `0983a42d49` |
| [TASK-0006](TASK-0006-diagnostico-boot-gs.md) — diagnóstico de boot do GS sem depender do log | concluída | FEAT-0001 | jni-bridge-nao-resolve-em-thread-nativa | `d8d08ee0e6` |
| [TASK-0007](TASK-0007-cas-precisao-gles.md) — precisão GLES no shader CAS | concluída | FEAT-0001 | cas-shader-gles-sem-precisao-mali | `7c3f5e775a` |
| [TASK-0008](TASK-0008-port-mfifo-spr-upstream.md) — port do MFIFO/SPR do upstream | concluída | FEAT-0001 | — (hipótese derrubada) | `cca3bf0c69` |
| [TASK-0009](TASK-0009-publicar-versao.md) — publicar a 1.0.23 | concluída | FEAT-0001 | — | `0bc7e826d0` |
| [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md) — corrigir o validador de rastreabilidade | aberta | FEAT-0002 | checktraceability-grep-casa-corpo-do-commit, checktraceability-fix-nao-insere-task-ausente-do-indice | — |
| [TASK-0011](TASK-0011-impor-regra-de-commit-mecanicamente.md) — impor a regra de commit mecanicamente | aberta | FEAT-0002 | rastreabilidade-sem-verificacao-de-git-para-task | — |
| [TASK-0012](TASK-0012-portao-de-boot-nao-perde-informacao.md) — portão de boot honra falha de init nativa e `onNewIntent` | concluída | — | app-falha-de-init-nativa-sem-consumidor, bootsplash-singletop-descarta-intent-novo | `3e25c6adb1` |
| [TASK-0013](TASK-0013-detector-valor-veneno-dma.md) — detector de valor-veneno no DMA (instrumenta o crash do SotC) | concluída | — | — (instrumenta, não corrige) | `a71a51e17d` |
| [TASK-0014](TASK-0014-comparador-superficie-jni.md) — comparador da superfície JNI contra o upstream | concluída | FEAT-0001 | — | `353dae44f1` |
| [TASK-0015](TASK-0015-manifesto-catalogo-curado.md) — manifesto de catálogo curado + `sort_manifest.py` que o preserva | concluída | — | — | `ee775b3015` |
| [TASK-0038](TASK-0038-fila-de-download-visivel.md) — fila de download visível, e progresso que chega na tela | concluída | FEAT-0001 | fila-download-sem-tela-e-sem-progresso-ao-vivo | `7e4f9d41ce` |
| [TASK-0040](TASK-0040-fila-de-download-em-tela-propria.md) — fila de download em tela própria | concluída | FEAT-0001 | fila-download-congela-tela-ao-pausar | `a3c7cccf51` |
| [TASK-0041](TASK-0041-permissao-de-notificacao-do-download.md) — permissão de notificação do download | concluída | FEAT-0001 | notificacao-de-download-invisivel-sem-pedir-permissao | `f1d206eacf` |

> **O índice está 24 linhas atrasado.** As tasks TASK-0016 a TASK-0037 e a TASK-0039 existem, estão
> commitadas e não aparecem acima. Não é descuido de quem as escreveu: `check_traceability.py --fix`
> só preenche o hash de linhas **que já estão** na tabela e não insere as que faltam — o defeito
> registrado em [`checktraceability-fix-nao-insere-task-ausente-do-indice`](../bugs/open/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md)
> e endereçado pela [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md), que segue aberta.
> Enquanto ela não sai, a listagem confiável é `git log --grep='^TASK-'`.
