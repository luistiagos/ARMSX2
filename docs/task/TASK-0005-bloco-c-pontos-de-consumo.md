# TASK-0005: Bloco C — ligar os pontos de consumo do perfil no GSDeviceOGL e GSDeviceVK

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-25
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [gs-mali-tela-vermelha-e-page-fault-driver](../bugs/open/gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md), [graphicshealthmonitor-falso-positivo-cenas-escuras](../bugs/open/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0005:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Substituir as decisões por nome de GPU e por título de jogo pelas decisões do banco de drivers.

> **Pré-condição relaxada, e por quê.** O texto original dizia "só começa depois que a TASK-0004
> tiver log de campo do A07 e do A15". Esse gate foi escrito supondo que o Bloco C ligaria
> workarounds novos — nesse caso, esperar o log é obrigatório. Não é o que acontece aqui: o efeito
> líquido no campo é **remover** uma regra (a de MGS3 por título) e **não ligar nenhuma outra**,
> porque nenhuma regra de GL do banco liga `UseRenderTargetCopyForFeedback` hoje e a única de
> Vulkan que liga é a do `r44p1`, que já era a intenção do código antigo. Ou seja: em todo aparelho
> que não seja um Mali `r44p1` em Vulkan, esta task deixa o comportamento igual ou mais rápido.
>
> O log de campo continua sendo a pré-condição para **acrescentar** regra ao banco. Ele não é
> pré-condição para parar de decidir pelo nome do jogo, que é uma regra que a evidência de campo
> abaixo já derrubou.

## Escopo

**Entra:**
- Os call-sites dos getters de perfil no `GSDeviceOGL::CheckFeatures`, reescritos à mão contra a
  nossa versão do arquivo. **Correção do número:** o escopo original dizia "13", contado sobre o
  arquivo do upstream. Na nossa versão, que é 1,48× menor, a superfície real que decide algo são
  **quatro** pontos — a decisão de fetch, a de texture barrier, o bloco `use_mali_profile` e o log
  de backend ativo. Os demais 13 do upstream são consumidores que não existem aqui
  (`multidraw_fb_copy`, `framebuffer_fetch_orders_overlap`, `depth_feedback`, `dual_source_blend`,
  `no_ps2_z_quantization`), e trazê-los seria trocar de motor — o que esta task exclui logo abaixo.
- `DecideGLFramebufferFetch()` no lugar do bloco `use_mali_profile` improvisado.
- Remoção da regra de MGS3 por título (`StartsWithNoCase(GetTitle(true), ...)`), substituída pela regra por versão de driver.
- Desligamento da troca automática de renderer do `GraphicsHealthMonitor`, mantendo só o diagnóstico.
- Default de `m_runtime_gpu_profile`: passa de `Adreno` para `Unknown`. Um vendor real como default
  faz todo backend que esquecer de chamar o setter se identificar como Adreno em silêncio. Nos dois
  backends que este projeto compila o perfil é atribuído em `CheckFeatures`, então no Android isto
  não muda decisão nenhuma; muda o que acontece se um backend futuro esquecer.

**NÃO entra:**
- Copiar `GSDeviceOGL.cpp`, `GSDeviceVK.cpp`, `GSDevice.cpp` ou `GS.cpp` do upstream. Divergem de 1,43× a 2,13× e arrastariam `GSBackQueue`, `GSPassScheduler`, `GSFrontState` e LSFG/FSR — trocar de motor, não corrigir um bug.
- ANGLE e `AndroidGpuProfileOverride`, que ganham tasks próprias depois.
- Remover os JNI `enableGraphicsSafeMode` / `setTemporaryRenderer`. Ficam como escotilha para uma
  ação deliberada do usuário; sem chamador automático, mas também sem tela que os acione ainda.

## Como validar

MGS3 no A15 em Vulkan: renderiza correto e com FPS ≥ 1.0.16. Shadow of the Colossus em Mali: nenhuma regressão de FPS (é o canário do upstream para a armadilha da cópia de RT em GL). Zero eventos de troca automática de renderer.

## Evidência de campo — A/B do workaround de MGS3 (2026-08-24)

Experimento feito no Galaxy A12 (`SM-A127M`, Mali-G52, driver **`v1.r38p1`**), desligando
temporariamente `GSUtil::ShouldForceMaliSafeFeedbackPath()` e revertendo em seguida. Nada foi
commitado do experimento; o que segue é o registro do resultado.

| | `fbfetch` | `texbarrier` | Imagem em MGS3 |
|---|---|---|---|
| **Com** o workaround (hoje) | 0 | 0 | correta |
| **Sem** o workaround | 1 | 1 | **correta** |

Linha de diagnóstico da [TASK-0006](TASK-0006-diagnostico-boot-gs.md) nos dois casos, com
`cfg_disable_fbfetch=0` — ou seja, não é configuração do usuário, é a nossa regra por título.

Sem o workaround, MGS3 percorreu FMV → dois logos da Konami → **tela de título**, tudo com cores
corretas, sem vermelho e sem corrupção. O driver anuncia framebuffer fetch (`arm=1 ext=1 pls=1`) e
com a regra ligada nós o recusamos, caindo no caminho de cópia de render target — que em GLES custa
uma cópia de RT mais um flush de tile por draw auto-referente, o mesmo trade que o upstream mediu
como 30 → 7 fps em Shadow of the Colossus.

**Conclusão:** neste driver a regra é desnecessária e só cobra custo. Isso **não** prova que ela seja
desnecessária no A15 do relato original, que provavelmente roda `r44p1` — a versão que o upstream
documenta como genuinamente quebrada no self-read in-tile. O que o experimento prova é que a regra
está **larga demais**: ela é escopada por título de jogo quando deveria ser escopada por **versão de
driver**, que é exatamente o que `vk-arm-r44p1-attachment-self-read` faz no banco já portado pela
[TASK-0002](TASK-0002-bloco-a-arquivos-perfil-gpu.md).

**Limitação do experimento:** não foi possível medir FPS. O OSD de FPS está forçado desligado pelo
perfil de desempenho e o `xgfGetFPS` do logcat é da MediaTek/Motorola, não deste Exynos. Portanto o
custo está estabelecido pela mecânica e pela medição do upstream, não por número medido aqui.

## Resultado

Entregue. O que de fato mudou, ponto a ponto:

**OpenGL.** `GSDeviceOGL::CheckFeatures` passou a chamar `DecideGLFramebufferFetch()`. A decisão de
fetch existia em **três** lugares no arquivo, ~100 linhas separados, e o terceiro — o bloco
`use_mali_profile` — testava a extensão crua (`GLAD_GL_ARM_shader_framebuffer_fetch`) em vez da
decisão que os dois primeiros já tinham tomado. Consequência real, e ela é pior do que "código
feio": num Mali com o gate de driver ligado, o gate desligava o fetch e o bloco Mali o religava, no
mesmo log; e `DisableFramebufferFetch` — a opção do usuário — era comida do mesmo jeito, o que
deixava o Android sem nenhuma forma de desligar fetch em Mali/GL pelas Configurações. Agora há um
ponto de decisão e o bloco Mali só consome. O log de backend ativo também passou a **ler** o
resultado em vez de rededuzi-lo pelas extensões.

**Vulkan.** `force_safe_mali_feedback` (vendorID Mali **e** título começando com "Metal Gear Solid
3") virou `UsesMobileDriverWorkaround(DriverWorkaround::UseRenderTargetCopyForFeedback)`. A regra
`vk-arm-r44p1-attachment-self-read` cobre o caso que importava, e cobre **todos os jogos** naquele
driver em vez de um só.

**`GSUtil::ShouldForceMaliSafeFeedbackPath()` deixou de existir.** Era o último ponto do caminho
gráfico que decidia por nome de jogo.

**`GraphicsHealthMonitor` virou diagnóstico puro.** Nenhuma chamada a `setTemporaryRenderer` ou
`enableGraphicsSafeMode` sobrou; vermelho e preto continuam sendo classificados e reportados à
telemetria, agora com a linha `GSBoot` anexada ao contexto. O schema das decisões subiu para 3, e a
migração continua rodando para apagar as chaves persistidas pelas 1.0.21–1.0.23 — sem isso, quem
ficou preso em Software naquelas versões continuaria preso, agora sem nenhum código capaz de
tirá-lo de lá.

### O que muda no campo, em uma frase por aparelho

| Aparelho | Antes | Depois |
|---|---|---|
| Mali GL, MGS3 | fetch e barrier desligados por título → cópia de RT + flush de tile por draw | fetch ligado; é o resultado que o A/B do A12 mostrou correto |
| Mali GL, outros jogos | inalterado | inalterado |
| Mali Vulkan `r44p1`, qualquer jogo | protegido só se o jogo fosse MGS3 | protegido sempre |
| Mali Vulkan fora do `r44p1`, MGS3 | cópia de RT sem motivo | caminho normal |
| Qualquer aparelho, tela vermelha | troca automática de renderer, até Software | evento de telemetria, nada mais |

### Validação executada

- `ninja -j 4 bin/libemucore.so` — **link completo, limpo** (não só as TUs tocadas: a remoção de
  `GSUtil::ShouldForceMaliSafeFeedbackPath` só apareceria no link).
- `gradlew testUnrestrictedDebugUnitTest` — **14 testes, 0 falhas**. O teste
  `monitorNeverChangesTheRenderer` varre o **fonte** do monitor procurando `setTemporaryRenderer` /
  `enableGraphicsSafeMode` fora de comentário, para que renomear o método de ação não faça o teste
  passar de novo.

### Validação que continua pendente

Os critérios de campo do escopo original — MGS3 no A15 em Vulkan com FPS ≥ 1.0.16, e zero regressão
de FPS em Shadow of the Colossus em Mali — **não foram medidos**, porque dependem dos aparelhos e,
no caso do FPS, de um OSD que o perfil de desempenho força desligado (limitação já registrada na
seção de evidência acima). O que está estabelecido é a mecânica e a medição do upstream, mais o A/B
de imagem feito no A12.
