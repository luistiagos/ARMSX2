# Features

Uma feature é uma iniciativa grande demais para um commit: ela agrupa **as tasks que a constroem** e
**os bugs que ela originou** depois de implantada. É o registro que responde "por que este código
existe" meses depois.

Ver [`docs/README.md`](../README.md) para a regra de commit e o desenho geral da rastreabilidade.

## Convenção de nome

`FEAT-NNNN-<slug-kebab-case>.md` — número sequencial, nunca reaproveitado.

Arquivos legados sem numeração (ex.: `telemetria.md`) continuam válidos; ganham número quando forem
tocados.

## Status

| Status | Significa |
|---|---|
| `planejada` | Escrita, nenhuma task começada. |
| `em andamento` | Ao menos uma task concluída, ainda faltam outras. |
| `concluída` | Todas as tasks concluídas e critérios de conclusão atendidos. |
| `abandonada` | Encerrada sem completar. O motivo fica registrado. |

## Template

```markdown
# FEAT-NNNN: <título>

- **Status:** planejada
- **Criada em:** YYYY-MM-DD
- **Concluída em:** —

## Objetivo

O que muda para o usuário ou para a manutenção do projeto.

## Justificativa

Por que vale o custo. Se a feature nasceu de bugs, citá-los aqui.

## Bugs que motivaram

- [<slug>](../bugs/open/<arquivo>.md) — resumo de uma linha

## Tasks

| Task | Status | Descrição |
|---|---|---|
| [TASK-NNNN](../task/TASK-NNNN-<slug>.md) | aberta | ... |

## Bugs originados por esta feature

Preenchido depois da implantação. Vazio é o resultado desejado.

## Critérios de conclusão

Lista objetiva. A feature só fecha quando todos forem verificáveis.
```

## Índice

| Feature | Status | Tasks | Descrição |
|---|---|---|---|
| [FEAT-0001](FEAT-0001-sync-upstream-oficial.md) | em andamento | 7 de 9 | Trazer as correções, o sistema de perfil de GPU e as melhorias do `ARMSX2/ARMSX2` oficial |
| [FEAT-0002](FEAT-0002-rastreabilidade-verificavel.md) | planejada | 0 de 2 | Tornar a rastreabilidade verificável por máquina, em vez de declarativa |
| [`telemetria.md`](telemetria.md) | concluída | — | Reporte de erros de produção (legado, sem numeração) |
