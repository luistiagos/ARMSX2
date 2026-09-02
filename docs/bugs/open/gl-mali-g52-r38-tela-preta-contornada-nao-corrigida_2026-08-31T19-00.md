# Bug: tela preta no OpenGL em Mali-G52 r38 — contornada por troca de renderer, não corrigida

- **Detectado em:** 2026-08-31 19:00 (registro retroativo — o defeito é anterior, o contorno foi
  publicado sem registro)
- **Origem:** Galaxy A12 `SM-A127M`, Exynos 850, Mali-G52, driver ARM r38p1, 007: Everything or
  Nothing. A/B de campo confirmado em 2026-08-31.
- **Errors (serviço):** nenhum — **não é crash, e é justamente por isso que não gera telemetria**
- **Classe:** fail
- **Reincidência:** é da mesma família da tela preta do A07
  ([gs-tela-preta-silenciosa-sem-diagnostico-a07](gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md)),
  em outro aparelho e outro backend
- **Feature:** nenhuma
- **Tasks que o resolvem:** **nenhuma** — a [TASK-0065](../../task/TASK-0065-veredito-do-renderer-em-todo-relato.md)
  registra o defeito e instrumenta o diagnóstico; **não o corrige**

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
([GSGPUDriverProfile.cpp:358](../../../pcsx2/GS/Renderers/Common/GSGPUDriverProfile.cpp#L358))
faz o `auto` desses aparelhos resolver para Vulkan. Ela funciona — o usuário vê imagem — e por isso
**fica**: tirá-la agora devolve a tela preta a todo mundo.

Mas ela é um desvio, não um conserto, e tem três custos que precisam estar escritos:

1. **Alcance global a partir de evidência local.** A regra casa com **todo Mali-G52 em driver
   r38.x**, no mundo inteiro. A evidência é um jogo, num telefone.
2. **Ela troca semântica de emulação de carona.** Mali no Vulkan descarta o piso de Z de 32 bits do
   PS2, e no OpenGL não — registrado em
   [mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta](mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md).
   Ninguém decidiu isso; veio junto com a troca de backend.
3. **É o terceiro movimento igual.** [`plano-grafico-mali-convergencia-upstream.md`](../../plano-grafico-mali-convergencia-upstream.md),
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
| `eglSwapInterval(0)` em Mali | o upstream já protege esse caso em [`GSDeviceOGL::SetSwapInterval`](../../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1346), e a guarda só vale com vsync desligado. **Não verificado no aparelho** com vsync ligado/desligado — é a próxima coisa barata a testar |

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
([TASK-0067](../../task/TASK-0067-merge-com-o-upstream.md), 72 commits) e APK `githubDebug` novo.
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
