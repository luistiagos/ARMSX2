# Bug: a onda XMB não compila no Mali — um `uniform` chamado `length`

- **Detectado em:** 2026-09-01 (Galaxy A12 `SM-A127M`, Mali-G52, build `githubDebug`)
- **Origem:** `platforms/android/app/src/main/java/com/armsx2/ui/home/XmbGlView.kt`
- **Errors (serviço):** nenhum — falha silenciosa, com queda para o fundo 2D
- **Classe:** correção
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda — ver "Cuidado antes de corrigir"

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
