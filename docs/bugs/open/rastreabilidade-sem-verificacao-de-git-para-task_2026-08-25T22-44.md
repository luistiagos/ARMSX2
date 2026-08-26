# Bug: a regra de commit não tem verificação nenhuma no sentido git → task

- **Detectado em:** 2026-08-25 22:44 (revisão do processo)
- **Origem:** `scripts/check_traceability.py::main` + ausência de gancho em `.git/hooks/`
- **Errors (serviço):** nenhum — falha de processo, não do aplicativo
- **Classe:** fail
- **Reincidência:** primeira vez
- **Feature:** [FEAT-0002](../../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Tasks que o resolvem:** [TASK-0011](../../task/TASK-0011-impor-regra-de-commit-mecanicamente.md)

## Sintoma

O validador percorre **arquivos de task → git**. Não existe nenhum caminho que percorra **git →
arquivos de task**. Consequência direta: um commit que quebra a regra principal do projeto é
invisível para a ferramenta que existe para defendê-la.

Dois commits passam sem qualquer aviso:

1. `TASK-0042: faz alguma coisa` sem `docs/task/TASK-0042-*.md` — a task nunca foi escrita.
2. `chore: ajuste rápido` alterando `app/src/`, `scripts/` ou arquivos de build — exatamente o que a
   [exceção `chore`](../../README.md) proíbe.

## Causa raiz

Duas ausências, não um defeito de código:

- **`main()` não enumera commits.** Ele monta os dicionários `tasks` e `feats` a partir do sistema de
  arquivos e valida cada um contra o git. Nenhum laço parte de `git log`.
- **Não existe gancho.** `.git/hooks/` não tem um único arquivo fora dos `.sample` (verificado em
  2026-08-25). A regra depende inteiramente de quem commita lembrar dela, e o `python
  scripts/check_traceability.py` do passo 5 do fluxo é manual.

## Como reproduzir

```sh
ls -la .git/hooks/ | grep -v '\.sample'    # vazio
git commit -m "TASK-9999: task que nao existe" --allow-empty
python scripts/check_traceability.py       # OK -- rastreabilidade consistente
```

## Impacto

**Este é o buraco que o processo foi criado para tapar.** O incidente fundador — 1.0.20, 1.0.21 e
1.0.22 construídas, assinadas e distribuídas a partir de 41 arquivos que nunca entraram em commit
nenhum — não teria sido detectado por este validador, porque nada nele olha para o que foi
efetivamente commitado, nem para o que deixou de ser.

Hoje o repositório está limpo: verificado em 2026-08-25 que **todo** commit com assunto
`TASK-NNNN:` tem o arquivo de task correspondente, e não há órfãos. O defeito é que nada mantém
isso verdadeiro.

## Próximos passos

Ver [TASK-0011](../../task/TASK-0011-impor-regra-de-commit-mecanicamente.md). Em resumo:

1. Modo `--commits <range>` no validador, que parte do `git log` e reprova assunto `TASK-NNNN:` sem
   arquivo de task.
2. Regra de caminho: commit com assunto `chore:` que toque `app/src/`, `scripts/` ou arquivos de
   build é reprovado, com a mensagem apontando para a exceção em `docs/README.md`.
3. Um gancho versionado no repositório (`scripts/hooks/`) + instrução de instalação, porque
   `.git/hooks/` não é versionado e um gancho que só existe na máquina de uma pessoa não é processo.
4. A mesma checagem na CI, que é a única barreira que ninguém consegue pular com `--no-verify`.
