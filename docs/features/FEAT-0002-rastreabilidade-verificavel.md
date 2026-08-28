# FEAT-0002: Tornar a rastreabilidade verificável, não declarativa

- **Status:** planejada
- **Criada em:** 2026-08-25
- **Concluída em:** —

## Objetivo

Fazer com que a regra de commit do projeto seja **verificada por máquina** em vez de sustentada pela
memória de quem commita. Ao fim desta feature, quebrar a rastreabilidade deve custar um erro visível,
não passar em silêncio.

## Justificativa

A [FEAT-0001](FEAT-0001-sync-upstream-oficial.md) trouxe o sistema de rastreabilidade — na primeira
task de processo do projeto — depois de 1.0.20–1.0.22 terem sido distribuídas a clientes a partir de
41 arquivos nunca commitados. O desenho ficou correto — o vínculo
task↔commit pelo assunto em vez de hash escrito à mão, e a exigência de ancestralidade de `HEAD` em
vez de simples `cat-file`, são as duas decisões difíceis e ambas estão certas.

A revisão de 2026-08-25 mostrou que **a implementação não cobre o desenho**, e o registro em
[`docs/README.md`](../README.md) afirmava garantias que o validador não dá:

- O vínculo task→commit resolve pelo **corpo** do commit, não pelo assunto.
- O índice de tasks, designado como o lugar onde o hash mora, tinha **1 linha para 9 tasks**, e o
  `--fix` relatava sucesso.
- Nada percorre o sentido **git → task**, que é justamente o sentido do incidente fundador.
- Status e campo `Publicado em` não são conferidos: a FEAT-0001 listava como `aberta` a task de
  publicação **depois** de ela ter ido ao ar, e cinco tasks que foram na 1.0.23 seguiam com
  `Publicado em: —`.

Um processo que aprova o que deveria reprovar é pior que nenhum processo, porque produz confiança
sem lastro.

## Bugs que motivaram

- [checktraceability-grep-casa-corpo-do-commit](../bugs/open/checktraceability-grep-casa-corpo-do-commit_2026-08-25T22-44.md) — o vínculo task→commit casa menção no corpo do commit
- [checktraceability-fix-nao-insere-task-ausente-do-indice](../bugs/open/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md) — `--fix` não insere linha nova e relata sucesso
- [rastreabilidade-sem-verificacao-de-git-para-task](../bugs/open/rastreabilidade-sem-verificacao-de-git-para-task_2026-08-25T22-44.md) — nada valida o sentido git → task; não há gancho

> **Por que esta feature evita citar números de task na prosa:** o validador varre o texto inteiro
> com `TASK_ID_RE.findall(text)` e exige backlink de **qualquer** task mencionada, inclusive numa
> frase explicativa ou dentro de uma URL. Citar aqui a task que criou o sistema fazia o validador
> reprovar exigindo que ela apontasse de volta para esta feature, à qual não pertence. É o defeito
> da varredura por prosa, corrigido pela TASK-0010 abaixo — até lá, a restrição vale para toda
> feature nova.

## Tasks

| Task | Status | Descrição |
|---|---|---|
| [TASK-0010](../task/TASK-0010-corrigir-validador-rastreabilidade.md) | aberta | Corrigir o validador: assunto em vez de corpo, `--fix` que insere, conferência de status e de `Publicado em` |
| [TASK-0011](../task/TASK-0011-impor-regra-de-commit-mecanicamente.md) | aberta | Impor a regra mecanicamente: modo `--commits`, regra de caminho para `chore:`, gancho versionado e CI |
| [TASK-0042](../task/TASK-0042-remover-regra-um-commit-por-task.md) | concluída | Sai a regra "uma task = um commit"; `--fix` passa a gravar todos os hashes de uma task |

## Bugs originados por esta feature

Nenhum até agora.

## Critérios de conclusão

1. Um commit `TASK-NNNN:` sem arquivo de task **reprova**, e a mensagem diz qual arquivo falta.
2. Um commit `chore:` que toque `app/src/`, `scripts/` ou arquivos de build **reprova**.
3. Uma menção a `TASK-NNNN:` no corpo de um commit `chore:` **não** é contada como o commit da task —
   coberto por teste de regressão.
4. `--fix` insere a linha da task ausente do índice e distingue `inseridas` de `nada a fazer`.
5. Status divergente entre feature e task **reprova**.
6. Task `concluída` cujo commit já foi publicado sem `Publicado em` preenchido **reprova**.
7. A verificação roda na CI, não só localmente — um gancho local é contornável com `--no-verify`.
