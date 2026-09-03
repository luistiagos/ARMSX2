# TASK-0080: renomear o uniform `length`, que é função embutida do GLSL e derruba a onda XMB

- **Status:** em andamento
- **Criada em:** 2026-09-03
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [xmb-gl-nao-compila-shader-uniform-chamado-length](../bugs/open/armsx2-fork/xmb-gl-nao-compila-shader-uniform-chamado-length_2026-09-01T10-45.md)
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

Preenchido ao concluir.
