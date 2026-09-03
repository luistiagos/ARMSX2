# Documentação — RetroSystem PS2 (ARMSX2)

Esta pasta é o registro de **o que existe, por que existe e em qual commit entrou**. Ela não é
documentação decorativa: a regra de commit do projeto depende dela.

## A regra principal

> **Nenhum commit de código sem uma task em [`docs/task/`](task/README.md) que o descreva.**
> O agente é quem commita, sempre.

Por que existe: as versões 1.0.20, 1.0.21 e 1.0.22 foram construídas, assinadas e distribuídas aos
clientes a partir de 41 arquivos que nunca entraram em nenhum commit. Sem commit não há diff; sem
diff não há revisão; sem revisão ninguém percebeu que o upstream já tinha resolvido o problema que
estávamos reescrevendo pela quarta vez. O commit `e32860b7e9` resgatou esse estado, e a regra acima
existe para que não se repita.

## Os três registros e como se ligam

```
                    FEATURE  (docs/features/FEAT-NNNN-*.md)
                    "o que estamos construindo"
                       ▲                    ▲
            pertence a │                    │ pertence a
                       │                    │
     TASK  ────────────┘                    └──────────  BUG
 (docs/task/            resolve  ────────►      (docs/bugs/)
  TASK-NNNN-*.md)       ◄────  resolvido por
 "uma unidade de
  trabalho = 1 commit"
```

Cada relação é **bidirecional e obrigatória**. Se a task diz que resolve um bug, o bug tem de citar
a task. Se a feature lista a task, a task tem de apontar de volta para a feature.

> **Obrigatória pela regra; nem toda direção é verificada hoje.** O validador reprova o link de mão
> única entre feature e task nos dois sentidos, e no sentido task→bug. **Não** verifica o sentido
> bug→task: um bug cujo campo `Tasks que o resolvem` cita uma task que não o lista de volta passa em
> silêncio. Ver [O que o validador não verifica](#o-que-o-validador-não-verifica) e a
> [FEAT-0002](features/FEAT-0002-rastreabilidade-verificavel.md).

Cardinalidades:

- Uma **task** resolve **0..N bugs**; um **bug** é resolvido por **1..N tasks**.
- Uma **feature** contém **1..N tasks**; uma task pertence a **0 ou 1 feature**.
- Uma **feature** pode originar bugs depois de implantada — esses ficam listados nela.

## Estrutura

| Pasta | O que guarda | Convenção |
|---|---|---|
| [`task/`](task/README.md) | Unidades de trabalho. Uma por commit. | `TASK-NNNN-<slug>.md` |
| [`features/`](features/README.md) | Iniciativas maiores que agrupam tasks. | `FEAT-NNNN-<slug>.md` |
| [`bugs/open/`](bugs/open/README.md) | Defeitos em investigação, separados entre fork atual e legado/version1. | `<linha>/<componente>-<sintoma>_<ISO>.md` |
| [`bugs/done/`](bugs/done/README.md) | Defeitos corrigidos **e validados**. | idem |
| `backlog/` | Ideias ainda não promovidas a feature. | livre |

Os demais arquivos soltos em `docs/` são especificações e decisões de arquitetura, sem numeração.

## Fluxo de um commit

1. **Escrever a task antes do código.** Número novo, escopo explícito (o que entra e o que **não**
   entra), critério de validação.
2. Fazer o trabalho. Se não couber num commit, **provavelmente a task era grande demais** — quebre
   em várias. Mas isto é orientação, não regra verificada: uma task pode ter mais de um commit
   ([TASK-0042](task/TASK-0042-remover-regra-um-commit-por-task.md)), e voltar a ela depois é melhor
   que emendar o commit já feito.
3. Commitar com a task no assunto: **`TASK-0007: <resumo no imperativo>`**. Esse prefixo é o
   vínculo autoritativo entre task e commit — não o hash escrito à mão.
4. Atualizar os links do outro lado (bug e feature) — no mesmo commit.
5. `python scripts/check_traceability.py` e só então `git push`.

> **Instale o gancho uma vez por worktree** — `.git/hooks/` não é versionado, então um gancho que
> só existe numa máquina não é processo:
>
> ```powershell
> git config core.hooksPath scripts/hooks
> ```
>
> Ele roda a checagem sobre o range que está sendo empurrado e barra o push que quebra a regra,
> dizendo **o que fazer**. `git push --no-verify` o contorna de propósito: a barreira que ninguém
> contorna é a CI (`.github/workflows/rastreabilidade.yml`), que roda a mesma checagem.

> **Por que não gravamos o hash dentro da própria task:** é circular. O hash só existe depois do
> commit, e `git commit --amend` para inseri-lo gera um hash novo — o campo fica apontando para um
> commit órfão que ainda responde a `git cat-file` e, portanto, mente sem ser detectado. Isso
> aconteceu de fato ao criar a TASK-0001. O vínculo real é o prefixo `TASK-NNNN:` no assunto, que o
> git resolve com `git log --grep`. O hash aparece no índice de `docs/task/README.md`, preenchido
> depois por `python scripts/check_traceability.py --fix` e commitado como `chore:`.

## Exceção: `chore`

Trabalho que **não roda no aplicativo** — README, formatação, `.gitignore`, correção de texto —
pode ir sem task, com o assunto prefixado por `chore:`. A exceção existe para a regra continuar
crível; ela **não** cobre nada em `app/src/`, `scripts/` ou arquivos de build. Na dúvida, crie a task.

## Validação

```powershell
python scripts/check_traceability.py                            # estrutura dos registros
python scripts/check_traceability.py --fix                      # completa o índice de tasks
python scripts/check_traceability.py --commits upstream/master..HEAD   # git → task
python -m pytest scripts/tests -q                               # regressão do próprio validador
```

Rodar antes de todo push — ou instalar o gancho, que faz isso sozinho.

**Os dois sentidos são checagens diferentes.** Sem `--commits`, o validador só caminha de arquivo
de task para o git: ele vê a task que mente, não o commit que não deveria existir. `--commits`
parte do `git log`, que é o sentido do incidente que criou este processo.

### O que ele verifica

- Campo obrigatório ausente (`Criada em`, `Feature`, `Bugs que resolve`, `Commit`).
- Status fora do vocabulário, e `revertida` sem **Revertida por**.
- Task `concluída` sem nenhum commit alcançável com o assunto `TASK-NNNN:`.
- Hash escrito à mão que não é **ancestral de `HEAD`**. Existir no banco de objetos não basta: um
  commit órfão de `--amend` ainda responde a `git cat-file` e mentiria sobre o histórico.
- Link feature↔task de mão única, nos dois sentidos; link task→bug **e** bug→task de mão única.
- Status divergente entre a tabela da feature e o arquivo da task.
- Task `concluída` cujo commit já foi publicado e que ficou com **Publicado em** vazio.
- Task `concluída` **fora do índice** de `docs/task/README.md`.
- Bug em `done/` sem task declarada no campo **Tasks que o resolvem** (menção em prosa não conta).

Com `--commits <range>`, mais três, agora partindo do git:

- Assunto `TASK-NNNN:` **sem** `docs/task/TASK-NNNN-*.md` — a task nunca foi escrita.
- `chore:` alterando `platforms/android/app/src/`, `pcsx2/`, `common/`, `scripts/` ou arquivo de
  build. A lista de caminhos mora num só lugar, em `GUARDED_PREFIXES`/`GUARDED_SUFFIXES`.
- Assunto que não é `TASK-NNNN:` nem `chore:`. Merges ficam de fora: um `git merge upstream/master`
  traz commits de terceiros, cujo assunto não é nosso para governar.

### O que o validador NÃO verifica

Registrado aqui porque um processo que promete mais do que entrega é pior que nenhum: ele produz
confiança sem lastro. Fechar esta lista é a [FEAT-0002](features/FEAT-0002-rastreabilidade-verificavel.md).

| Não verifica | Consequência observada |
|---|---|
| **Se a task descreve honestamente o que o commit fez.** | Nenhum script alcança isso — é revisão humana, e continuará sendo. |
| **Quantos commits uma task tem.** Deixou de ser regra na [TASK-0042](task/TASK-0042-remover-regra-um-commit-por-task.md). | Uma task pode espalhar-se por vários commits sem ninguém notar. Trocado de propósito: exigir um só empurrava para `--amend`, que reescreve o histórico — o estrago que a checagem de hash órfão existe para pegar. |

### O que ele passou a verificar, e não verificava

Oito linhas saíram da tabela acima em 2026-09-03. Ficam registradas porque a lista de buracos só
tem valor se der para ver quando cada um foi tapado.

| Era buraco | Fechado por |
|---|---|
| O sentido **git → task**: nada partia do `git log`. | [TASK-0011](task/TASK-0011-impor-regra-de-commit-mecanicamente.md) — modo `--commits`, gancho e CI |
| A exceção `chore` por caminho; não havia gancho. | [TASK-0011](task/TASK-0011-impor-regra-de-commit-mecanicamente.md) |
| O vínculo task→commit casava o **corpo** do commit. | [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) |
| `--fix` só substituía linha existente, e anunciava sucesso sem inserir. | [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) |
| Status cruzado feature↔task. | [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) |
| O campo `Publicado em`. | [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) |
| O sentido bug → task. | [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) |
| `done/` aceitava a substring `"TASK-"` em prosa qualquer. | [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) |

Os três bugs correspondentes estão em [`bugs/done/`](bugs/done/README.md), com o teste de regressão
que prova cada um: `python -m pytest scripts/tests -q`, 27 testes, cada um montando um repositório
git de verdade.
