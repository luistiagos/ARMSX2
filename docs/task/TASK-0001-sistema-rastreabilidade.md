# TASK-0001: Estabelecer o sistema de rastreabilidade feature / task / bug

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-24
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum (task de processo)
- **Commit:** assunto `TASK-0001:` — hash resolvido do git, ver índice em [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** — (não altera o aplicativo)

## Objetivo

Criar o registro que amarra **feature → task → commit → bug**, com links obrigatórios nos dois
sentidos, e a regra de que nenhum commit de código entra sem uma task que o descreva.

## Motivação

As versões 1.0.20, 1.0.21 e 1.0.22 foram construídas, assinadas e distribuídas a clientes a partir
de 41 arquivos que nunca entraram em nenhum commit — o último commit da linha era `c9f1e4ff42`, de
21/08. Sem commit não há diff, sem diff não há revisão, e sem revisão ninguém comparou o que
estávamos escrevendo com o que o upstream já tinha pronto no próprio repositório desde 18/08.

Também é o que explica um relato de campo com versão errada: sem amarrar binário publicado a estado
de código, um A07 reportando 1.0.16 enquanto a distribuição estava em 1.0.19 é indistinguível de um
bug real.

## Escopo

**Entra:**

- `docs/README.md` — a regra de commit, o desenho das três relações e o fluxo passo a passo.
- `docs/task/README.md` — convenção `TASK-NNNN-<slug>`, tabela de status, template e índice.
- `docs/features/README.md` — convenção `FEAT-NNNN-<slug>`, status, template e índice.
- `docs/features/FEAT-0001-sync-upstream-oficial.md` — a feature guarda-chuva do port do upstream.
- `docs/task/TASK-0001-...` — esta task.
- `scripts/check_traceability.py` — validador que reprova link só de um lado, campo faltando, task
  concluída sem commit e hash inexistente.
- Seção nova em `CLAUDE.md` para que outros agentes sigam o mesmo padrão.
- Campos **Feature** e **Tasks** adicionados ao template de bug em `docs/bugs/open/README.md`.

**NÃO entra:**

- Retroagir tasks para o histórico já commitado. O commit `e32860b7e9` é explicitamente o último
  estado sem task; a regra vale a partir daqui.
- Automação de CI. O validador roda à mão antes do push; virar hook é decisão posterior.
- Qualquer alteração em código que roda no aplicativo.

## Como validar

```powershell
python scripts/check_traceability.py     # deve terminar com "OK"
```

E, por inspeção: `FEAT-0001` lista `TASK-0001`; `TASK-0001` aponta de volta para `FEAT-0001`; os três
bugs gráficos citados pela feature existem em `docs/bugs/open/`.

## Resultado

Estrutura criada e validador passando. A partir do próximo commit, todo trabalho em `app/src/`,
`scripts/` ou arquivos de build exige uma task; documentação e formatação podem ir como `chore:`.

Ficou deliberadamente de fora um ponto que o validador **não** consegue checar: se a task descreve
honestamente o que o commit fez. Isso continua dependendo de quem escreve.
