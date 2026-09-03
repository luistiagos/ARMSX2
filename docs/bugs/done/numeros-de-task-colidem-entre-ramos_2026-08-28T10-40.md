# Bug: números de task colidem entre ramos, e o validador trata a colisão como duplicata

- **Detectado em:** 2026-08-28 10:40 (ao remover a regra "uma task = um commit", na TASK-0042)
- **Origem:** `scripts/check_traceability.py`, `commits_for_task()` — busca em `--all`; e a política
  de numeração em `docs/task/README.md`, que promete um número "nunca reaproveitado"
- **Errors (serviço):** nenhum — é defeito de processo, não de app
- **Classe:** rastreabilidade (o identificador não identifica)
- **Reincidência:** primeira vez registrada; existe desde que o fork nasceu
- **Feature:** [FEAT-0002](../../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Tasks que o resolvem:** [TASK-0078](../../task/TASK-0078-numero-de-task-identifica-neste-ramo.md)

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

## Correção — 2026-09-03 ([TASK-0078](../../task/TASK-0078-numero-de-task-identifica-neste-ramo.md))

Das quatro saídas listadas acima, foi feita a **4 com dentes**: a unicidade passa a ser por ramo,
**declarada e verificada**, sem tocar em `feature/handoff-end-to-end`. Renumerar, prefixar ou
reservar faixa lá é decisão de lá, e o escopo desta correção é o ARMSX2-fork.

### O que mudou no validador

`commits_for_task()` continua procurando em `--all`, mas quem decide agora é `check_task_has_commit()`:

- **A partir da TASK-0016** — a primeira escrita depois do fork — uma task `concluída` precisa de
  commit **alcançável de `HEAD`**. O commit homônimo do outro ramo não a satisfaz mais.
- **Abaixo dela**, a busca larga continua valendo. Não é concessão: as TASK-0001 a TASK-0015 foram
  concluídas na linha anterior do produto, cujos commits o fork não alcança por construção. Exigir
  `HEAD` delas seria o registro mentindo sobre trabalho que existe.
- A fronteira é uma constante única, `FORK_FIRST_TASK`, com o motivo escrito ao lado.

**A mensagem de reprovação distingue os dois casos**, que era metade do problema original — o
relato abre justamente com o validador dizendo "uma task = um commit, mas ha 2 commits", que foi
lido como "commitada duas vezes" e não era isso:

```
status 'concluída', mas o unico commit com assunto 'TASK-0099: ...' (ab12cd34) NAO e alcancavel
de HEAD -- e a task de outro ramo com o mesmo numero, nao esta. O numero de task so e unico
dentro do ramo.
```

### O que a medição de hoje mostrou

Nove números colidem, não um: **TASK-0016 a TASK-0024**, cada um com um commit em cada ramo. O
relato original tinha registrado dois (0016 e 0017) porque foi escrito antes de os outros
existirem.

### O que continua verdade, e agora está escrito

`docs/task/README.md` prometia um número "sequencial, nunca reaproveitado, e o identificador
estável usado nos links". Passou a dizer o que de fato vale: o número é único **dentro deste ramo**,
e um link `[TASK-NNNN]` só identifica alguma coisa quando se sabe de que ramo se fala.

### O que NÃO foi feito, e por quê

- **Renumerar** — corrigiria o passado e quebraria todo link já escrito nos dois ramos.
- **Prefixo por linha de produto** (`TASK-F-`/`TASK-H-`) — mexeria no validador, no gancho, na CI e
  em todo link existente, para resolver um problema que só aparece se os ramos forem fundidos.
- **Faixa 2000+ para o handoff** — exigiria alterar o outro ramo.

Se os dois ramos forem fundidos algum dia, os nove pares de arquivos homônimos continuam sendo um
problema, e aí a renumeração volta à mesa. Enquanto forem ramos independentes, o número identifica.

### Validação

`python -m pytest scripts/tests -q` — 30 testes. Os três novos montam um segundo ramo órfão com um
commit `TASK-0099:` de outro assunto e provam que ele não satisfaz a task daqui; que uma task
anterior à fronteira continua aceitando commit fora de `HEAD`; e que o caso normal não mudou. O
primeiro **falha** contra a versão anterior do script.
