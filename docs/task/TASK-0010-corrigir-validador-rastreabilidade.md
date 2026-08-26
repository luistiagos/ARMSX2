# TASK-0010: Corrigir o validador de rastreabilidade para verificar o que ele afirma verificar

- **Status:** aberta
- **Criada em:** 2026-08-25
- **Concluída em:** —
- **Feature:** [FEAT-0002](../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Bugs que resolve:** [checktraceability-grep-casa-corpo-do-commit](../bugs/open/checktraceability-grep-casa-corpo-do-commit_2026-08-25T22-44.md), [checktraceability-fix-nao-insere-task-ausente-do-indice](../bugs/open/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0010:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (não altera o aplicativo)

## Objetivo

Fechar a distância entre o que `scripts/check_traceability.py` promete e o que ele faz. Hoje ele
aprova casos que deveria reprovar e reprovaria casos corretos assim que um commit `chore:` citar uma
task numa linha do corpo.

## Escopo

**Entra:**

- **`commits_for_task()` passa a filtrar pelo assunto.** Trocar `--grep=^TASK-NNNN:` por leitura de
  `%h%x00%s` e comparação `subject.startswith(tid + ":")` em Python. É a correção do
  [bug do `--grep`](../bugs/open/checktraceability-grep-casa-corpo-do-commit_2026-08-25T22-44.md).
- **`fill_index()` passa a inserir**, não só substituir. Quando a task concluída não tem linha no
  índice, montar a linha a partir dos campos da própria task. Distinguir as saídas `inseridas N`,
  `atualizadas N` e `nada a fazer`, e parar de assumir que `Commit` é a última coluna.
- **Conferência de status cruzado feature↔task.** Se a feature lista a task com um status diferente
  do que a task declara, reprovar. Caso real: a FEAT-0001 listou a TASK-0009 como `aberta` depois de
  publicada.
- **Conferência de `Publicado em`.** Task `concluída` cujo commit é ancestral do commit de uma task
  de publicação, e ainda assim com `Publicado em: —`, reprova. Cinco tasks ficaram assim depois da
  1.0.23.
- **Link bug→task bidirecional de fato.** Hoje só `task→bug` é verificado. Ler o campo
  `**Tasks que o resolvem:**` do bug e exigir que a task o liste em `**Bugs que resolve:**`.
- **Bug em `done/` passa a exigir o campo declarado**, não a substring `"TASK-"` em qualquer lugar do
  texto — uma menção em prosa (*"hipótese eliminada pela TASK-0008"*) satisfaz o check atual.
- **Varredura de feature restrita à tabela de tasks.** `TASK_ID_RE.findall(text)` hoje varre a prosa
  inteira; uma frase sobre trabalho futuro citando uma task ainda não escrita faz o validador exigir
  o arquivo.
- **Testes de regressão** em `scripts/tests/`, cobrindo no mínimo: menção no corpo não conta como
  commit da task; `--fix` insere linha ausente; status divergente reprova; link de mão única
  reprova.

**NÃO entra:**

- Qualquer imposição no ato do commit — gancho, regra de caminho para `chore:`, modo `--commits`,
  CI. Tudo isso é a [TASK-0011](TASK-0011-impor-regra-de-commit-mecanicamente.md), porque é mecanismo
  novo e não cabe no mesmo commit.
- Reescrever o histórico para consertar os dois `chore:` que anunciaram gravar um hash e só mexeram
  em espaço em branco. Ficam registrados no bug; o índice já foi corrigido por um `chore:` posterior.

## Como validar

```powershell
python scripts/check_traceability.py          # continua OK no repositório atual
python -m pytest scripts/tests -q             # os testes de regressão passam
```

E os quatro casos negativos, que devem **reprovar** depois desta task e passam antes dela:

1. Commit `chore:` com `TASK-0099:` numa linha do corpo, e a TASK-0099 marcada `concluída` sem commit
   próprio.
2. Task concluída ausente do índice de `docs/task/README.md`.
3. Feature listando uma task com status diferente do declarado na task.
4. Bug cujo campo `Tasks que o resolvem` cita uma task que não o lista de volta.

## Resultado

Preenchido ao concluir.
