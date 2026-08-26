# TASK-0016: estabelecer a base do fork sobre a árvore Android do upstream

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0016:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (não chega ao cliente)

## Objetivo

Criar a branch do fork a partir da árvore do upstream e trazer para dentro dela a infraestrutura de
processo, para que o trabalho seguinte já nasça rastreável.

Esta é a etapa 1 do [plano do fork](../plano-fork-sobre-upstream.md).

## Escopo

**Entra:**
- Branch `feature/fork-upstream-android`, criada de `662b114168` (upstream/master, 25/08/2026),
  como `git worktree` em `D:/projects/play2/ARMSX2-fork`.
- `docs/` — processo, tasks, bugs, features e os planos, incluindo o do próprio fork.
- `scripts/` — `check_traceability.py` e `compare_jni_surface.py`.
- `catalog_manifest_ps2.txt` + `sort_manifest.py` — a curadoria da
  [TASK-0015](TASK-0015-manifesto-catalogo-curado.md), trazida intacta.
- `CLAUDE.md` **reescrito** para esta árvore.
- Uma correção em `check_traceability.py`, explicada abaixo.

**NÃO entra:**
- Nenhuma linha de código do app ainda. Identidade, telemetria, updater e catálogo são as etapas 3
  a 7 do plano.
- `build-and-upload.ps1`, `deploy.ps1` e os demais scripts de publicação. Eles apontam para caminhos
  que não existem mais nesta árvore, e um script versionado que aponta para o lugar errado é
  exatamente o tipo de código morto que este processo existe para evitar. Vêm na etapa de publicação,
  adaptados.

## A correção no validador, e por que ela era obrigatória

`commits_for_task()` resolvia o vínculo task↔commit com `git log --grep=^TASK-NNNN: HEAD`.

**Numa branch de fork isso reprova tudo.** A branch nasce da árvore do upstream e portanto não
alcança nenhum commit da linha anterior — as 15 tasks concluídas antes do fork passariam a acusar
*"nenhum commit alcançável"*. O registro mentiria sobre trabalho que existe e está no repositório,
só que noutro ramo.

Trocado para `--all`. Isso **não** enfraquece a checagem de commit órfão: aquela existe para hash
escrito à mão no campo `Commit:` de uma task, e um órfão de `--amend` não é alcançável por nenhuma
ref — portanto `--all` continua sem o enxergar.

## O que o `CLAUDE.md` novo acrescenta

Ele não é uma adaptação do antigo; foi reescrito, porque a árvore é outra. O que ele passa a
registrar e o anterior não tinha como ter:

- A inversão: a árvore deles é a base, nós somos o delta.
- **A regra que sustenta o fork:** correção de motor nasce como contribuição ao upstream, não como
  edição local. Sem ela, o fork suja de novo na velocidade medida na linha anterior (22 arquivos
  compartilhados do core editados em duas semanas).
- Os comandos de build **medidos**, com os três pré-requisitos que não são óbvios: as dependências
  do shaderc vêm da rede, o CMake tem de ser 3.31.6 (o 3.22.1 falha), e o Rust não é preciso.
- **Um achado desta task:** o `versionCode` default deles é **1088**; o nosso é 37. Publicar 1088
  por engano torna impossível voltar à nossa série, porque o Android recusa instalar versionCode
  menor sobre maior. Está registrado como restrição dura, ao lado do `applicationId`.

## Como validar

1. `python scripts/check_traceability.py` nesta árvore — **feito, OK, 15 tasks**. Antes da correção
   do `--all`, reprovava todas.
2. O build nativo desta árvore já foi medido em `662b114168` (mesmo commit): 825 s, exit 0. Ver
   [`spike-transplante-upstream-2026-08-26.md`](../spike-transplante-upstream-2026-08-26.md) §4b.

## Resultado

Entregue. A base existe e é rastreável. O que ela ainda **não** é: um app publicável — não tem a
nossa identidade, nem telemetria, nem updater, nem catálogo. Isso é o plano, etapas 3 a 7.
