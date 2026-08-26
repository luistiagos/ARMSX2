# Bug: Mali — tela vermelha silenciosa e page fault no driver durante renderização

- **Detectado em:** 2026-08-21 07:39 (telemetria de produção) + relato de cliente em 2026-08-22
- **Origem:** telemetria `armsx2/native` (`native::abort+156`) e relato de Metal Gear Solid 3
- **Errors (serviço):** 1607 (1 ocorrência decodificada); 1606, 1608, 1609, 1610 e 1611 são crashes
  do mesmo Galaxy A17 na mesma janela, mas ficaram sem backtrace por falha do decoder
- **Classe:** crash / investigação de corrupção gráfica silenciosa
- **Reincidência:** primeira assinatura decodificada dentro de `libGLES_mali.so`
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0003](../../task/TASK-0003-bloco-b1-shader-cache-driver.md), [TASK-0005](../../task/TASK-0005-bloco-c-pontos-de-consumo.md)

## Sintoma

Há dois sinais relacionados, mas ainda não equivalentes:

1. Um cliente relata Metal Gear Solid 3 com a área do jogo totalmente vermelha no Samsung Galaxy
   A15. O overlay Android continua normal. OpenGL anteriormente dava tela preta; após correção do
   boot dos assets, OpenGL e Vulkan passaram a exibir a tela vermelha. Não ocorre no Motorola G86.
2. O error **1607**, num Galaxy A17 (`SM-A175F`, Android 16, driver MediaTek `mt6789`), registra
   `SIGABRT` causado por uma page fault dentro do driver Mali:

```text
Abort message: 'Unhandled page fault: sig=11 pc=0x78daf27190 addr=0x64 write=0'
#04 pc 00000000008fb18c /vendor/lib64/egl/mt6789/libGLES_mali.so
#05 pc 000000000071d008 libemucore.so
#06 pc 0000000000709bb0 libemucore.so
...
```

O build-id do `libemucore.so` é `fef266ccc27f14fd95fa2085d9a6d820917dee63`, idêntico ao APK
1.0.17 versionado. Entre 07:39:03 e 07:47:45 esse aparelho gerou os errors 1606–1611; somente 1607
foi decodificado. Isso confirma instabilidade real no caminho de renderização Mali, mas **não
prova que o evento é Metal Gear Solid 3 nem que produziu tela vermelha**: a telemetria não envia
serial do jogo, renderer efetivamente aberto ou amostra do framebuffer. O aparelho também é A17,
não o A15 do relato.

## Causa raiz

Ainda não confirmada para a tela vermelha. O código confirma três lacunas relevantes:

1. O perfil Mali reativa framebuffer fetch em
   [`GSDeviceOGL.cpp:825`](../../../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L825)
   depois de `DisableFramebufferFetch` ter sido aplicado nas linhas 783–789. Portanto
   `DisableFramebufferFetch=true` **não desabilita de fato** o recurso em Mali.
2. Vulkan considera texture barrier habilitada sempre que `OverrideTextureBarriers != 0`
   ([`GSDeviceVK.cpp:2743-2746`](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2743)).
   O caminho de cópia de render target só entra quando a feature fica desabilitada
   ([`GSDeviceVK.cpp:6092-6105`](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L6092)).
   Assim, apenas trocar OpenGL por Vulkan não garante o caminho seguro por cópia.
3. O safe mode atual só detecta crash/boot sem frames do renderer automático. Uma imagem vermelha
   continua apresentando frames, então o marcador é aposentado e nenhum fallback é acionado.

O candidato principal é corrupção de feedback loop/framebuffer fetch ou texture barrier no driver
Mali. A page fault de 1607 sustenta a família de causa (driver/renderização), não o sintoma exato.

## Como reproduzir

1. Galaxy A15 Mali, abrir Metal Gear Solid 3 com OpenGL e depois Vulkan.
2. Registrar para cada tentativa: modelo exato, `Build.FINGERPRINT`, renderer solicitado e
   renderer efetivamente aberto, vendor/renderer/version do driver, serial/CRC do jogo e flags
   `framebuffer_fetch`/`texture_barrier` finais.
3. Testar, em builds diagnósticos separados:
   - Software;
   - OpenGL com framebuffer fetch e texture barriers realmente desabilitados;
   - Vulkan com `OverrideTextureBarriers=0`, forçando o clone/copy do render target.

Se Software renderizar corretamente e um dos modos por cópia corrigir a imagem, o defeito fica
isolado no caminho de feedback do driver.

## Próximos passos

1. Corrigir a precedência do perfil Mali: respeitar `DisableFramebufferFetch` também no bloco das
   otimizações Mali e calcular `texture_barrier` somente depois da decisão final de fetch.
2. Adicionar ao log de boot do GS um evento compacto com jogo, GPU/driver, API solicitada/efetiva e
   features finais. Sem isso, crashes e corrupção silenciosa não podem ser correlacionados.
3. Implementar uma tabela de workarounds por fingerprint do driver/GPU, com opção por jogo. Para a
   família problemática, preferir desabilitar feedback/barriers e usar cópia do RT; não trocar a
   API globalmente para todos os aparelhos.
4. Para detectar corrupção silenciosa, amostrar poucos pixels do frame apresentado em baixa
   frequência e só declarar falha após vários frames quase uniformes em vermelho enquanto o GS
   continua emitindo draws. Antes de persistir o fallback, repetir a validação com o caminho por
   cópia. O estado deve ser chaveado por `GPU + driver + jogo + renderer`, nunca global.
5. Não usar a troca OpenGL ↔ Vulkan isoladamente como correção: ambos os backends possuem caminhos
   de feedback dependentes do driver e o relato já reproduziu nos dois.

## Correção implementada — 2026-08-22

- `GSDeviceOGL` deixou de reativar `GL_ARM_shader_framebuffer_fetch` no perfil Mali depois de
  `DisableFramebufferFetch`; a precedência da configuração agora é definitiva.
- Metal Gear Solid 3 em GPU Mali força cópia segura do render target, sem framebuffer fetch nem
  texture barriers, tanto no OpenGL quanto no Vulkan. A regra usa o título do GameDB e não altera
  outros jogos ou outros fabricantes.
- `GraphicsHealthMonitor` passou a amostrar uma imagem 32×32 do `SurfaceView` a cada 3 segundos.
  Quatro amostras consecutivas dominadas por vermelho/preto, com VM e FPS ativos, acionam primeiro
  o modo por cópia. O fallback só é persistido depois de duas amostras saudáveis.
- Se o modo por cópia continuar vermelho, o renderer Software é tentado temporariamente e também
  só é persistido após validação visual.
- A persistência é chaveada por fingerprint/hardware do driver + serial/CRC do jogo + renderer
  efetivo. Nenhum fallback vira configuração global.
- Cada transição é enviada como `armsx2/graphics`, incluindo aparelho, fingerprint, jogo e renderer.

**Validação local:** `assembleUnrestrictedDebug` concluído com sucesso. **Status:** aguardando reteste
no Galaxy A15 com MGS3 e observação da telemetria do fallback.

## Substituição da correção de 2026-08-22 — 2026-08-25 (TASK-0005)

Dois dos quatro itens acima foram **desfeitos de propósito** pela
[TASK-0005](../../task/TASK-0005-bloco-c-pontos-de-consumo.md), e o registro precisa dizer por quê,
porque cada um deles parecia uma correção quando foi escrito.

**1. A regra de MGS3 por título saiu.** Ela dizia: "GPU Mali **e** título começando com Metal Gear
Solid 3 → cópia segura do render target". O A/B feito no Galaxy A12 (`SM-A127M`, Mali-G52, driver
`v1.r38p1`) em 2026-08-24 mostrou MGS3 percorrendo FMV → logos → tela de título **com cores
corretas, sem vermelho e sem corrupção**, com `fbfetch=1 texbarrier=1`, ou seja, com a regra
desligada. O driver anuncia framebuffer fetch (`arm=1 ext=1 pls=1`) e a regra o recusava mesmo
assim.

Portanto a regra estava **larga demais**: escopada por jogo quando o defeito é do driver. No lugar
dela entrou `DriverWorkaround::UseRenderTargetCopyForFeedback`, lido do banco de regras. Efeito
prático: o Mali `r44p1` em Vulkan — onde a leitura in-tile não corrompe, **perde o device**
(`VK_ERROR_DEVICE_LOST` em `vkWaitForFences`) — passa a ser protegido em **todos os jogos**, e não
só em MGS3; e todo driver que faz a leitura corretamente para de pagar o custo da cópia.

**2. A troca automática de renderer do `GraphicsHealthMonitor` saiu inteira.** O item que dizia "se
o modo por cópia continuar vermelho, o renderer Software é tentado" foi removido. A mesma heurística
já tinha produzido 38 falsas trocas em cenas escuras
([graphicshealthmonitor-falso-positivo-cenas-escuras](./graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md)),
e o caminho de ação chega em `GSUpdateConfig` → `GSreopen` → `pxFailRel` → `abort()` numa falha
dupla. A classificação de vermelho e o evento `armsx2/graphics` continuam; a ação não.

**O que continua valendo do documento acima:** a análise da causa (feedback de render target
dependente do driver) e o item 1 da correção — `GSDeviceOGL` não reativa mais o fetch depois de
`DisableFramebufferFetch`. Esse ponto, aliás, ficou mais forte: a decisão agora é tomada uma única
vez, por `DecideGLFramebufferFetch()`, em vez de três vezes ao longo do arquivo.

**Status:** continua aberto. O reteste no A15 segue sendo a evidência que falta, e agora ele tem uma
pergunta mais precisa a responder — qual versão de driver o aparelho reporta na linha `GSBoot`
(campo `drv_ver`), porque é isso que decide se existe regra a acrescentar ao banco.
