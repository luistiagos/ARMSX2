# TASK-0064: devolver o controle do piso de Z do PS2, que o Mali no Vulkan tira sem volta

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta](../bugs/open/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0064:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Relato de campo em 2026-08-31: 007: Everything or Nothing com linhas verticais no Galaxy A12. É o
terceiro defeito seguido no mesmo aparelho, e os três se encadeiam:

| # | sintoma | o que foi feito |
|---|---|---|
| 1 | jogo extremamente lento | trilha de CPU (`CNTFRQ_EL0`, MTVU, GOS) — [TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) |
| 2 | tela preta | regra `gl-arm-g52-r38-auto-vulkan`: o `auto` do aparelho passou de OpenGL para Vulkan |
| 3 | linhas verticais | *este* — aparece só no backend para onde o nº 2 mandou o aparelho |

A/B executado no aparelho: forçando OpenGL à mão, **as linhas somem e a tela preta volta**. Upscale
está em **1x nativo**, o que descarta o artefato clássico de costura de sprite em upscaling.

## O defeito, em uma linha

Trocar de renderizador em Mali não troca só o backend — troca **como a profundidade é emulada** —
e a chave que deveria permitir desfazer isso não faz nada.

```cpp
// GSDeviceOGL.cpp:1046  -> Mali mantém o piso
m_features.no_ps2_z_quantization = GSConfig.DisablePS2DepthQuantization || vendor_id_apple;

// GSDeviceVK.cpp:3877   -> Mali perde o piso, e o `||` não deixa voltar
m_features.no_ps2_z_quantization =
    GSConfig.DisablePS2DepthQuantization || IsDeviceMali() || IsDeviceAppleGPU();
```

O comentário do lado Vulkan promete *"opt-out via INI for Z-precision-sensitive titles"*. Não
existe: `DisablePS2DepthQuantization` só empurra o valor para `true`. Diagnóstico completo no
[registro do bug](../bugs/open/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md).

## Objetivo

Que o padrão de cada dispositivo continue **exatamente** o de hoje, e que exista uma forma de
forçar o piso de volta — no arquivo de configuração e na tela, por jogo.

Não é uma mudança de renderização: com o toggle desligado, o binário decide igual ao de hoje em
todo aparelho.

## Escopo

**Entra:**

- `pcsx2/Config.h` — nova chave `ForcePS2DepthQuantization` no mesmo bitfield, ao lado de
  `DisablePS2DepthQuantization`. O par `Disable`/`Force` é a forma que este código já usa para
  exatamente este problema (`DisableFramebufferFetch` / `EnableAdrenoFramebufferFetch` /
  `ForceMaliFramebufferFetch`), então não se inventa convenção nova.
- `pcsx2/Pcsx2Config.cpp` — default `false`, leitura/escrita no INI, entrada em
  `RestartOptionsAreEqual` e na lista de chaves que exigem reinício (a decisão é tomada em
  `CheckFeatures`, na criação do device).
- Os **três** backends que decidem isso hoje — Vulkan, OpenGL e Metal — passam a ler a chave. Só o
  Vulkan tem o caso Mali, mas Apple GPU tem o mesmo formato nos três e a mesma falta de saída.
- `platforms/android/.../config/Settings.kt` — campo `forcePs2DepthQuantization`, por jogo, com
  leitura do INI, escrita, igualdade, persistência JSON e override por jogo.
- `platforms/android/.../ui/settings/RendererTab.kt` — toggle.
- `I18n.kt` (inglês, fonte da verdade) + `i18n/pt-BR.json`. As outras línguas caem para o inglês
  sozinhas, que é o comportamento declarado do `I18n`.

**Não entra, e é deliberado:**

- **Mudar o padrão de qualquer aparelho.** O piso continua desligado em Mali/Apple por default. Se
  a medição de campo disser que ligá-lo é o certo, isso é outra task e outra decisão — com o número
  do A/B na mão, não com a suposição desta.
- **Mexer na regra `gl-arm-g52-r38-auto-vulkan`.** Ela é o defeito nº 2 do encadeamento e precisa do
  seu próprio registro e do seu próprio A/B. Tirá-la agora devolve a tela preta.
- ~~**Ligar os `DRIVER_*` mortos**~~ — **esta linha estava errada e fica registrada em vez de
  apagada.** Eu afirmei que `DRIVER_SCALARIZE_VECTOR_BITWISE_AND` e os outros três eram emitidos no
  header do shader e nenhum shader os lia. Não é verdade: os helpers (`gpu_bitwise_and`,
  `gpu_boolean_not`, `gpu_bitwise_not`) são emitidos como GLSL **inline pelo próprio C++**
  ([GSDeviceOGL.cpp:2206-2261](../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L2206-L2261),
  [GSDeviceVK.cpp:5300-5330](../../pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L5300-L5330)), e os
  shaders os chamam em **39 lugares** (`convert.glsl` 8+8, `tfx_fs.glsl` 14, `tfx.glsl` 9). O
  workaround está ligado e funcionando.

  A conclusão errada veio de um `grep` em `bin/resources/shaders/` — o lugar errado — sem abrir a
  função que monta o header. É exatamente o que o `CLAUDE.md` proíbe: *"`grep` que mostra a linha
  não é verificação"*.
- **Reimplementar o watchdog de frame apresentado** (`auto_renderer_boot.tmp`), que a linha anterior
  tinha e o fork aposentou sem substituto.

## Como será validado

1. **Compila** — objetos isolados na árvore ninja do AGP: `Pcsx2Config.cpp.o`, `GSDeviceOGL.cpp.o`,
   `GSDeviceVK.cpp.o`; e `:app:compileGithubDebugKotlin` para o lado Kotlin.
2. **Default inalterado** — com `ForcePS2DepthQuantization=false`, a linha de log do device
   (`GSDeviceVK.cpp:3913`, que já imprime `no_ps2_z_quantization`) sai igual à de antes no mesmo
   aparelho.
3. **No aparelho, o A/B que hoje é impossível** — Galaxy A12, renderizador Vulkan, 007 Everything or
   Nothing, upscale 1x:
   - toggle **off**: as linhas estão lá (estado atual);
   - toggle **on** + reiniciar o jogo: as linhas somem ou não.

   **As duas respostas são resultado.** Se sumirem, a causa está nomeada e a discussão passa a ser
   qual deve ser o default em Mali. Se não sumirem, uma hipótese cara caiu por um toggle em vez de
   por um ciclo de APK — e a próxima suspeita é a lista de diferenças GL↔Vulkan do registro do bug.

## Contexto que quem pegar isto precisa ter

[`docs/plano-grafico-mali-convergencia-upstream.md`](../plano-grafico-mali-convergencia-upstream.md),
na seção *"O que explicitamente NÃO fazer"*, diz:

> Trocar OpenGL ↔ Vulkan globalmente como "correção". Já foi feito nos dois sentidos (1.0.17 e
> 1.0.20) e os dois falharam; ambos os backends têm caminho de feedback dependente de driver.

A regra `gl-arm-g52-r38-auto-vulkan` é a terceira vez, e entrou no commit `bf45520833` — assunto
`*`, **sem task e sem registro de bug**. Esta task não conserta isso; registra que está por
consertar, para que a próxima pessoa não descubra sozinha.

Vale também o que o `CLAUDE.md` manda: a parte em `pcsx2/` é **correção de motor**, e nasce como
contribuição ao upstream. Ela foi escrita nessa forma — genérica, sem nada de Android, sem mudar
default nenhum.
