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

Duas coisas que só apareceram ao fazer:

- **`fill_index` só roda com a validação passando** (`main()` retorna 1 antes de chegar nele). Quem
  usa `--fix` para consertar o índice enquanto há qualquer outro problema aberto não recebe nada, e
  o script não diz que pulou. Não mexi nisso — é escopo da [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md),
  que já existe para o `--fix` — mas fica registrado aqui porque me custou uma rodada.
- **O índice não tinha linha para a TASK-0016 nem para a TASK-0017**, justamente as duas com dois
  commits. Sem elas, o caminho novo do `fill_index` não seria exercitado por nada. Foram
  acrescentadas, e são a prova de que a coluna comporta N hashes.

Esta task tem **dois commits**, de propósito: o segundo grava no índice o hash do primeiro. Sob a
regra que acabou de sair, isso teria exigido `--amend` — exatamente o que ela custava.
