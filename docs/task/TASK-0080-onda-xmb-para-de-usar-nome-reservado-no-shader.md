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

### O limite honesto desta validação

O relato nasceu num **Galaxy A12 (Mali-G52)**, e é lá que o driver recusa o shader. O aparelho
disponível é um moto g86, cujo compilador pode ser permissivo com o nome — ou seja, aqui não dá para
provar que a versão anterior falhava. O que esta medição prova é que a versão nova **compila, sobe e
não custa nada**; a prova de que o A12 deixou de cair para o fundo 2D depende de retestar naquele
aparelho, e é o que o relatório pede em "Como reproduzir".

Isso não enfraquece a correção: `length` é nome de função embutida do GLSL, e a recusa é a leitura
correta da regra. O que varia entre drivers é a permissividade, não quem está certo.
