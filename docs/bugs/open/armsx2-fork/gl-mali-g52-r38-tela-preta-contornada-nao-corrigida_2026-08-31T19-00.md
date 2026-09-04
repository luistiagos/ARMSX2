# Bug: tela preta no OpenGL em Mali-G52 r38 — contornada por troca de renderer, não corrigida

- **Detectado em:** 2026-08-31 19:00 (registro retroativo — o defeito é anterior, o contorno foi
  publicado sem registro)
- **Origem:** Galaxy A12 `SM-A127M`, Exynos 850, Mali-G52, driver ARM r38p1, 007: Everything or
  Nothing. A/B de campo confirmado em 2026-08-31.
- **Errors (serviço):** nenhum — **não é crash, e é justamente por isso que não gera telemetria**
- **Classe:** fail
- **Reincidência:** é da mesma família da tela preta do A07
  ([gs-tela-preta-silenciosa-sem-diagnostico-a07](../legado-version1/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md)),
  em outro aparelho e outro backend
- **Feature:** nenhuma
- **Tasks que o resolvem:** **nenhuma corrige a causa**, e a partir de 2026-09-04 sabe-se por quê:
  a causa está **no driver GLES da ARM**, fora do nosso código e fora do core (ver
  *"A causa: é o driver"* abaixo). A [TASK-0065](../../../task/TASK-0065-veredito-do-renderer-em-todo-relato.md)
  registra o defeito e instrumenta o diagnóstico; a
  [TASK-0083](../../../task/TASK-0083-escolha-de-angle-por-jogo-chega-ao-core.md) faz o contorno
  medido (ANGLE por jogo) **funcionar**, porque a chave existia e era inerte

## Sintoma

No renderizador OpenGL, a saída fica **permanentemente preta** enquanto a VM, o áudio, os FMVs e o
contador de quadros continuam. O GS produz quadros; nada chega ao painel.

A/B executado no aparelho em 2026-08-31, com upscale em 1x nativo:

| renderizador | resultado |
|---|---|
| OpenGL (forçado à mão) | **tela preta** |
| Vulkan | imagem aparece, com o defeito de linhas do outro registro |

## O contorno que está publicado, e por que ele não é a correção

A regra `gl-arm-g52-r38-auto-vulkan`
([GSGPUDriverProfile.cpp:358](../../../../pcsx2/GS/Renderers/Common/GSGPUDriverProfile.cpp#L358))
faz o `auto` desses aparelhos resolver para Vulkan. Ela funciona — o usuário vê imagem — e por isso
**fica**: tirá-la agora devolve a tela preta a todo mundo.

Mas ela é um desvio, não um conserto, e tem três custos que precisam estar escritos:

1. **Alcance global a partir de evidência local.** A regra casa com **todo Mali-G52 em driver
   r38.x**, no mundo inteiro. A evidência é um jogo, num telefone.
2. **Ela troca semântica de emulação de carona.** Mali no Vulkan descarta o piso de Z de 32 bits do
   PS2, e no OpenGL não — registrado em
   [mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta](../../done/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md).
   Ninguém decidiu isso; veio junto com a troca de backend.
3. **É o terceiro movimento igual.** [`plano-grafico-mali-convergencia-upstream.md`](../../../plano-grafico-mali-convergencia-upstream.md),
   seção *"O que explicitamente NÃO fazer"*: *"Trocar OpenGL ↔ Vulkan globalmente como 'correção'.
   Já foi feito nos dois sentidos (1.0.17 e 1.0.20) e os dois falharam."*

## O que a regra tem a favor, e que o registro anterior desta análise subestimava

**A regra entrou com testes.** `tests/ctest/core/gs/gs_gpu_driver_profile_tests.cpp` pina a string
real de `GL_VERSION` do aparelho através do resolvedor
(`MaliG52R38PrefersVulkanForAndroidAuto`) e pina que ela **não** se alarga para revisões e modelos
vizinhos (`MaliG52R38AutoPreferenceIsNarrow`, cobrindo r37p1, r39p0, G51 e G57). O que faltou foi
**task e registro de bug**: ela entrou no commit `bf45520833`, cujo assunto é `*`.

Consequência prática: o código diz o que a regra faz, mas nada diz **o que foi medido**, então
ninguém consegue revisar a decisão nem saber quando ela pode sair.

## O que já foi descartado

| hipótese | como caiu |
|---|---|
| framebuffer fetch em GL | o aparelho continuou preto com esse caminho desligado (registrado no comentário da regra) |
| cache de shader do GL corrompido | reconstruir o cache não mudou nada (idem) |
| `eglSwapInterval(0)` em Mali | o upstream já protege esse caso em [`GSDeviceOGL::SetSwapInterval`](../../../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1346), e a guarda só vale com vsync desligado. **Não verificado no aparelho** com vsync ligado/desligado — é a próxima coisa barata a testar |

## A pista que ainda não foi seguida

O comentário do teste diz que o A12 é *"the device which exposed the OpenGL presentation regression
after the 1.0.23 -> 1.0.24 core transition"*. Se isso estiver certo, **a tela preta é regressão do
transplante**, não defeito do aparelho: a 1.0.23 renderizava este mesmo telefone em OpenGL.

Isso é verificável e ninguém verificou. A afirmação está num comentário de teste, sem medição
citada. Confirmar ou derrubar isso decide tudo:

- **Se for regressão nossa**, a correção é achar o que mudou no caminho de apresentação em GL entre
  as duas árvores, e a regra sai.
- **Se não for**, o aparelho sempre foi assim e a regra é o contorno permanente correto — mas aí
  ela precisa de justificativa própria, não da frase "regressão" que a sustenta hoje.

Uma diferença já foi conferida e **não** é a causa: `SetSwapInterval` ganhou uma guarda de Mali
**no upstream**, ou seja, a árvore nova tem proteção a mais nesse ponto, não a menos.

## Próximos passos, na ordem de custo

1. Instalar a 1.0.23 (linha `feature/handoff-end-to-end`) no A12 e abrir o mesmo jogo em OpenGL.
   Uma resposta binária que decide entre os dois caminhos acima.
2. Se for regressão: `diff` do caminho de apresentação em GL (`GSDeviceOGL::Create`,
   `RenderBlankFrame`, `GLContextEGLAndroid`, `SetSwapInterval`) entre as duas árvores.
3. Com o veredito do renderer agora em todo relato (TASK-0065), confirmar em campo que a regra está
   de fato casando nos aparelhos afetados — hoje isso é indistinguível de "não casou e o usuário
   escolheu Vulkan à mão".

## Reteste depois do merge com o upstream (2026-09-01)

Retestado no mesmo A12 `SM-A127M`, com a árvore já no upstream de 31/08
([TASK-0067](../../../task/TASK-0067-merge-com-o-upstream.md), 72 commits) e APK `githubDebug` novo.
**O defeito continua.** `renderer=12` confirmado no log, `GL_RENDERER: Mali-G52`, driver `r38p1`.

O que o reteste acrescenta ao registro:

1. **A tela preta agora está medida, não julgada a olho.** As capturas em +52 s, +112 s, +142 s e
   +172 s do boot são **byte a byte idênticas** (md5 `629192d67bc9d079dd30d6a549d2b453`), enquanto o
   `PerfLog` do mesmo intervalo mostra a VM viva — quadro 5845, 36,9 fps, GS em 66%. Confirma a
   descrição do sintoma com número em vez de impressão.
2. **O FMV de abertura APARECE.** Não é preto desde o primeiro quadro: a silhueta da abertura
   renderiza por volta de +80 s, e só depois a saída congela em preto. Quem for procurar a causa
   precisa saber que o caminho de apresentação funciona por alguns segundos antes de parar.
3. **O defeito é do título, não do backend.** O *10 Pin - Champions Alley* bootou em OpenGL no
   mesmo aparelho e na mesma sessão e renderizou normalmente. Isso enfraquece ainda mais a regra
   `gl-arm-g52-r38-auto-vulkan`, que desvia **todo** Mali-G52 r38 do mundo com base neste jogo.
4. **Não é "o upstream já resolveu".** Essa hipótese está eliminada.

## O contorno foi retirado (2026-09-02)

O achado nº 3 do reteste acima — *10 Pin* renderiza em GL no mesmo aparelho e na mesma sessão —
derrubou a premissa da regra `gl-arm-g52-r38-auto-vulkan`: o discriminador é o **título**, e nenhum
eixo do banco de drivers separa dois jogos. A regra saiu na
[TASK-0072](../../../task/TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md), com o registro
do porquê no lugar onde ela estava.

**Este bug continua aberto e agora é visível no padrão**: no `auto`, o 007 volta a ficar preto
nesses aparelhos. A saída, por jogo e alcançável com a tela preta (o overlay de toque fica por cima
da área de render), é: menu em jogo → Renderer → Vulkan → reiniciar.

As duas pistas que ficaram, e que são melhores do que tudo que se tinha antes:

1. **É do título, não do backend.** Procurar o que o 007 faz e o *10 Pin* não faz.
2. **A apresentação PARA, não deixa de começar.** O FMV aparece por volta de +80 s e só então
   congela. Isso descarta falha de criação de device ou de shader e aponta para superfície/swapchain
   perdida, ou para a thread do GS presa no present.

## A causa: é o driver — medido em 2026-09-04

Aparelho `SM-A127M`, Mali-G52, driver ARM **r38p1**, Android 13 (SDK 33). APK `githubDebug`
(versionCode 2004). Jogo 007: Everything or Nothing (`SLUS-20751`, CRC `6848699B`), `renderer=opengl`
fixado por jogo, upscale 1,25x. Boot por intent externo, para ser repetível:

```
adb shell "am start -n come.nanodata.armsx2/com.armsx2.Main -a android.intent.action.VIEW \
  --es path '/sdcard/Android/data/come.nanodata.armsx2/files/roms/007 - Everything or Nothing (USA).chd'"
```

### O instrumento: o OSD do próprio core

Em vez de compilar instrumentação nova (~14 min de build nativo), a sessão ligou o **OSD do core**
(`OsdShowFPS` / `OsdShowGSStats` / `OsdShowResolution`), que é desenhado por ImGui dentro de
`GSRenderer::EndPresentFrame` → `ImGuiManager::RenderOSD()` → `GSDeviceOGL::EndPresent()`, ou seja
**dentro do mesmo passe de present, imediatamente antes do `SwapBuffers`**. Ele responde de graça a
três perguntas que o relatório vinha fazendo.

### 1. A apresentação está viva — o `SwapBuffers` não é o problema

Com a tela preta, o OSD **desenha e atualiza**, em todas as capturas:

```
BAT 31°FPS: 17.64 [P] | Speed: 59% (T: 100%) | ARMSX2 2.7
OpenGL HW | 50216 PRIM | 50 DRW | 53 DRWC | 0 BAR | 11 RP | 0 RB | 5 TC | 12 TU
VRAM: 194 MB | TGT: 3.1 MB | SRC: 0.0 MB | HC: 185 MB | PL: 6.0 MB
0 QF | Min: 43.22ms | Avg: 54.04ms | Max: 71.18ms
640x560 NTSC Interlaced (Field)
```

Se a superfície EGL estivesse perdida, ou a thread do GS presa no present, esse texto não apareceria
— ele passa pelo mesmo `eglSwapBuffers`. **Cai a hipótese "superfície/swapchain perdida" e cai
"thread do GS presa no present"**, que eram as duas que o registro de 2026-09-02 deixou de pé.

### 2. O GS produz saída — `Merge()` retorna verdadeiro e `PresentRect` é chamado

A linha `640x560` do OSD é `GSgetInternalResolution`, que devolve `GSRenderer::GetInternalResolution()`,
que é literalmente `return m_real_size`. E `m_real_size` só é escrito dentro de `GSRenderer::Merge`,
que o **zera** (`m_real_size = GSVector2i(0, 0)`) nos seus **dois** caminhos de `return false` — os
circuitos PCRTC desabilitados, e `GetOutput` devolvendo nulo para os dois circuitos.

Logo, `640x560` com a tela preta significa: `Merge()` **retornou true** → `blank_frame == false` →
o `if (current && !blank_frame)` da `VSync` é verdadeiro → **`g_gs_device->PresentRect(current, …)`
é executado com uma textura não nula**.

Isso derruba de uma vez a família inteira de hipóteses "o GS não produziu quadro": PCRTC desligado,
`GSRendererHW::GetOutput` devolvendo nulo, `LookupDisplayTarget` sem alvo. Nenhuma delas sobrevive a
`m_real_size != 0`. E o contador do OSD confirma que o GS está desenhando de verdade: **50.216
primitivas, 50 draws, 53 draw-calls e 11 render passes por quadro**.

Vale corrigir uma leitura do relatório antigo enquanto isso: a fps que se via ("36,7 fps, quadros
andando") **não** provava que quadros estavam sendo apresentados, porque `DoBeginPresent` limpa o
alvo com `glClearColor(0,0,0,1)` a cada quadro — um present de nada também produz 37 fps e capturas
byte a byte idênticas. O que prova a apresentação é o OSD desenhando; o que prova a saída do GS é o
`m_real_size`.

### 3. Não é o desentrelaçamento

`640x560 NTSC Interlaced (Field)` levantou o `FastMAD` como suspeito natural: o FMV que **aparece**
roda a `80x78` (progressivo, `shader_mode == -1`, passe direto) e o preto começa exatamente quando a
saída vira `640x560` entrelaçada, que em `Automatic` cai no `shader_mode == 3` do
[`GSInterlaceModePolicy.h`](../../../../pcsx2/GS/Renderers/Common/GSInterlaceModePolicy.h) — dois
passes sobre um buffer MAD de altura dobrada que depende de conteúdo preservado entre quadros, que é
exatamente o tipo de coisa que uma GPU tile-based erra.

Medido: `deinterlaceMode = 1` (Off) por jogo, **confirmado aplicado** (`deinterlace_mode = 1` no
`PCSX2-Android.ini` do aparelho, lido depois do boot). Com `GSInterlaceMode::Off`,
`GSDevice::Interlace` nem chega a ser chamada e `m_current = m_merge`. **A tela continua preta.**
Hipótese eliminada.

### 4. O que fecha a causa: ANGLE

`useAngleOpenGL` faz `GLContextEGL::LoadEGL` abrir `libEGL_angle.so` (GLES-on-Vulkan) em vez do
`libEGL.so` do sistema. **Mesmo código de GS, mesma sequência de chamadas GL, mesmo aparelho, mesmo
jogo, mesma configuração** — só muda a implementação de GL:

| braço | `GL_VENDOR` | `GL_RENDERER` | linha de GS do OSD | resultado |
|---|---|---|---|---|
| driver do sistema | `ARM` | `Mali-G52` | `50216 PRIM \| 50 DRW \| 53 DRWC \| 0 BAR \| 11 RP \| 0 RB \| 5 TC \| 12 TU` | **preto** |
| ANGLE | `Google Inc. (ARM)` | `ANGLE (ARM, Vulkan 1.3.213 (Mali-G52 (0x72120000)), Mali-G52-38.1.0)` | **a mesma linha**, e a mesma linha de memória (`VRAM: 194 MB \| TGT: 3.1 MB \| SRC: 0.0 MB \| HC: 185 MB \| PL: 6.0 MB`) | **imagem** |

Medida da área de render (recorte `(0,150)-(720,570)` da captura de tela, que é a área do
`SurfaceView`):

| braço | cores distintas | cor dominante | md5 entre capturas |
|---|---|---|---|
| driver do sistema | **202** (e 92,7 % delas é o *overlay de toque*, não a imagem: o fundo é uma cor só) | `(19,19,19)` em 92,7 % | **idêntico** em +70/90/110/130/150 s |
| ANGLE | 12.244 → 32.546 → 43.275 | `(19,19,19)` em 12,9 % | **diferente** a cada captura, por 220 s |

**Conclusão: o emulador emite o mesmo trabalho nos dois braços; quem erra é o driver GLES da ARM
r38p1.** Não é defeito do fork, não é defeito do core, e não é "o upstream já resolveu" — não há o
que mandar para o upstream a partir daqui, porque não há linha de código nossa ou deles em falta.

Isso também explica, sem contradição, as duas pistas de 2026-09-02: é "do título" porque só um jogo
neste aparelho usa a sequência de GL que o driver erra (o *10 Pin* renderiza), e "para depois do
FMV" porque o FMV usa uma saída de `80x78` e o jogo passa para `640x560` entrelaçada.

### O contorno certo, e por que ele estava inerte

O contorno agora é **ANGLE, mantendo o OpenGL** — e não trocar de backend, que é o que o histórico
deste bug vinha fazendo. A diferença importa: trocar para Vulkan leva junto uma mudança de semântica
de emulação que ninguém decidiu (o
[piso de Z](../../done/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md)), e neste
par jogo/aparelho o Vulkan ainda perde o device.

Mas ligar ANGLE pelo menu em jogo **não fazia nada**, e isso também foi medido aqui: nenhuma linha
`@@ANGLE@@` no log e `GL_VENDOR` continuando `ARM`. Duas causas, as duas em código nosso, corrigidas
na [TASK-0083](../../../task/TASK-0083-escolha-de-angle-por-jogo-chega-ao-core.md): `applyAngleEnv`
lia só a camada **global** enquanto o menu em jogo grava **por jogo**, e o botão "Aplicar e
Reiniciar" não passava por `applyAngleEnv` nenhuma. Depois da correção, com o global em `false` e só
a chave do jogo em `true`:

```
@@ANGLE@@ off     renderer=opengl useAngle=false     <- init da Activity, sem jogo: global
@@ANGLE@@ enabled renderer=opengl useAngle=true      <- boot da VM: resolução por jogo
GL_VENDOR: Google Inc. (ARM)
```

e a imagem aparece (capturas em +105 s e +160 s, md5 `e8315ec15af85baa5053148f90bb98e6` e
`d43122017d7d381bd8ac9598244b11ab`, 12.244 e 32.546 cores).

### O que continua aberto

1. **Qual operação de GL o driver r38p1 erra.** Sabe-se que é o driver, não *o quê*. Achar isso
   exigiria capturar/bissectar o fluxo GL (RenderDoc no aparelho, ou desligar caminhos do
   `GSDeviceOGL` um a um em builds nativos de ~14 min cada). É o próximo passo mais informativo se
   alguém quiser a causa raiz — mas ela **não é corrigível por nós**: mesmo achada, a correção seria
   um contorno de driver.
2. **A treliça sob ANGLE.** A imagem aparece, mas com uma malha fina visível sobre todo o quadro nas
   duas capturas. Não investigada. Não impede jogar, e não existe no braço preto porque lá não há
   imagem nenhuma para comparar.
3. **O alcance.** A evidência continua sendo **um** jogo em **um** telefone. Nada aqui autoriza
   ligar ANGLE sozinho para todo Mali r38 — seria repetir o erro da regra
   `gl-arm-g52-r38-auto-vulkan`, retirada na [TASK-0072](../../../task/TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md)
   por exatamente isso.
4. **Pôr ANGLE na escada da TASK-0082.** Hoje a ação "a imagem não apareceu" propõe Vulkan → Software.
   Com esta medida, o degrau útil para um Mali em OpenGL passa a ser "OpenGL via ANGLE" **antes** de
   trocar de backend. Muda uma escada já validada em aparelho, então merece task própria.

### Por que o relatório continua em `open/`

Porque a causa não está corrigida: ela está **identificada e fora do nosso alcance**, e o que existe
é mitigação. A severidade cai na prática — o título é jogável em OpenGL com ANGLE, sem trocar de
backend — mas quem abrir este arquivo precisa encontrar o defeito descrito, não um "resolvido" que
some do índice.
