# Bug: números de task colidem entre ramos, e o validador trata a colisão como duplicata

- **Detectado em:** 2026-08-28 10:40 (ao remover a regra "uma task = um commit", na TASK-0042)
- **Origem:** `scripts/check_traceability.py`, `commits_for_task()` — busca em `--all`; e a política
  de numeração em `docs/task/README.md`, que promete um número "nunca reaproveitado"
- **Errors (serviço):** nenhum — é defeito de processo, não de app
- **Classe:** rastreabilidade (o identificador não identifica)
- **Reincidência:** primeira vez registrada; existe desde que o fork nasceu
- **Feature:** [FEAT-0002](../../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Tasks que o resolvem:** nenhuma ainda

## Sintoma

O validador acusava, de forma permanente:

```
- docs/task/TASK-0016-base-do-fork.md: uma task = um commit, mas ha 2 commits com assunto 'TASK-0016': e68aef1491, 9388c6a261
- docs/task/TASK-0017-identidade-do-produto.md: uma task = um commit, mas ha 2 commits com assunto 'TASK-0017': 186dec5544, 7c6215dff0
```

Lido como "esta task foi commitada duas vezes". **Não é isso.**

## Causa raiz

São **duas tasks diferentes com o mesmo número**, em ramos sem história comum:

| Hash | Assunto | Ramo | Data |
|---|---|---|---|
| `9388c6a261` | TASK-0016: estabelece a base do fork sobre a arvore Android do upstream | `feature/fork-upstream-android` | 2026-08-26 |
| `e68aef1491` | TASK-0016: tira o parse do catalogo e o stat-storm da main thread no boot | `feature/handoff-end-to-end` | 2026-08-27 |
| `7c6215dff0` | TASK-0017: da ao fork a identidade do RetroSystem PS2 | `feature/fork-upstream-android` | 2026-08-26 |
| `186dec5544` | TASK-0017: preenche a grade da Home depois do primeiro frame | `feature/handoff-end-to-end` | 2026-08-27 |

Os dois ramos numeram tasks em paralelo, cada um com o seu `docs/task/`. `commits_for_task()`
procura em `--all` — de propósito, para não reprovar task anterior ao fork, cujo commit não é
alcançável de `HEAD` — e portanto colhe os dois.

Isto contradiz o que `docs/task/README.md` promete:

> `TASK-NNNN-<slug-kebab-case>.md` — o número é sequencial, **nunca reaproveitado**, e é o
> identificador estável usado nos links.

Ele é único *dentro de um ramo*. Entre ramos, não.

## Consequência já observada

Ao remover a regra "uma task = um commit" ([TASK-0042](../../task/TASK-0042-remover-regra-um-commit-por-task.md)),
o `fill_index` passou a gravar **todos** os hashes encontrados — e escreveu na linha da TASK-0016 do
fork o hash da TASK-0016 do handoff:

```
| [TASK-0016](TASK-0016-base-do-fork.md) … |  `9388c6a261` `e68aef1491` |
```

Um hash que, resolvido, mostra um commit sobre outro assunto, noutro ramo. Contornado na própria
TASK-0042 restringindo a escrita do índice a `HEAD` (`reachable_only=True`), mas isso é remendo no
consumidor: a ambiguidade do número continua.

## O que ainda está errado

- `git log --grep='^TASK-NNNN:'` — o vínculo autoritativo, segundo `docs/README.md` — é **ambíguo**
  em qualquer contexto que enxergue os dois ramos.
- Um link `[TASK-0016](...)` num documento significa coisas diferentes conforme o worktree.
- Se os ramos algum dia forem fundidos, os dois arquivos `TASK-0016-*.md` coexistem com números
  iguais e conteúdos distintos.

## Caminhos possíveis (decisão de processo, não de código)

1. **Faixas por ramo** — o fork usa 0016+, o handoff usa 2000+. Barato, resolve o futuro, não o
   passado.
2. **Renumerar um dos lados.** Corrige o passado e quebra todo link já escrito.
3. **Prefixo por linha de produto** (`TASK-F-0016` / `TASK-H-0016`). Mais invasivo no validador.
4. **Aceitar e escopar:** declarar que o número é único por ramo e fazer o validador (e a prosa)
   dizerem isso. É o estado de fato hoje, apenas sem estar escrito.

Sem task ainda: precisa da decisão antes.
