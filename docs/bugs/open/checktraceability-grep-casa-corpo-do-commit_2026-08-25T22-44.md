# Bug: o validador resolve task→commit pelo corpo do commit, não pelo assunto

- **Detectado em:** 2026-08-25 22:44 (revisão do processo, reproduzido em repositório de teste)
- **Origem:** `scripts/check_traceability.py::commits_for_task`
- **Errors (serviço):** nenhum — ferramenta de desenvolvimento, não roda no aplicativo
- **Classe:** fail
- **Reincidência:** primeira vez
- **Feature:** [FEAT-0002](../../features/FEAT-0002-rastreabilidade-verificavel.md)
- **Tasks que o resolvem:** [TASK-0010](../../task/TASK-0010-corrigir-validador-rastreabilidade.md)

## Sintoma

`commits_for_task()` diz ter encontrado o commit de uma task quando nenhum commit tem aquele assunto.
Basta que **qualquer** commit mencione `TASK-NNNN:` no início de uma linha do corpo.

## Causa raiz

[`check_traceability.py:78`](../../../scripts/check_traceability.py#L78):

```python
out = git("log", "--format=%h", "--grep=^" + tid + ":", "--extended-regexp", "HEAD")
```

`git log --grep` aplica a expressão à **mensagem inteira** do commit, e `^` casa o início de
qualquer linha — não o início do assunto. O docstring da função diz *"Fonte de verdade do vinculo
task->commit: o assunto do commit"*, mas o código não olha o assunto em momento nenhum.

## Como reproduzir

```sh
mkdir /tmp/t && cd /tmp/t && git init -q .
echo a > a && git add a
git commit -q -m "chore: mexe noutra coisa" -m "Contexto:
TASK-0099: isto e so uma mencao no CORPO, nao o assunto"

git log -1 --format='%s'                                   # -> chore: mexe noutra coisa
git log --format=%h --grep='^TASK-0099:' --extended-regexp # -> imprime o hash
```

Verificado em 2026-08-25: a segunda chamada retorna o commit, apesar de o assunto ser `chore:`.

## Impacto

Quebra nos dois sentidos, e o segundo é o pior porque acontece no fluxo normal do projeto:

1. **Falso positivo (aprova o que devia reprovar).** Uma task marcada `concluída` sem commit nenhum
   passa na validação se qualquer `chore:` citar a linha `TASK-NNNN:` no corpo. O único check que
   liga task a código real deixa de valer.
2. **Falso negativo (reprova o que está certo).** A regra "uma task = um commit" conta o commit real
   **mais** todo `chore:` que o cite, e reprova com *"uma task = um commit, mas ha 2 commits"*. O
   projeto gera exatamente esse tipo de commit — `bbdcd47ac0` e `3f4e0f4898` são `chore:` cujo
   assunto fala de uma task. Hoje escapam porque citam a task no **assunto** e não numa linha do
   corpo começando por `TASK-NNNN:`; é uma linha de texto de distância do falso negativo.

## Próximos passos

Filtrar pelo assunto no Python em vez de delegar ao `--grep`:

```python
out = git("log", "--format=%h%x00%s", "HEAD")
prefix = tid + ":"
return [h for h, s in (l.split("\0", 1) for l in (out or "").splitlines() if "\0" in l)
        if s.startswith(prefix)]
```

Um teste de regressão deve cobrir o caso acima — commit `chore:` com a menção no corpo não pode ser
retornado. Ver [TASK-0010](../../task/TASK-0010-corrigir-validador-rastreabilidade.md).
