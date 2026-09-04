# TASK-0080: renomear o uniform `length`, que é função embutida do GLSL e derruba a onda XMB

- **Status:** concluída
- **Criada em:** 2026-09-03
- **Concluída em:** 2026-09-03
- **Feature:** nenhuma
- **Bugs que resolve:** [xmb-gl-nao-compila-shader-uniform-chamado-length](../bugs/done/xmb-gl-nao-compila-shader-uniform-chamado-length_2026-09-01T10-45.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0080:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

`XmbGlView.kt:487` declara `uniform float length;`. `length()` é função embutida do GLSL, e um
compilador que aplique a regra recusa o shader inteiro:

```
0:39: L0001: Symbol 'length' can't be referenced as a variable
```

Quando `initGl` falha, a thread `xmb-gl` sai e o app cai para o fundo 2D. Ou seja: **a onda XMB pode
estar invisível para uma fatia dos usuários desde sempre**, porque o fallback é bonito e ninguém
reclama de um fundo bonito trocado por outro.

## Por que agora, e não antes

O relatório traz um "⚠️ Cuidado antes de corrigir" que continuava válido quando foi escrito:
corrigir o shader faria a onda GL **passar a compilar**, trocando um fundo estático barato por uma
thread EGL a 30 fps — exatamente o que a [TASK-0063](TASK-0063-fundo-da-biblioteca-para-de-animar.md)
tinha acabado de tirar do fundo 2D, medindo a queda de 0,94 para 0,15 de um núcleo.

**Essa condição foi cumprida pela [TASK-0070](TASK-0070-onda-xmb-em-gl-tambem-para-de-animar.md).**
Hoje `XmbGlView.RenderThread.run()` desenha **um** quadro em `FROZEN_T` e depois **estaciona** num
`wakeLock.wait()`; só acorda por resize, mudança de cor ou shutdown. Uma thread parada está
bloqueada, não girando. O custo que o aviso protegia não existe mais, então a ordem que ele pedia
— decidir primeiro o que a onda faz, corrigir o shader depois — está cumprida nessa ordem.

## Escopo

**Entra:**

- `length` → `waveLength` na declaração, nos três usos dentro do `main()` do vertex shader, e no
  `glUniform1f(u("length"), LENGTH)` que o alimenta. O nome novo segue a convenção dos vizinhos
  (`waveCosAmp`, `waveBias`, `waveHeightScale`, `waveSoftClip`).
- Corrigir o comentário que atribui a queda ao hardware. Tanto `XmbGlView` quanto
  `LibraryWaveBackground` dizem que o caminho 2D existe para *"older Mali without float-texture
  filtering, or any EGL failure"*. No aparelho do relato não era nem uma coisa nem outra — era erro
  de compilação do nosso GLSL, que falharia em qualquer driver estrito.

**NÃO entra:**

- Remover o fallback 2D. Ele continua sendo a rede para falha de EGL de verdade.
- Voltar a animar a onda. A decisão da TASK-0070 vale.
- Auditar os outros shaders do app. Conferidos os uniforms deste arquivo — nenhum outro nome colide
  com embutido do GLSL ES 3.00 —, mas uma varredura geral é outra coisa.

## Como validar

Em aparelho:

```bash
adb logcat -c && adb shell am force-stop come.nanodata.armsx2
# abrir o app, entrar na biblioteca
adb logcat -d | grep -i XmbGlView
```

Antes: `Symbol 'length' can't be referenced as a variable` a cada tentativa de init. Depois: nenhuma
linha de erro, e a thread `xmb-gl` existe (`adb shell ps -T | grep xmb-gl`) em vez de sair.

E a checagem que o relatório usou para medir o custo: a thread aparece **uma vez** e fica parada —
não deve consumir CPU mensurável em repouso.

## Resultado

Feito e medido em aparelho — moto g86 5G, Android 16 (SDK 36), `github/release`:

- **Nenhuma linha `XmbGlView` no logcat.** Antes, `Symbol 'length' can't be referenced as a
  variable` saía a cada tentativa de init.
- **A thread `xmb-gl` existe e fica viva** (`ps -T`, estado `S`). No relato, ela saía logo depois de
  `initGl` falhar — o `/proc` do A12 não tinha nenhuma thread com esse nome.
- **Custo em repouso: zero.** `utime+stime` da thread medido duas vezes com 6 segundos entre elas:
  **2 ticks → 2 ticks, delta 0**. Ela desenha um quadro e estaciona no `wakeLock.wait()`, como a
  TASK-0070 desenhou. Era exatamente esse o receio do "cuidado antes de corrigir", e ele não se
  materializou.
- **A onda aparece na tela**, atrás da grade do catálogo.

### E o A/B no aparelho do relato, que fecha a questão

O Galaxy A12 `SM-A127M` (Mali-G52) apareceu no mesmo dia, e o antes/depois foi medido nele:

| | `1.0.24` instalado (antes) | `githubDebug` desta branch (depois) |
|---|---|---|
| logcat | `GL init failed` + `S0022: Symbol 'length' redeclared` + `L0001: … can't be referenced as a variable` ×3 | **nenhuma linha** |
| thread `xmb-gl` | ausente — saiu, como o relatório descreve | **viva**, tid 12323, estado `S` |
| CPU em repouso | — | `utime+stime` **3 → 3 ticks em 8 s**, delta 0 |

Duas notas de método que valem mais que o resultado:

- **A release não instalou aqui** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): este aparelho tem um build
  **de debug**, e a release usa a chave de produção. A validação passou a usar `assembleGithubDebug`
  — e ainda bem, porque a pasta de dados tem **14 GB de ROMs** que um `uninstall` levaria junto.
- **O GL nem sempre roda.** O aparelho tinha `library.background.animated2d = true`, e nesse modo o
  `HomeScreen` pula o `XmbGlView` inteiro: sem thread e sem erro, o que parecia a correção não
  fazendo efeito. A preferência foi desligada para medir e **devolvida ao valor original** depois.
