# Bug: a onda XMB não compila no Mali — um `uniform` chamado `length`

- **Detectado em:** 2026-09-01 (Galaxy A12 `SM-A127M`, Mali-G52, build `githubDebug`)
- **Origem:** `platforms/android/app/src/main/java/com/armsx2/ui/home/XmbGlView.kt`
- **Errors (serviço):** nenhum — falha silenciosa, com queda para o fundo 2D
- **Classe:** correção
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0080](../../task/TASK-0080-onda-xmb-para-de-usar-nome-reservado-no-shader.md)

## Sintoma

A onda XMB em GLES3 nunca aparece neste aparelho. O usuário vê o fundo 2D
([`LibraryWaveBackground`](../../../platforms/android/app/src/main/java/com/armsx2/ui/home/LibraryWaveBackground.kt)),
que existe como **fallback**.

## Causa

Não é limitação de hardware. É o nosso shader:

```
W XmbGlView: 0:39: L0001: Symbol 'length' can't be referenced as a variable
W XmbGlView: 	at com.armsx2.ui.home.XmbGlView$RenderThread.compile(XmbGlView.kt:243)
W XmbGlView: 	at com.armsx2.ui.home.XmbGlView$RenderThread.link(XmbGlView.kt:249)
W XmbGlView: 	at com.armsx2.ui.home.XmbGlView$RenderThread.initGl(XmbGlView.kt:231)
```

`XmbGlView.kt:472` declara `uniform float length;`, e `length()` é **função embutida do GLSL**.
Declarar uma variável com esse nome é proibido, e o compilador do Mali recusa. O uniform é lido em
três lugares (`XmbGlView.kt:498`, `:500`, `:501`) e escrito em `:167` via `u("length")`.

**O comentário do código atribui a falha a outra coisa.** Tanto `XmbGlView` quanto
`LibraryWaveBackground` dizem que o caminho 2D existe para "*older Mali without float-texture
filtering, or any EGL failure*". Neste aparelho não é nem uma coisa nem outra — é um erro de
compilação do nosso GLSL, que falharia em qualquer driver que aplique a regra.

## Alcance

Desconhecido, e provavelmente maior do que parece. Qualquer driver estrito com essa regra rejeita o
shader; drivers permissivos aceitam. Ou seja, a onda XMB pode estar invisível para uma fatia dos
usuários **desde sempre**, sem ninguém notar, porque o fallback funciona e ninguém reclama de um
fundo bonito ser substituído por outro fundo bonito.

## Custo: nenhum, neste aparelho

Vale registrar porque a intuição diz o contrário. Quando `initGl` falha, `run()` chama `onStatus(false)`,
`teardown()` e retorna — a thread `xmb-gl` **sai**. Conferido no `/proc`: zero threads com esse nome.
Então a onda GL não consome nada aqui; ela simplesmente não existe.

## ⚠️ Cuidado antes de corrigir

**Corrigir só o shader seria uma regressão de desempenho.** A
[TASK-0063](../../task/TASK-0063-fundo-da-biblioteca-para-de-animar.md) removeu a animação do fundo
2D por decisão de produto — "não vale sacrificar desempenho por enfeite" —, e mediu a queda de
0,94 para 0,15 de um núcleo. Renomear o uniform faria a onda GL **passar a compilar** neste
aparelho, trocando o fundo estático barato por uma thread EGL desenhando a 30 fps.

Então a ordem correta é: **primeiro decidir o que a onda GL deve fazer** (animar ou ficar parada,
como a 2D), e só depois corrigir o shader. Corrigir agora entregaria ao usuário exatamente o que ele
acabou de pedir para tirar.

## Como reproduzir

```bash
adb logcat -d | grep -i XmbGlView
```

Com o app aberto na biblioteca ao menos uma vez. A linha `Symbol 'length' can't be referenced as a
variable` aparece uma vez por tentativa de inicialização do GL.

## Correção — 2026-09-03 ([TASK-0080](../../task/TASK-0080-onda-xmb-para-de-usar-nome-reservado-no-shader.md))

`length` → `waveLength`, na declaração, nos três usos e no `glUniform1f`. O nome novo segue os
vizinhos (`waveCosAmp`, `waveBias`, `waveHeightScale`, `waveSoftClip`). Conferidos os outros
uniforms do arquivo: nenhum colide com embutido do GLSL ES 3.00.

Os comentários que atribuíam a queda ao hardware — *"older Mali without float-texture filtering"*,
nos dois arquivos — foram corrigidos. Era o nosso GLSL.

### O "⚠️ Cuidado antes de corrigir" foi cumprido, não ignorado

O aviso dizia: corrigir o shader antes de decidir o que a onda GL deve fazer entregaria ao usuário
justamente o que ele acabou de pedir para tirar — uma thread EGL a 30 fps no lugar de um fundo
estático barato. Essa decisão foi tomada e implementada pela
[TASK-0070](../../task/TASK-0070-onda-xmb-em-gl-tambem-para-de-animar.md): `RenderThread.run()`
desenha **um** quadro em `FROZEN_T` e estaciona num `wakeLock.wait()`.

Medido em aparelho, e é a parte que valida o aviso: `utime+stime` da thread `xmb-gl`, duas leituras
com 6 segundos entre elas, **2 ticks → 2 ticks, delta 0**. A onda voltou a existir e não custa nada
em repouso.

### Validação

moto g86 5G, Android 16 (SDK 36), `github/release`:

- nenhuma linha `XmbGlView` no logcat (antes: `Symbol 'length' can't be referenced as a variable` a
  cada init);
- thread `xmb-gl` viva em estado `S` — no A12 ela saía logo após `initGl` falhar, e o `/proc` não
  tinha nenhuma com esse nome;
- a onda aparece na tela, atrás da grade do catálogo.

**Limite desta validação, dito na cara:** o relato nasceu num Galaxy A12 (Mali-G52), e é lá que o
driver recusa. No moto g86 o compilador pode ser permissivo, então aqui não dá para provar que a
versão anterior falhava — só que a nova compila, sobe e é gratuita. O reteste no A12 continua sendo
o que fecha o "Alcance" descrito acima.

## A/B no aparelho do relato — Galaxy A12 `SM-A127M`, Mali-G52, 2026-09-03

O "limite honesto" registrado acima **foi fechado**: o aparelho do relato apareceu, e o antes/depois
foi medido nele.

**Antes** — o que estava instalado (`1.0.24`, versionCode 38), abrindo a biblioteca:

```
21:57:39.677 10786 10864 W XmbGlView: GL init failed
21:57:39.677 10786 10864 W XmbGlView: java.lang.RuntimeException: shader compile:
                                      0:9: S0022: Symbol 'length' redeclared
21:57:39.677 10786 10864 W XmbGlView: 0:35: L0001: Symbol 'length' can't be referenced as a variable
21:57:39.677 10786 10864 W XmbGlView: 0:39: L0001: Symbol 'length' can't be referenced as a variable
21:57:39.677 10786 10864 W XmbGlView: 0:39: L0001: Symbol 'length' can't be referenced as a variable
```

`ps -T` não lista nenhuma thread `xmb-gl` — ela saiu, exatamente como o relatório descreve. Note que
o driver dá **duas** queixas distintas: `S0022: redeclared` na declaração e `L0001: can't be
referenced as a variable` em cada uso.

**Depois** — `githubDebug` desta branch, mesmo aparelho, mesma tela:

```
(nenhuma linha XmbGlView no logcat)

u0_a263  12204 12323  588  7878088 233140  0  0  S  xmb-gl
```

Sem erro nenhum, e a thread **viva** em estado `S`. Custo em repouso, medido no aparelho fraco que é
onde a preocupação do "cuidado antes de corrigir" mais valia: `utime+stime` **3 → 3 ticks em 8
segundos, delta 0**.

### Duas notas de método

1. **A instalação da release falhou aqui**, com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: o que está
   neste aparelho é um build **de debug** (assinatura de debug), e a release do fork usa a chave de
   produção. Foi por isso que a validação usou `assembleGithubDebug` — e ainda bem: a pasta de dados
   tem **14 GB de ROMs**, que um `adb uninstall` teria levado junto.

2. **O GL nem sempre roda.** Este aparelho tinha `library.background.animated2d = true` nas
   preferências, e nesse modo o `HomeScreen` pula o `XmbGlView` inteiro — sem thread e sem erro, o
   que à primeira vista parecia a correção não fazendo nada. A preferência foi desligada para a
   medição e **devolvida ao valor original** depois.
