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

## Correção — 2026-09-03 ([TASK-0011](../../task/TASK-0011-impor-regra-de-commit-mecanicamente.md))

Os quatro próximos passos entraram, e o repositório atual passa nos quatro.

1. **Modo `--commits <range>`** em `scripts/check_traceability.py`. Parte do `git log` e reprova
   três coisas: assunto `TASK-NNNN:` sem `docs/task/TASK-NNNN-*.md`; `chore:` alterando caminho que
   a exceção não cobre; e assunto que não é nem um nem outro. Merges ficam de fora — um
   `git merge upstream/master` traz commits de terceiros, cujo assunto não é nosso para governar,
   e sem essa exclusão o merge da TASK-0067 reprovaria 72 vezes.
2. **Regra de caminho num só lugar:** `GUARDED_PREFIXES` e `GUARDED_SUFFIXES` no topo do script.
   Cobre `platforms/android/app/src/`, `pcsx2/`, `common/`, `scripts/` e os arquivos de build
   (`*.gradle`, `*.gradle.kts`, `gradle.properties`, `libs.versions.toml`, `CMakeLists.txt`,
   `*.cmake`).
3. **Gancho versionado** em [`scripts/hooks/pre-push`](../../../scripts/hooks/pre-push), instalado
   com `git config core.hooksPath scripts/hooks`. Ele valida o range que está sendo empurrado e,
   quando barra, imprime **o que fazer** para cada tipo de falha — não só que falhou.
4. **A mesma checagem na CI**, em
   [`.github/workflows/rastreabilidade.yml`](../../../.github/workflows/rastreabilidade.yml).
   É a barreira que `--no-verify` não contorna. O `fetch-depth: 0` ali não é detalhe: com o clone
   raso padrão não haveria histórico para percorrer, e a checagem passaria sem ter olhado nada.

### O que o histórico atual revelou

```
python scripts/check_traceability.py --commits upstream/master..HEAD
OK -- 77 task(s), 2 feature(s), 111 commit(s) em upstream/master..HEAD, rastreabilidade consistente.
```

111 commits nossos, e **um** fora da regra: `bf45520833`, assunto `*`, 114 arquivos — entre eles
`scripts/check_cover_coverage.py` e testes sob `app/src/`. É exatamente o que esta checagem passa a
barrar, e já estava publicado quando ela foi escrita. Reescrever histórico publicado é o mesmo
estrago que `commit_is_reachable` existe para detectar, então ele entrou como exceção **nominal**
em `LEGACY_SUBJECT_EXCEPTIONS`, com o motivo escrito ao lado — em vez de enfraquecer a regra para
todos.

Nenhum `chore:` do histórico toca caminho protegido. Verificado antes de ligar a regra.

### O que NÃO entrou, e por quê

O escopo original da task pedia reprovar "dois commits com o mesmo prefixo `TASK-NNNN:`". Isso foi
escrito antes da [TASK-0042](../../task/TASK-0042-remover-regra-um-commit-por-task.md), que
**removeu** a regra "uma task = um commit" de propósito: exigir um só empurrava para `--amend`, que
reescreve o histórico. Reintroduzi-la aqui desfaria uma decisão tomada. Não entrou.

### Validação

`python -m pytest scripts/tests -q` — 27 testes. Os nove novos cobrem os três casos negativos do
plano da task (commit de task inexistente, `chore:` tocando `app/src/`, assunto fora do
vocabulário), mais arquivo de build, merge ignorado, range inválido, e a garantia de que sem
`--commits` o comportamento não muda.

Um deles nasceu de um defeito real encontrado pelos próprios testes: `main()` retornava 0 logo no
início quando não havia **nenhum** arquivo de task — e um repositório sem tasks, com commits
alterando `app/src/`, é literalmente o incidente fundador. A saída antecipada agora só acontece
quando `--commits` não foi pedido.
