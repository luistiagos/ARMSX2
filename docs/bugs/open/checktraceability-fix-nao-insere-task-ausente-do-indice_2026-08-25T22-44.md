# Bug: `--fix` não insere no índice a task que ainda não tem linha, e relata sucesso

- **Detectado em:** 2026-08-25 22:44 (revisão do processo, confirmado no histórico do git)
- **Origem:** `scripts/check_traceability.py::fill_index`
- **Errors (serviço):** nenhum — ferramenta de desenvolvimento, não roda no aplicativo
- **Classe:** fail
- **Reincidência:** primeira vez
- **Feature:** [FEAT-0002](../../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Tasks que o resolvem:** [TASK-0010](../../task/TASK-0010-corrigir-validador-rastreabilidade.md)

## Sintoma

`python scripts/check_traceability.py --fix` imprime `--fix: indice ja estava atualizado.` e sai com
código 0, enquanto o índice de [`docs/task/README.md`](../../task/README.md) segue **sem a
linha da task**. Dois commits do projeto — `bbdcd47ac0` ("indice de tasks com o hash da TASK-0008") e
`3f4e0f4898` ("indice de tasks com o hash da TASK-0009") — anunciam no assunto ter gravado um hash e
mudaram **apenas espaço em branco** numa linha de outra task:

```diff
-| [TASK-0001](...) — sistema de rastreabilidade ... | concluída | — | — |       `c572dc095b` |
+| [TASK-0001](...) — sistema de rastreabilidade ... | concluída | — | — |        `c572dc095b` |
```

Antes desta correção o índice tinha **1 linha para 9 tasks**.

## Causa raiz

[`check_traceability.py:210`](../../../scripts/check_traceability.py#L210):

```python
pattern = re.compile(r"^(\|\s*\[" + tid + r"\].*\|\s*)([^|]*)(\|\s*)$", re.MULTILINE)
```

`fill_index()` só sabe **substituir** a célula de uma linha existente. Quando a task não tem linha no
índice, `subn` devolve `n = 0`, o laço segue, e no fim `changed == 0` produz a mensagem
`indice ja estava atualizado` — indistinguível do caso legítimo em que tudo já estava correto.

Dois efeitos compostos:

- O índice, que [`docs/README.md`](../../README.md) designa como **o lugar onde o hash mora**, é o
  único registro que envelhece sem ninguém perceber.
- O `chore:` que roda o `--fix` mente no próprio assunto, porque nada confere se ele fez o que diz.

## Como reproduzir

```sh
git show --stat 3f4e0f4898          # 1 arquivo, 1 insercao, 1 delecao
git show 3f4e0f4898 -- docs/task/README.md   # a mudanca e um espaco
grep -c '^| \[TASK-' docs/task/README.md     # antes da correcao: 1
```

## Impacto

Baixo para o produto, alto para a confiança no processo: o registro que responde *"em qual commit
esta task entrou"* estava vazio para 8 das 9 tasks, e o validador aprovava. Combinado com o
[bug do `--grep`](checktraceability-grep-casa-corpo-do-commit_2026-08-25T22-44.md), o vínculo
task↔commit não tinha nenhuma verificação confiável de ponta a ponta.

## Próximos passos

1. `fill_index()` deve **inserir** a linha quando ela não existe, montando as células a partir dos
   campos da própria task (status, feature, bugs).
2. Distinguir as três saídas: `inseridas N`, `atualizadas N`, `nada a fazer`.
3. Sair com código ≠ 0 no modo `--check` quando houver task concluída fora do índice, para que o
   gancho da [TASK-0011](../../task/TASK-0011-impor-regra-de-commit-mecanicamente.md) possa barrar.
4. Não depender de a coluna `Commit` ser a última — hoje é, e o regex assume isso em silêncio.

Ver [TASK-0010](../../task/TASK-0010-corrigir-validador-rastreabilidade.md).

## Reincidência — 2026-08-26

Reproduzido de novo, sem esforço: depois de concluir as TASK-0012, 0013 e 0014, um
`python scripts/check_traceability.py --fix` respondeu

```
OK -- 14 task(s), 2 feature(s), rastreabilidade consistente.
--fix: 9 linha(s) do indice atualizada(s) com o hash real.
```

e o índice continuou com **11 linhas para 14 tasks**. As três novas não foram inseridas. O índice
teve de ser completado à mão.

O relato acrescenta dois defeitos que não estavam registrados:

1. **A coluna `Status` não é atualizada.** As TASK-0004 e TASK-0005 já estavam `concluída` nos
   próprios arquivos e o `--fix` gravou o hash **mantendo `aberta` no índice**. Ou seja, ele produz
   uma linha internamente contraditória — com commit e "aberta" ao mesmo tempo — e o validador
   aprova, porque não compara o status do arquivo com o do índice. É o mesmo buraco que a
   `docs/README.md` já lista como *"Status cruzado feature↔task"*, agora visto entre task e índice.
2. **A substituição deixa espaço duplo.** As 9 linhas tocadas ficaram com dois espaços antes do
   hash. Cosmético, mas é o sinal de que a montagem da célula é uma substituição de texto e não
   uma reconstrução da linha — que é a raiz do defeito 1.

Isto reforça o ponto 3 dos próximos passos: enquanto o `--check` não reprovar, o `--fix` continua
podendo anunciar sucesso sem ter feito o trabalho, e quem lê a saída acredita.
