# TASK-0042: tirar do validador a regra "uma task = um commit"

- **Status:** concluída
- **Criada em:** 2026-08-28
- **Concluída em:** 2026-08-28
- **Feature:** [FEAT-0002](../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0042:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pedido do usuário: *"tire essa regra do validador"*, sobre a reprovação permanente que o
`check_traceability.py` vinha dando:

```
- docs/task/TASK-0016-base-do-fork.md: uma task = um commit, mas ha 2 commits com assunto 'TASK-0016': e68aef1491, 9388c6a261
- docs/task/TASK-0017-identidade-do-produto.md: uma task = um commit, mas ha 2 commits com assunto 'TASK-0017': 186dec5544, 7c6215dff0
```

## O que a regra custava

Não era só ruído nesses dois casos. Ela **empurra para `--amend`** toda vez que algo da task volta:
na [TASK-0040](TASK-0040-fila-de-download-em-tela-propria.md) foi preciso emendar o commit já feito
só para corrigir uma linha do registro, porque um segundo commit `TASK-0040:` reprovaria. Emendar
reescreve o histórico — e o próprio validador tem uma checagem inteira (`commit_is_reachable`)
dedicada a pegar órfão de `--amend`. A regra estava criando o problema que a outra checagem existe
para detectar.

## O que se perde, dito com todas as letras

A regra garantia que `git log --grep='^TASK-NNNN:'` resolvesse para **exatamente um** hash, e é
disso que vive a coluna `Commit` do índice. Sem ela, uma task pode ter N commits, e a coluna precisa
comportar N hashes. É o preço, e ele é pago abaixo em vez de ignorado.

O que **não** se perde: nada de commit sem task. A regra que existe por causa das 1.0.20/21/22 é
"nenhum commit de código sem task", e essa continua valendo e continua sendo verificada.

## Escopo

**Entra:**

1. `scripts/check_traceability.py`: sai o `elif len(found) > 1: fail(...)`. Task `concluída` segue
   exigindo **pelo menos um** commit com o assunto — a checagem que importa.
2. `fill_index` no mesmo arquivo: hoje ele faz `if len(found) != 1: continue`, ou seja, uma task com
   dois commits ficaria **sem hash nenhum** no índice, em silêncio. Passa a escrever todos, em
   ordem cronológica.
3. Os quatro lugares que enunciam a regra em prosa, para tool e documentação não divergirem:
   `CLAUDE.md`, `docs/README.md` (o quadro da regra, o passo 2 do fluxo e a lista do validador) e
   `docs/task/README.md`.

**Fica de fora, deliberadamente:**

- **Reescrever o histórico da TASK-0016 e da TASK-0017.** Elas passam a ser legais como estão; era
  esse o pedido.
- Mexer nas outras checagens. Campo obrigatório, status, link bidirecional task↔feature↔bug, hash
  órfão e "concluída sem commit" continuam iguais.
- Afrouxar "nenhum commit de código sem task". Essa é a regra que motivou o sistema inteiro.

## Como validar

```
python scripts/check_traceability.py      # sem as duas linhas de TASK-0016/0017
python scripts/check_traceability.py --fix
```

E uma verificação de que o índice não regrediu: a linha de uma task com dois commits deve mostrar os
dois hashes, não uma célula vazia.

## Resultado

Entregue. A reprovação some:

```
$ python scripts/check_traceability.py
(sem as linhas de TASK-0016 e TASK-0017)
```

### A premissa da task estava errada, e a verificação derrubou

Escrevi acima, e no primeiro commit, que TASK-0016 e TASK-0017 "têm dois commits cada". **Não têm.**
Ao conferir hash por hash antes de fechar:

| Hash | Assunto | Ramo |
|---|---|---|
| `9388c6a261` | TASK-0016: estabelece a base do fork | `feature/fork-upstream-android` |
| `e68aef1491` | TASK-0016: tira o parse do catálogo da main thread | `feature/handoff-end-to-end` |

São **duas tasks diferentes com o mesmo número**, em ramos sem história comum, e `commits_for_task`
procura em `--all`. A reprovação nunca foi "uma task commitada duas vezes"; era colisão de
numeração. Registrado em [`numeros-de-task-colidem-entre-ramos`](../bugs/open/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md).

Isso **não** invalida a remoção da regra — o custo real dela (empurrar para `--amend`, medido na
TASK-0040) continua valendo, e era esse o pedido. Invalida a justificativa que eu tinha dado com os
dois casos.

E cobrou um preço imediato: com a regra fora, `fill_index` passou a gravar **todos** os hashes e
escreveu na linha da TASK-0016 do fork o hash da TASK-0016 do handoff. Corrigido aqui mesmo —
`commits_for_task` ganhou `reachable_only`, e o índice passa a resolver só o que `HEAD` alcança,
porque o índice descreve **este** ramo. A validação continua em `--all`, que é o que evita reprovar
task anterior ao fork.

### Outras duas coisas que só apareceram ao fazer

- **`fill_index` só roda com a validação passando** (`main()` retorna 1 antes de chegar nele). Quem
  usa `--fix` para consertar o índice enquanto há qualquer outro problema aberto não recebe nada, e
  o script não avisa que pulou. Não mexi — é escopo da [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md) —
  mas fica registrado, porque me custou uma rodada.
- **Cada `--fix` acrescentava um espaço** na célula de commit (o `\s*` do padrão já engolia o
  espaço, e a substituição concatenava outro). Um `rstrip()` resolveu.

Esta task tem **dois commits**, de propósito: o segundo é esta correção mais o índice. Sob a regra
que acabou de sair, teria exigido `--amend` — exatamente o que ela custava, e exatamente o tipo de
volta que agora é permitido.
