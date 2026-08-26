# Documentação — RetroSystem PS2 (ARMSX2)

Esta pasta é o registro de **o que existe, por que existe e em qual commit entrou**. Ela não é
documentação decorativa: a regra de commit do projeto depende dela.

## A regra principal

> **Nenhum commit de código sem uma task em [`docs/task/`](task/README.md) que o descreva.**
> **Uma task = um commit.** O agente é quem commita, sempre.

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
| [`bugs/open/`](bugs/open/README.md) | Defeitos em investigação ou aguardando reteste. | `<componente>-<sintoma>_<ISO>.md` |
| [`bugs/done/`](bugs/done/README.md) | Defeitos corrigidos **e validados**. | idem |
| `backlog/` | Ideias ainda não promovidas a feature. | livre |

Os demais arquivos soltos em `docs/` são especificações e decisões de arquitetura, sem numeração.

## Fluxo de um commit

1. **Escrever a task antes do código.** Número novo, escopo explícito (o que entra e o que **não**
   entra), critério de validação.
2. Fazer o trabalho. Se não couber num commit, **a task era grande demais** — quebre em várias.
3. Commitar com a task no assunto: **`TASK-0007: <resumo no imperativo>`**. Esse prefixo é o
   vínculo autoritativo entre task e commit — não o hash escrito à mão.
4. Atualizar os links do outro lado (bug e feature) — no mesmo commit.
5. `python scripts/check_traceability.py` e só então `git push`.

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
python scripts/check_traceability.py
```

Rodar antes de todo push.

### O que ele verifica

- Campo obrigatório ausente (`Criada em`, `Feature`, `Bugs que resolve`, `Commit`).
- Status fora do vocabulário, e `revertida` sem **Revertida por**.
- Task `concluída` sem nenhum commit alcançável com o assunto `TASK-NNNN:`.
- Mais de um commit com o mesmo prefixo `TASK-NNNN:` — a regra "uma task = um commit".
- Hash escrito à mão que não é **ancestral de `HEAD`**. Existir no banco de objetos não basta: um
  commit órfão de `--amend` ainda responde a `git cat-file` e mentiria sobre o histórico.
- Link feature↔task de mão única, nos dois sentidos; link task→bug de mão única.
- Bug em `done/` sem nenhuma menção a uma task.

### O que o validador NÃO verifica

Registrado aqui porque um processo que promete mais do que entrega é pior que nenhum: ele produz
confiança sem lastro. Fechar esta lista é a [FEAT-0002](features/FEAT-0002-rastreabilidade-verificavel.md).

| Não verifica | Consequência observada |
|---|---|
| **Se a task descreve honestamente o que o commit fez.** | Nenhum script alcança isso — é revisão humana, e continuará sendo. |
| **O sentido git → task.** Nada parte do `git log`. | Um commit `TASK-NNNN:` sem arquivo de task, ou um `chore:` que altera `app/src/`, é invisível. É o sentido do incidente que criou este processo. |
| **A exceção `chore` por caminho.** Não há gancho em `.git/hooks/`. | A regra depende de quem commita lembrar dela. |
| **O vínculo task→commit pelo assunto.** O `--grep` casa o **corpo** do commit. | Uma menção `TASK-NNNN:` numa linha do corpo de um `chore:` conta como se fosse o commit da task. |
| **Se `--fix` fez o que diz.** Ele só substitui linha existente. | O índice ficou com 1 linha para 9 tasks enquanto dois `chore:` anunciavam ter gravado hashes. |
| **Status cruzado feature↔task.** | A FEAT-0001 listou a TASK-0009 como `aberta` depois de publicada. |
| **O campo `Publicado em`.** Não é obrigatório nem conferido. | Cinco tasks que foram ao ar na 1.0.23 seguiram com `—`. |
| **O sentido bug → task.** | Um bug que declara ser resolvido por uma task que não o lista passa. |
| **Declaração vs. prosa.** O check de `done/` é a substring `"TASK-"`. | Um bug fechado cuja única menção é *"hipótese eliminada pela TASK-0008"* satisfaz a exigência. |

Os três defeitos de implementação estão registrados como bugs em
[`bugs/open/`](bugs/open/README.md) e planejados nas
[TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md) e
[TASK-0011](task/TASK-0011-impor-regra-de-commit-mecanicamente.md).
