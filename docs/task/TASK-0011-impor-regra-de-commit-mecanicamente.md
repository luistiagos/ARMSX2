# TASK-0011: Impor a regra de commit mecanicamente, no gancho e na CI

- **Status:** aberta
- **Criada em:** 2026-08-25
- **Concluída em:** —
- **Feature:** [FEAT-0002](../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Bugs que resolve:** [rastreabilidade-sem-verificacao-de-git-para-task](../bugs/open/rastreabilidade-sem-verificacao-de-git-para-task_2026-08-25T22-44.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0011:` no assunto)
- **Revertida por:** —
- **Publicado em:** — (não altera o aplicativo)

## Objetivo

Fazer o sentido **git → task** existir. Hoje o validador só caminha de arquivo de task para o git, e
por isso não enxerga nem um commit `TASK-NNNN:` sem task escrita, nem um `chore:` que altera
`app/src/` — que é literalmente o incidente que criou o processo.

## Motivação

O processo foi criado depois de 1.0.20–1.0.22 terem ido a clientes a partir de 41 arquivos nunca
commitados. A ferramenta escrita para impedir a repetição **não detectaria aquele incidente**, porque
nada nela parte do que o git contém. Enquanto a regra depender de quem commita lembrar dela, ela é
uma convenção, não uma garantia.

## Escopo

**Entra:**

- **Modo `--commits <range>`** em `scripts/check_traceability.py`, que parte do `git log` e reprova:
  - assunto `TASK-NNNN:` sem `docs/task/TASK-NNNN-*.md` correspondente;
  - dois commits com o mesmo prefixo `TASK-NNNN:` (a regra "uma task = um commit", verificada do lado
    do git);
  - assunto que não é `TASK-NNNN:` nem `chore:` nem um prefixo permitido explicitamente.
- **Regra de caminho para `chore:`.** Commit `chore:` que toque `app/src/`, `scripts/` ou arquivos de
  build reprova, com mensagem citando a exceção em [`docs/README.md`](../README.md). A lista de
  caminhos fica num único lugar no script, não espalhada.
- **Gancho versionado** em `scripts/hooks/pre-push` (ou `commit-msg`), com instrução de instalação no
  `docs/README.md`. `.git/hooks/` não é versionado — um gancho que só existe numa máquina não é
  processo.
- **A mesma checagem na CI**, sobre o range do push/PR. É a única barreira que `--no-verify` não
  contorna, e por isso é a que vale.
- Registro no `CLAUDE.md` de que a regra passou a ser verificada, e como rodar a checagem sobre um
  range.

**NÃO entra:**

- As correções internas do validador (assunto em vez de corpo, `--fix` que insere, status cruzado).
  São a [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md) e devem entrar **antes**: impor
  mecanicamente um validador que ainda dá falso positivo transforma um erro silencioso num bloqueio
  ruidoso e errado.
- Retroagir sobre o histórico anterior a `e32860b7e9`, que é explicitamente o último estado sem task.
  O range verificado começa depois dele.

## Dependência

Depende da [TASK-0010](TASK-0010-corrigir-validador-rastreabilidade.md). A ordem importa: enquanto
`commits_for_task()` casar o corpo do commit, um gancho que reprova produz falso negativo em fluxo
normal — o commit real mais um `chore:` que o cite contam como dois — e a primeira reação de quem for
bloqueado será desligar o gancho.

## Como validar

```powershell
python scripts/check_traceability.py --commits e32860b7e9..HEAD   # o historico atual passa
```

E os três casos negativos, que devem reprovar:

1. `git commit --allow-empty -m "TASK-9999: task que nao existe"`
2. Commit `chore:` tocando qualquer arquivo sob `app/src/`.
3. Dois commits com assunto começando por `TASK-0010:`.

Depois, com o gancho instalado, repetir 1 e 2 e confirmar que o push é barrado — e que a mensagem
diz **o que fazer**, não só que falhou.

## Resultado

Preenchido ao concluir.
