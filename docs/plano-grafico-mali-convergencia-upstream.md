# Plano: sair do ciclo de correções gráficas em Samsung A07/A15 (Mali)

- **Data:** 2026-08-24
- **Motivação:** quatro rodadas de correção (1.0.17 → 1.0.22) em tela preta / tela vermelha /
  falso positivo do monitor visual, cada uma trocando um sintoma por outro. Relato mais recente:
  *"Agora o jogo nem abre mais. Aparece uma tela branca e fecha o app."*
- **Escopo:** decisão de renderer, features de blending (framebuffer fetch / texture barrier),
  cache de shader e diagnóstico gráfico no Android.
- **Bugs relacionados:** [tela preta A07](bugs/open/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md),
  [tela vermelha + page fault Mali](bugs/open/gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md),
  [falso positivo do GraphicsHealthMonitor](bugs/open/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md)

---

## 1. Por que estamos em círculo

Não é falta de esforço nem azar. As quatro rodadas falharam pela mesma razão estrutural: **todas as
nossas decisões gráficas são tomadas a partir do nome do GPU ou do nome do jogo, nunca a partir da
identidade do driver.** Como o defeito real é do driver (versão específica do blob Mali), qualquer
regra baseada em nome acerta um aparelho e erra outro — e o "erra outro" só aparece dias depois,
como um sintoma novo.

Estado atual do nosso código:

| Decisão | Como decidimos hoje | Onde isso quebra |
|---|---|---|
| Vulkan ou OpenGL no automático | `IsAllowlistedAndroidVulkanGPU(name)` — comparação de substring no **nome** do GPU ([GSDeviceVK.cpp](../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp)) | Mesmo GPU, blobs diferentes. Um Mali‑G57 com driver r38 e outro com r44p1 recebem a mesma decisão. |
| Framebuffer fetch / texture barrier | Bloco `use_mali_profile` escrito à mão em `GSDeviceOGL::CheckFeatures`, em três pontos separados | Foi exatamente aqui que achamos, em 21/08, o bloco Mali reativando fetch **depois** de `DisableFramebufferFetch`. |
| Caminho seguro de MGS3 | `StringUtil::StartsWithNoCase(VMManager::GetTitle(true), "Metal Gear Solid 3")` ([GSUtil.cpp](../app/src/main/cpp/pcsx2/GS/GSUtil.cpp)) | Aplica a todo Mali, com qualquer driver, inclusive os que renderizam MGS3 corretamente — e cobra o custo de cópia de RT deles. |
| Corrupção silenciosa | `GraphicsHealthMonitor` amostrando 32×32 pixels do `SurfaceView` e trocando renderer em runtime | Já produziu 38 eventos de falso positivo em 6 modelos e 15 jogos (1.0.21). E a troca em runtime tem caminho de `abort()` — ver §2.3. |
| Cache de shader OpenGL | `cache/gl_programs.idx` chaveado **só** por `SHADER_CACHE_VERSION` + hash do fonte | **Nenhuma identidade de driver.** Ver §2.1 — é o achado central deste documento. |

O resultado é que não temos um lugar único onde a decisão gráfica é tomada, então nem nós nem um
teste conseguimos olhar e dizer o que o aparelho vai fazer. Cada correção vira mais uma condição
espalhada, e a interação entre elas é o que produz o sintoma seguinte.

---

## 2. Causas concretas encontradas nesta análise

### 2.1. O cache de programas OpenGL não é invalidado quando o driver muda — causa provável da tela branca

`GLShaderCache` grava binários de programa compilados em `<DataRoot>/cache/gl_programs.bin`. O
índice carrega apenas a versão do formato e os hashes do código-fonte
([GLShaderCache.cpp:113](../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GLShaderCache.cpp#L113),
[:153](../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GLShaderCache.cpp#L153)):

```cpp
const u32 file_version = SHADER_CACHE_VERSION;   // 67
// ... e nada mais. Nem GL_VENDOR, nem GL_RENDERER, nem GL_VERSION.
```

Binário de programa GL **só é válido para o driver exato que o produziu**. Entregar bytes de outro
driver para `glProgramBinary()` é comportamento indefinido: alguns drivers retornam erro (nós
tratamos — "Failed to create program from binary... Recreating cache"), outros aceitam e **quebram
no primeiro draw**.

O upstream já corrigiu isso, e o comentário deles nomeia o sintoma exato:

> *"Program binaries are only valid for the exact driver that produced them; if the cache was
> written by a different driver (e.g. the device's native GLES driver, then the user switched the
> renderer to ANGLE which reports a [different one]) ... glProgramBinary() **and crashes some
> drivers on the first cached draw**."*
> — `GLShaderCache.cpp` em `ARMSX2/ARMSX2@master`

Por que isso encaixa com o nosso caso, ponto a ponto:

- **É persistente, não intermitente.** O cache está em disco no data root do usuário e sobrevive a
  atualização do app. Uma vez envenenado, falha em toda abertura — "o jogo *nem abre mais*".
- **É específico de Samsung A-series.** Samsung entrega atualização do blob Mali por OTA (o próprio
  A07 do relato aparece na telemetria em Android 15 e depois Android 16). O Moto G86 do escritório
  não recebe essas trocas, então **nunca reproduz**.
- **Nós mesmos provocamos trocas de driver.** A 1.0.20 devolveu todo Mali para OpenGL; a 1.0.21/22
  troca renderer em runtime pelo `GraphicsHealthMonitor`; e o app tem
  `GpuDriverManagerActivity` + `setCustomDriverPath`, que deixam o usuário instalar outro blob Mali.
  Nenhum desses caminhos invalida `gl_programs.bin`.
- **Explica a sequência preto → vermelho → fecha.** Programa corrompido pode não desenhar nada
  (preto), desenhar errado (vermelho) ou matar o driver (page fault dentro de `libGLES_mali.so`,
  que é literalmente o backtrace do error **1607**).
- **Só o OpenGL está exposto.** O lado Vulkan valida `pipelineCacheUUID` + `vendorID` +
  `driverVersion` no header do pipeline cache
  ([VKShaderCache.cpp:74–103](../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/VKShaderCache.cpp#L74)).
  Ou seja: ao mandar Mali de volta para OpenGL em 1.0.20, movemos toda a base Samsung para o único
  backend sem validação de driver.

> Isto é uma hipótese forte, com mecanismo verificado no código — **não é confirmação**. O teste que
> decide está em §4, passo 0, e custa um comando de `adb`.

### 2.2. `isNativeInitializationSucceeded()` existe e ninguém consulta

A inicialização nativa passou para uma thread em `App.onCreate()`, com um `catch (Throwable)` que
marca `sNativeInitComplete = true` **mesmo quando falha**
([App.java](../app/src/main/java/kr/co/iefriends/pcsx2/App.java)). `MainActivity.onCreate` só checa
`isNativeInitializationComplete()`; `isNativeInitializationSucceeded()` não é lido em lugar nenhum
do projeto. Se `System.loadLibrary` falhar, o boot segue e o primeiro `NativeApp.*` lança
`UnsatisfiedLinkError` — tela em branco e processo morto, sem diálogo.

Além disso `BootSplashActivity` é `singleTop` e **não sobrescreve `onNewIntent`**, então o
`EXTRA_TARGET_INTENT` que `MainActivity` envia é ignorado quando a splash já está no topo: o usuário
volta para a HomeActivity em vez de abrir o jogo.

### 2.3. O "modo seguro" alcança automaticamente um caminho de `abort()`

`NativeApp.setTemporaryRenderer()` e `NativeApp.enableGraphicsSafeMode()` alteram `Renderer`,
`DisableFramebufferFetch` e `OverrideTextureBarriers`. Os três estão em `RestartOptionsAreEqual`
([Pcsx2Config.cpp:895](../app/src/main/cpp/pcsx2/Pcsx2Config.cpp#L895)), então `MTGS::ApplySettings()`
cai em `GSUpdateConfig` → `GSreopen(true, ...)` e:

```cpp
if (!GSreopen(true, true, GSConfig.Renderer, &old_config))
    pxFailRel("Failed to do full GS reopen");   // → abort(), processo morre
```
([GS.cpp:785–789](../app/src/main/cpp/pcsx2/GS/GS.cpp#L785))

**Correção de escopo (verificada em 24/08):** esse `pxFailRel` **não é nosso** — é código herdado do
PCSX2 e está idêntico no upstream (`GS.cpp:1054`). O `GSreopen` também já tenta reverter para a
configuração antiga antes de desistir, e o `pxFailRel` interno só dispara em falha dupla: a nova
configuração falha **e** a antiga também. Portanto o `abort()` não é uma gambiarra nossa.

O que **é** nosso é o **chamador automático**. Upstream só chega nesse caminho quando o usuário troca
uma configuração de propósito; nós passamos a chegar nele sozinhos, a partir de uma heurística de
pixel, em aparelhos cujo driver já está instável. Um mecanismo de recuperação que roda sem o usuário
pedir e cujo pior caso é `abort()` sem diálogo e sem telemetria é risco assimétrico — por isso a
Prioridade 1 desliga o chamador, e não o `pxFailRel`.

### 2.4. A regra de MGS3 é a que o upstream removeu de propósito

Nós forçamos cópia segura de render target para MGS3 em qualquer Mali, em OpenGL **e** Vulkan. O
upstream teve exatamente essa regra no GL, mediu o custo e **retirou**:

> *"on GLES — where fetch and the texture barrier are one capability — every self-referential draw
> became an RT copy plus a tile flush: **Shadow of the Colossus went 30 → 7 fps** on an Anbernic
> RG 477V and users downgraded to 2.6.6.4 en masse. The fetch path DOES corrupt some content on this
> blob (**MGS3 was the observed case**) ... but Vulkan — where the RT-copy path costs an ordinary
> image copy instead of a tile flush — remains the correct-rendering choice for the affected games."*
> — `GSGPUDriverProfile.cpp` em `ARMSX2/ARMSX2@master`

Duas conclusões diretas: (a) o defeito de MGS3 é do **driver**, não do jogo, e o upstream já sabe
qual (`r44p1`); (b) a correção certa é **Vulkan com cópia de RT**, não OpenGL com cópia de RT — que
é o que estamos fazendo e é o caminho caro.

---

## 3. O que o upstream construiu enquanto nós improvisávamos

Pesquisa no `ARMSX2/ARMSX2` (master `dbd7be271c`, agosto/2026). Eles resolveram esta classe inteira
de problema com infraestrutura, não com regras pontuais:

| Componente upstream | O que faz | Nosso equivalente |
|---|---|---|
| `GS/Renderers/Common/GSGPUProfile.{h,cpp}` + `GSGPUProfileMali.cpp` / `Adreno` / `PowerVR` | Resolve GL_VENDOR/GL_RENDERER/GL_VERSION (ou `VkPhysicalDeviceProperties`) em **modelo + arquitetura + driver + versão** (`r44p1`, `build 1.9@4850625`, …) | não existe |
| `GSGPUDriverProfile.cpp` | **Banco de bugs de driver**: 27 regras com faixa de versão, `DriverBug`/`DriverWorkaround` em bitmask e nível de confiança | não existe |
| `GSFramebufferFetchPolicy.h` | A decisão de framebuffer fetch como **uma função `constexpr` pura**, com testes unitários | 3 pontos imperativos espalhados em `CheckFeatures` |
| `GSUtil::AndroidAutoPrefersVulkan()` | Escolhe Vulkan/OpenGL no automático **consultando o banco de drivers**, e loga a decisão inteira | allowlist por nome de GPU |
| Assinatura de driver no `GLShaderCache` | FNV‑1a de vendor+renderer+version+formatos de binário no índice; muda o driver, invalida o cache | **ausente** (§2.1) |
| Renderer **ANGLE** (GLES sobre Vulkan) | Terceiro backend, com driver previsível, quando o blob GLES do fabricante é ruim | não existe |
| `GSConfig.AndroidGpuProfileOverride` | Usuário força perfil Mali/Adreno/PowerVR/Xclipse | não existe |

Regras do banco que atingem **exatamente os nossos aparelhos**:

- `gl-arm-g57-fifo` — Mali‑**G57** (o GPU do Galaxy A07) tem `BrokenVSync`; workaround `ForceFifoPresent`.
- `vk-arm-r44p1-attachment-self-read` — r44p1 perde o device (`VK_ERROR_DEVICE_LOST`) no self-read
  in-tile; workaround `UseRenderTargetCopyForFeedback`. É a versão correta, escopada por driver, do
  que nós fizemos escopado por título de jogo.
- `vk-arm-proprietary` — `BrokenPushDescriptors`, `BrokenAttachmentFeedbackLoopLayout`,
  `SlowCachedReadbackMemory` em todo blob ARM.

E o comentário deles sobre o crash Mali-G77 sob ANGLE fecha o argumento de §2.1:

> *"The Mali-G77 crash under ANGLE was NOT these hacks but **stale program binaries** from the native
> driver being fed to ANGLE's glProgramBinary(); that is fixed at the source in GLShaderCache
> (driver-keyed cache)."*

### O que NÃO devemos perder no port

Upstream não é melhor em tudo. Verificado no código deles em 24/08:

- **`Host::ReportErrorAsync` no Android do upstream ainda é só log** (`native-lib.cpp:3405`), exatamente
  como o nosso era antes de 20/08. O diálogo via JNI que adicionamos é **nosso e está à frente do
  upstream** — manter no port, não substituir pelo deles.
- **Upstream também tem regra por jogo.** `ApplyAndroidGameDBOverrides()` força `HalfPixelOffset Off`
  para Tekken 5. A diferença não é existir regra por jogo — é como ela é ancorada: perfil **resolvido**
  (`IsMaliGPUProfile()`), SoC (`IsMediaTekSoC()`), **lista de seriais** em vez de título em inglês, e
  `!GSConfig.ManualUserHacks` para respeitar o override do usuário. Nossa regra de MGS3 não tem
  nenhuma das quatro âncoras.
- **Nosso log estava desligado por decisão nossa**, não por falta do upstream: os dois chamam
  `Log::SetConsoleOutputLevel(LOGLEVEL_DEBUG)`, mas o nosso perfil de dispositivo depois forçava
  `EnableSystemConsole=false`. Já corrigido, e não pode voltar.

### O que confirma que a infraestrutura deles é sustentada, não aspiracional

- **Testes existem**: `tests/ctest/core/gs/gs_framebuffer_fetch_policy_tests.cpp` (15 KB) e
  `gs_gpu_driver_profile_tests.cpp` (6 KB) cobrem exatamente as duas políticas que queremos portar.
- **ANGLE é binário embarcado, não plano**: `platforms/android/app/src/main/jniLibs/arm64-v8a/`
  contém `libEGL_angle.so` e `libGLESv2_angle.so` (6,2 MB). A seleção é contida — a configuração
  `AndroidUseAngleOpenGL` faz o lado Java exportar `ARMSX2_ANGLE_EGL_LIBRARY`, e `GLContextEGL`
  carrega essa EGL no lugar da do sistema. Sem a variável, o caminho é inerte: risco de port baixo.

Também vale registrar o que **não** funciona, com evidência de campo do upstream: em [#331](https://github.com/ARMSX2/ARMSX2/issues/331)
o texto sumido do Gran Turismo 4 em Mali não era driver — era **patch de widescreen**. E em
[#380](https://github.com/ARMSX2/ARMSX2/issues/380) o mantenedor não tenta adivinhar: pede `adb
logcat` e faz teste dirigido no aparelho do usuário. Não existe atalho por heurística de pixel.

---

## 4. Plano

Princípio que passa a valer: **nenhuma decisão gráfica nova pode ser tomada a partir de nome de GPU
ou nome de jogo.** Ou vem do banco de drivers, ou é escolha explícita do usuário.

### Passo 0 — Provar ou derrubar a hipótese do cache (antes de escrever qualquer código)

Custa um comando e decide o resto do plano.

```powershell
# Com o aparelho afetado (A07/A15) reproduzindo "tela branca e fecha":
adb shell run-as come.nanodata.armsx2 ls -la files/cache/    # localizar gl_programs.*
adb shell rm -f <DataRoot>/cache/gl_programs.idx <DataRoot>/cache/gl_programs.bin
# abrir o mesmo jogo novamente
```

- **Se o jogo abrir** → §2.1 confirmado. Prioridade 1 vira urgente e vira hotfix imediato.
- **Se não abrir** → capturar `adb logcat -T 1 > armsx2-a07.txt` com o toggle de log ligado e
  triar antes de seguir. Nesse caso o suspeito passa a ser §2.2.

Enquanto isso, pedir ao cliente uma captura do rodapé da tela inicial (mostra `Versão 1.0.xx`) —
já tivemos um A07 reportando 1.0.16 enquanto a distribuição estava em 1.0.19.

### Prioridade 1 — Hotfix de contenção (dias, baixo risco, sem infraestrutura nova)

Objetivo: parar a sangria e devolver o aparelho a um estado abrível. Nada aqui inventa heurística.

1. **Chavear o `GLShaderCache` pelo driver.** Portar a assinatura do upstream: FNV‑1a de
   `GL_VENDOR` + `GL_RENDERER` + `GL_VERSION` + lista de formatos de binário, gravada no índice e
   comparada na leitura. Driver diferente → descarta e recompila. Portar como está, sem variação.
2. **Bump de `SHADER_CACHE_VERSION`** (67 → 68) para descartar de uma vez todo cache envenenado já
   instalado em campo. Sem isso, quem já está quebrado continua quebrado.
3. **Invalidar o cache também em troca de driver customizado** — `setCustomDriverPath` deve apagar
   `gl_programs.*` ao mudar de blob.
4. **Tirar o `abort()` do caminho de recuperação (§2.3).** `GSUpdateConfig` deve devolver falha para
   o host em vez de `pxFailRel`, e o host deve reverter para a configuração anterior e informar o
   usuário. Um modo seguro que mata o processo é pior que não ter modo seguro.
5. **Fechar §2.2:** `MainActivity` consulta `isNativeInitializationSucceeded()` e mostra diálogo em
   vez de seguir para o crash; `BootSplashActivity` ganha `onNewIntent`.
6. **Desligar a troca automática de renderer do `GraphicsHealthMonitor`,** inclusive o caminho de
   vermelho. Manter **apenas** a coleta diagnóstica (`armsx2/graphics`) e um botão manual
   "tentar modo compatível" nas Configurações. Motivo: sem identidade de driver, o monitor não tem
   como saber *para onde* trocar, e cada troca automática é mais uma chance de envenenar o cache e
   de cair no `abort()` acima. Volta a ser automático em Prioridade 3, aí sim consultando o banco.

### Prioridade 2 — Diagnóstico que fecha o ciclo (dias, sem risco de regressão)

Hoje, quando um usuário relata, não temos como saber o que o aparelho fez. Isso é o que torna cada
rodada um chute.

1. **Uma linha de log de boot do GS,** no modelo do upstream, sempre emitida e sempre enviada à
   telemetria: modelo, `Build.FINGERPRINT`, GPU, string de driver e versão, API pedida, API
   efetivamente aberta, serial/CRC do jogo, e os valores finais de `framebuffer_fetch` /
   `texture_barrier` / `DisableFramebufferFetch`.
2. **Anexar essa mesma linha a todo crash nativo** (`armsx2/native`), para que um tombstone dentro de
   `libGLES_mali.so` diga em qual configuração aconteceu. Hoje o error 1607 não permite nem afirmar
   qual jogo era.

Sem os itens 1 e 2, os passos de Prioridade 3 não são verificáveis e a gente volta ao mesmo lugar.

### Prioridade 3 — Convergir com o upstream

> **Achado de 24/08 que muda o custo desta etapa: o código já está no repositório.** O remote
> `upstream` está buscado localmente em `be72a8e1eb` (**18/08/2026**) e **todos** os arquivos
> necessários existem nesse ref — os 8 do sistema de perfil, os 2 de teste, a assinatura de driver do
> `GLShaderCache` e os binários do ANGLE. Nada precisa ser baixado; é `git checkout upstream/master -- <path>`.
>
> Isso significa que as rodadas **1.0.21 e 1.0.22 — as duas que pioraram o problema — foram escritas
> depois desse código já estar na árvore, sem uso.** O port foi pedido antes e ficou pela metade.

#### O port se divide em três blocos, com riscos muito diferentes

**Bloco A — copiável literalmente (~103 KB, 10 arquivos).** Autocontidos: só dependem de
`common/Pcsx2Defs.h`, da STL e de `sys/system_properties.h`. `GSFramebufferFetchPolicy.h` não tem
nem um `#include`. Zero acoplamento com a nossa árvore divergida.

| Arquivo | Tamanho |
|---|---|
| `GSGPUDriverProfile.cpp` (banco de 27 regras) | 24.647 |
| `GSFramebufferFetchPolicy.h` | 12.232 |
| `GSGPUProfile.cpp` / `.h` / `Private.h` | 15.130 / 8.030 / 1.226 |
| `GSGPUProfileMali.cpp` / `Adreno.cpp` / `PowerVR.cpp` | 9.765 / 8.636 / 1.030 |
| `gs_framebuffer_fetch_policy_tests.cpp` / `gs_gpu_driver_profile_tests.cpp` | 15.656 / 6.262 |

**Bloco B — enxerto pequeno e cirúrgico.** Não é copiar arquivo, é colar bloco:
- `GSDevice.h`: ~22 linhas de getters `__fi` (`SetRuntimeGPUProfile`, `IsMaliGPUProfile`,
  `UsesMobileDriverWorkaround`, `IsMediaTekSoC`, …) + 5 membros.
- `GLShaderCache`: a assinatura de driver são **6 linhas**. É a correção da §2.1.
- `CMakeLists` das duas árvores para os arquivos novos.

**Bloco C — NÃO copiável: os pontos de consumo.** É aqui que mora a divergência real:

| Arquivo | Nosso | Upstream | Fator |
|---|---|---|---|
| `GSDevice.cpp` | 41.653 | 88.656 | 2,13× |
| `GSDevice.h` | 35.417 | 73.206 | 2,07× |
| `GSDeviceOGL.cpp` | 105.029 | 155.290 | 1,48× |
| `GS.cpp` | 38.694 | 56.446 | 1,46× |
| `GSDeviceVK.cpp` | 245.158 | 349.768 | 1,43× |

Copiar esses arquivos arrastaria a evolução inteira da GS deles — `GSBackQueue`,
`GSPassScheduler`, `GSFrontState` (parse de GIF em pipeline), LSFG/FSR. Isso é **trocar de motor,
não corrigir um bug**, e é exatamente o tipo de mudança que produziu as quatro rodadas anteriores.

A boa notícia: a superfície real de consumo no `GSDeviceOGL` são **13 call-sites** dos getters de
perfil. Reescrever 13 pontos à mão contra a nossa versão do arquivo é trabalho de horas.

#### Por que a ordem A → B → C importa

Blocos A e B, sozinhos, já entregam as duas coisas de maior valor **sem alterar nenhuma decisão de
renderização**:
1. o cache chaveado por driver (a causa provável da tela branca), e
2. o resolvedor + banco ligados **apenas à linha de log** da Prioridade 2 — o aparelho passa a ser
   observável enquanto o comportamento gráfico continua idêntico ao de hoje.

Só depois, com log de campo real do A07 e do A15 batendo com o que o banco resolve, os 13 pontos de
consumo do Bloco C são ligados, um de cada vez.

#### Sequência de execução

Cada etapa entregável e testável sozinha:

1. `GSGPUProfile.{h,cpp}` + `GSGPUProfilePrivate.h` + os três `GSGPUProfile{Mali,Adreno,PowerVR}.cpp`.
   Puro parsing, sem efeito colateral — dá para portar e testar com unit test antes de ligar em nada.
2. `GSGPUDriverProfile.cpp` (o banco de 27 regras). Idem: entra desligado, só logando o que
   resolveu, até batermos o log com aparelhos reais.
3. `GSFramebufferFetchPolicy.h` + reescrita de `GSDeviceOGL::CheckFeatures` para consumi-la. **Aqui
   morre o nosso bloco `use_mali_profile` improvisado e a regra de MGS3 por título de jogo** — a
   substituta é a regra por versão de driver.
4. `GSUtil::AndroidAutoPrefersVulkan()` substituindo `IsAllowlistedAndroidVulkanGPU`. Aposentar
   também o `auto_renderer_boot.tmp`/`auto_renderer_no_vulkan.tmp`, que é a nossa versão cega do
   mesmo problema.
5. `GSConfig.AndroidGpuProfileOverride` exposto nas Configurações — a escotilha de emergência que
   hoje não temos.
6. **ANGLE.** É a resposta do upstream para blob de fabricante ruim, e é a única coisa no plano que
   dá ao usuário um driver *previsível*. Fica por último porque é a maior e depende de 1–3.

Ao portar, respeitar as decisões medidas do upstream — elas estão documentadas em comentário e
contradizem o que fizemos:
- **Não** reativar a regra GL de cópia de RT para r44p1 (custou 30 → 7 fps em SotC no campo deles).
- **Não** remover as otimizações Mali achando que são a causa (removê-las regrediu FPS em Mali‑G615).
- Para MGS3 em driver afetado, o caminho certo é **Vulkan + cópia de RT**, não OpenGL + cópia de RT.

### O que explicitamente NÃO fazer

- Trocar OpenGL ↔ Vulkan globalmente como "correção". Já foi feito nos dois sentidos (1.0.17 e
  1.0.20) e os dois falharam; ambos os backends têm caminho de feedback dependente de driver.
- Classificar saúde gráfica por amostra de pixel. Já produziu 38 falsos positivos em 6 modelos.
- Adicionar mais uma condição por nome de jogo ou nome de GPU. É a origem do ciclo.

---

## 5. Como saberemos que saiu do círculo

Critérios de encerramento, não impressão de melhora:

1. Um evento `armsx2/graphics-boot` de um Galaxy A07 e de um A15 chega à telemetria com GPU, driver
   e versão preenchidos — isto é, o aparelho passou a ser observável.
2. A abertura do jogo no A07 volta a funcionar e sobrevive a uma atualização de driver simulada
   (apagar/reintroduzir o cache não muda o comportamento).
3. Zero eventos de troca automática de renderer — porque não existe mais troca automática cega.
4. MGS3 no A15 renderiza correto **e** com FPS igual ou melhor que 1.0.16, em Vulkan.
5. Nenhuma regressão de FPS em Shadow of the Colossus em Mali (é o canário do upstream para a
   armadilha da cópia de RT em GL).

---

## 6. Referências upstream

- `ARMSX2/ARMSX2@master` — `pcsx2/GS/Renderers/Common/GSGPUProfile.h`, `GSGPUDriverProfile.cpp`,
  `GSGPUProfileMali.cpp`, `GSFramebufferFetchPolicy.h`, `GSUtil.cpp`,
  `pcsx2/GS/Renderers/OpenGL/GLShaderCache.cpp`
- [#396](https://github.com/ARMSX2/ARMSX2/issues/396) — MGS3 lento + bugs gráficos em Mali‑G52
- [#512](https://github.com/ARMSX2/ARMSX2/issues/512) — preto + queda de FPS em Mali‑G615 (Vulkan)
- [#513](https://github.com/ARMSX2/ARMSX2/issues/513) / [#232](https://github.com/ARMSX2/ARMSX2/issues/232) — Mali + Vulkan quebrando render
- [#331](https://github.com/ARMSX2/ARMSX2/issues/331) — texto sumido em Mali que era **patch de widescreen**, não driver
- [#380](https://github.com/ARMSX2/ARMSX2/issues/380) — método do mantenedor: `adb logcat` + teste dirigido, sem heurística
