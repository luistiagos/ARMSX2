# TASK-0078: fazer o número da task identificar alguma coisa, ao menos dentro deste ramo

- **Status:** concluída
- **Criada em:** 2026-09-03
- **Concluída em:** 2026-09-03
- **Feature:** [FEAT-0002](../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Bugs que resolve:** [numeros-de-task-colidem-entre-ramos](../bugs/done/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0078:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (não altera o aplicativo)

## Objetivo

`docs/task/README.md` promete que o número da task é "sequencial, nunca reaproveitado, e o
identificador estável usado nos links". Ele não é: `feature/fork-upstream-android` e
`feature/handoff-end-to-end` não têm história comum e numeraram em paralelo. Medido hoje, **nove
números colidem** — de TASK-0016 a TASK-0024 —, cada um com dois commits de assuntos completamente
diferentes.

Ao fim desta task o número identifica uma task **dentro deste ramo**, e o validador prova isso em
vez de o documento prometer.

## Decisão

Das quatro saídas listadas no relatório, esta task faz **só o lado deste ramo**. Renumerar,
prefixar por linha de produto ou reservar uma faixa para o outro ramo exigiria alterar
`feature/handoff-end-to-end`, e a instrução foi explícita: corrigir só o que é do ARMSX2-fork.

Então: **a unicidade passa a ser por ramo, declarada e verificada aqui.**

## Escopo

**Entra:**

- **`commits_for_task()` deixa de aceitar o commit do outro ramo para task deste.** A partir da
  TASK-0016 — a primeira escrita depois do fork — uma task `concluída` precisa de commit
  **alcançável de `HEAD`**. Abaixo disso, a busca larga continua valendo, porque aquelas tasks
  foram de fato concluídas na linha anterior do produto e o fork não alcança os commits delas.
  A fronteira fica numa constante única, `FORK_FIRST_TASK`, com o motivo escrito ao lado.
- **A mensagem de reprovação diz o que aconteceu.** Quando existe commit com aquele assunto mas
  fora de `HEAD`, dizer isso — e não "nenhum commit" —, porque é a diferença entre "esqueceu de
  commitar" e "casou com a task de outro ramo".
- **`docs/task/README.md` para de prometer o que não entrega:** o número é único **dentro deste
  ramo**, e um link `[TASK-NNNN]` só identifica alguma coisa quando se sabe de que ramo se fala.
- Testes de regressão cobrindo os dois lados da fronteira.

**NÃO entra:**

- Qualquer alteração em `feature/handoff-end-to-end` — faixa, renumeração ou prefixo. É outro ramo.
- Prefixo por linha de produto (`TASK-F-`/`TASK-H-`). Mexeria no validador, no gancho, na CI e em
  todo link já escrito, para resolver um problema que só aparece se os ramos forem fundidos.
- Fundir os dois ramos, ou decidir se serão fundidos.

## Como validar

```powershell
python scripts/check_traceability.py          # continua OK
python -m pytest scripts/tests -q             # os testes novos passam
```

E o caso negativo: uma task ≥ 0016 marcada `concluída` cujo único commit com aquele assunto está
noutro ramo deve **reprovar**, dizendo exatamente isso.

## Resultado

Feito. `check_task_has_commit()` separa os dois lados da fronteira `FORK_FIRST_TASK = 16`, e a
mensagem de reprovação distingue "esqueceu de commitar" de "casou com a task de outro ramo" — que
era metade do problema original: o relato abre com o validador dizendo *"uma task = um commit, mas
ha 2 commits"*, lido como "commitada duas vezes", e não era isso.

**A medição de hoje corrigiu o relato:** colidem **nove** números, de TASK-0016 a TASK-0024, e não
dois. O relatório registrava 0016 e 0017 porque foi escrito antes de os outros existirem.

`docs/task/README.md` parou de prometer um identificador global e passou a dizer o que vale: o
número é único dentro deste ramo, e é isso que o validador prova.

O outro ramo não foi tocado, conforme a decisão. Se os dois forem fundidos algum dia, os nove pares
de arquivos homônimos voltam a ser problema e a renumeração volta à mesa.

Validação: `python -m pytest scripts/tests -q` — 30 testes, 3 novos aqui; o que cobre a colisão
entre ramos **falha** contra a versão anterior do script.
