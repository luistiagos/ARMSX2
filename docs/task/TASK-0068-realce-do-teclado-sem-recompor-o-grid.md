# TASK-0068: mover o realce do teclado deixa de recompor as quarenta teclas

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** `digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui` (uma das três parcelas)
- **Commit:** — (o vínculo é o prefixo `TASK-0068:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

Medido no Galaxy A12 em 2026-08-31, na busca de Configurações (mesmo teclado, sem a onda e sem a
grade da biblioteca), seis toques por braço:

| braço | quadros | 50º | 90º |
|---|---|---|---|
| A — sempre a MESMA tecla | 8 | **61 ms** | 150 ms |
| B — seis teclas DIFERENTES | 8 | **93 ms** | 150 ms |

Os dois braços digitam a mesma quantidade de caracteres e refazem o mesmo resultado de busca. A
única diferença é o realce: tocar a mesma tecla escreve `row.intValue = r` com o valor que já
estava, e `mutableIntStateOf` **não notifica em escrita de valor igual** — o `Overlay` não é
invalidado. Tocar outra tecla notifica.

**Mover o realce custa ~32 ms**, um terço do piso por tecla.

## Causa

`Overlay` lê `row`/`col` no próprio corpo:

```kotlin
val curRow = row.intValue
val curCol = col.intValue
...
val isSelected = (curRow == r && curCol == c) || (key == SHIFT && isShifted)
KeyCap(label = label, selected = isSelected, ...)
```

O Compose invalida o **escopo reiniciável mais próximo** de uma leitura de estado. Aqui esse escopo
é o conteúdo do `Surface`, que contém as cinco linhas inteiras: para trocar a cor de **uma** tecla,
as quarenta recompõem e regravam sua display list.

Note que subir a leitura para dentro de cada `KeyCap` **não resolve sozinho** — as quarenta leriam
`row`/`col` e as quarenta seriam invalidadas do mesmo jeito. E não adianta ler dentro do `key(r, c)`:
`key` é `inline`, então a leitura é atribuída ao escopo de fora, o mesmo de hoje.

## Escopo

**Entra:**

- `LibraryKeyboard.Overlay` — deixa de ler `row`/`col`. Passa `r`, `c` e `forceSelected` (o caso do
  SHIFT, que depende de `shifted` e não da posição) para cada `KeyCap`.
- `LibraryKeyboard.KeyCap` — passa a derivar a própria seleção:

  ```kotlin
  val selected by remember(r, c, forceSelected) {
      derivedStateOf { forceSelected || (row.intValue == r && col.intValue == c) }
  }
  ```

  `derivedStateOf` recalcula para as quarenta (duas comparações de inteiro), mas **só notifica quem
  observa quando o valor muda**. Movendo o realce de A para B, apenas dois booleanos mudam — logo
  apenas duas `KeyCap` recompõem. `KeyCap` é uma função `@Composable` normal, portanto tem escopo
  reiniciável próprio; é isso que faz a invalidação parar nela.

**NÃO entra:**

- **O piso residual de ~61 ms.** É a maior parcela e continua sem causa identificada. Esta task não
  o toca, e não deve ser lida como tendo resolvido "a digitação lenta".
- **O acréscimo do catálogo** (mediana 150 ms, cauda 450 ms com 12.305 linhas).
- **Trocar a cor por `drawBehind`.** Seria a alternativa "ler na fase de desenho", mas a cor do
  texto e o peso da fonte também mudam com a seleção, e reproduzi-los fora da composição exigiria
  desenhar dois `Text` sobrepostos com alfa. Mais mudança e mais risco visual para o mesmo ganho.

## Resultado medido, e ele NÃO confirma a previsão

A/B controlado em 2026-09-01: worktree isolada em `ARMSX2-t68`, mesmo commit (`2ed7606986`), os dois
APKs diferindo **só** neste arquivo, instalados em sequência no mesmo A12, mesmo roteiro — busca de
Configurações, 12 toques por braço, três rodadas cada.

| | A (mesma tecla) | B (teclas diferentes) |
|---|---|---|
| **sem** a mudança | 61 / 61 / 61 ms | 89 / 85 / 85 ms |
| **com** a mudança | 61 / 57 / 57 ms | **77 / 77 / 77 ms** |

**B melhorou ~8 ms** (85 → 77), de forma consistente nas três rodadas. O braço A ficou dentro do
ruído. O custo do realce, que é a diferença B−A, caiu de ~24 ms para ~19 ms.

**A previsão da seção "Como validar" era que B cairia para perto de A. Não caiu.** A mudança remove
cerca de um terço do custo do realce, não o custo todo — sobram ~19 ms cuja causa **não** está
identificada. Duas coisas a considerar antes de acreditar em qualquer explicação nova: recompor duas
`KeyCap` não deveria custar 19 ms, e a estimativa anterior de "~32 ms" para o realce vinha de uma
amostra de 6 toques numa rodada só; medida direito, a linha de base é ~24 ms.

**O ganho é real e pequeno:** ~8 ms num quadro de digitação de ~85 ms, com zero mudança de
comportamento. Mantida por isso, não por ter confirmado a hipótese — ela confirmou o mecanismo e
errou a magnitude.

## Como validar

1. **Aparência idêntica** — a tecla realçada continua com fundo `primary`, texto `onPrimary` e
   negrito; o SHIFT continua realçado enquanto travado.
2. **A/B pelo mesmo roteiro do Contexto**, na busca de Configurações. O esperado é o braço B cair
   para perto do braço A. Se não cair, a hipótese está errada e a task não se justifica.
3. `:app:compileGithubDebugKotlin`.
