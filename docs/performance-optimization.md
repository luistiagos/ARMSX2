# Performance Optimization — RetroSystem PS2

## Objetivo

Maximizar FPS na maioria dos smartphones Android, eliminando quedas de desempenho causadas por configurações padrão conservadoras herdadas do PCSX2 desktop.

---

## 1. Renderer Auto com preferência segura por Vulkan

### Problema anterior
O padrão Android chegou a resolver `Auto` diretamente para OpenGL sempre que OpenGL estava compilado. Isso evitava alguns drivers problemáticos, mas deixava GPUs capazes, como Adreno recentes, sem o caminho Vulkan por padrão.

### Solução implementada

**Arquivo:** `app/src/main/cpp/pcsx2/GS/GSUtil.cpp`

O branch Android em `GSUtil::GetPreferredRenderer()` agora resolve o renderer `Auto` para Vulkan quando `GSDeviceVK::IsSuitableDefaultRenderer()` aprova o dispositivo, com fallback para OpenGL:

```cpp
#elif defined(__ANDROID__)
#if defined(ENABLE_VULKAN)
    if (GSDeviceVK::IsSuitableDefaultRenderer())
        preferred_renderer = GSRendererType::VK;
#endif
#if defined(ENABLE_OPENGL)
    if (preferred_renderer == GSRendererType::Auto)
        preferred_renderer = GSRendererType::OGL;
#endif
    if (preferred_renderer == GSRendererType::Auto)
        preferred_renderer = GSRendererType::SW;
```

Vulkan continua disponível no spinner de renderer para seleção manual, mas volta a ser o caminho automático em aparelhos que passam na triagem de driver.

### Triagem real para Android (allowlist)

**Arquivo:** `app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp`

A heurística original de `IsSuitableDefaultRenderer()` era pensada para desktop (só rejeitava llvmpipe/SwiftShader/Intel) e aprovava Vulkan em qualquer GPU Android — inclusive Mali/PowerVR/Adreno antigas com drivers Vulkan quebrados, causando glitches e FPS pior que OpenGL. Agora o branch Android usa uma allowlist:

- **Aprovados:** Adreno 6xx ou mais recente (incl. drivers Turnip) e Samsung Xclipse.
- **Rejeitados:** Mali, PowerVR, IMG, Adreno < 6xx, nomes desconhecidos, e qualquer aparelho abaixo de Android 10 (API 29).

Aparelhos rejeitados seguem no OpenGL pelo caminho `Auto`; Vulkan permanece selecionável manualmente.

### Escolha explícita do usuário é preservada

Quando o usuário muda o renderer no spinner, a flag `EmuCore/GS/RendererSetByUser` é gravada como `true` e as migrações de perfil **nunca mais sobrescrevem** o renderer desse aparelho. Aplicar um preset de performance devolve o renderer para `Auto` e limpa a flag (o usuário delegou a escolha de volta).

### Fallback em runtime

**Arquivo:** `app/src/main/cpp/pcsx2/GS/GS.cpp`

Se um renderer automático diferente de OpenGL falhar ao criar o device/swapchain em tempo de execução, o `GSopen()` ainda faz uma segunda tentativa com OpenGL antes de abortar:

```cpp
#if defined(__ANDROID__) && defined(ENABLE_OPENGL)
if (!res && automatic_renderer && renderer != GSRendererType::OGL) {
    Console.WriteLn(Color_StrongOrange, "Automatic renderer failed, falling back to OpenGL.");
    renderer = GSRendererType::OGL;
    res = OpenGSDevice(renderer, true, false, vsync_mode, allow_present_throttle);
    if (res) {
        res = OpenGSRenderer(renderer, basemem);
        if (!res) CloseGSDevice(true);
    }
}
#endif
```

### UI de seleção de renderer

**Arquivo:** `app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java`

Corrigido o mapeamento inicial do spinner: quando nenhum renderer está salvo (ou o valor é `-1` / Auto), a UI agora seleciona a posição `0` ("Auto") em vez de forçar OpenGL:

```java
int v = (r == null || r.isEmpty()) ? -1 : Integer.parseInt(r);
switch (v) {
    case 12: pos = 1; break;   // OpenGL
    case 13: pos = 2; break;   // Software
    case 14: pos = 3; break;   // Vulkan
    default: pos = 0;          // Auto
}
```

---

## 2. Padrões de Performance Android Centralizados

### Problema anterior
Os defaults de performance estavam duplicados literalmente em dois JNI entry-points (`NativeApp_initialize` e `NativeApp_reloadDataRoot`), usando valores numéricos hardcoded (ex: `SetIntValue("Renderer", 12)`) sem constantes.

### Solução implementada

**Arquivo:** `app/src/main/cpp/main.cpp`

Criadas as funções centralizadas:

```cpp
static void ApplyAndroidPerformanceDefaults(SettingsInterface& settings);
static bool MigrateAndroidPerformanceDefaults(SettingsInterface& settings);
```

`ApplyAndroidPerformanceDefaults` — chamada em primeiro boot / reset de data root. Define os valores ideais para Android de uma vez só, em lugar único.

`MigrateAndroidPerformanceDefaults` — chamada em boots subsequentes (settings existentes). Aplica apenas os valores que mudaram desde o último perfil. Retorna `true` se algo foi salvo, disparando `s_settings_interface->Save()`.

### Versão do perfil (`ANDROID_PERFORMANCE_PROFILE_VERSION = 6`)

Controla quando a migração é reaplicada. Versões anteriores:

| Versão | Mudança principal |
|--------|--------------------|
| 1 | OpenGL forçado como padrão |
| 2 | Tentativa de Vulkan forçado (revertida) |
| 3 | Renderer Auto + EECycleRate 0 |
| 4 | Preset de desempenho + EECycleRate -1 |
| 5 | Auto prefere Vulkan adequado + EECycleRate 0 |
| **6** | **Allowlist Vulkan Android + blending Basic + HWDownloadMode Enabled + RendererSetByUser** |

### Valores do preset "Melhor desempenho"

| Chave | Valor | Motivo |
|-------|-------|--------|
| `EmuCore/GS/Renderer` | Auto (-1) | No Android prefere Vulkan quando aprovado; senão OpenGL |
| `upscale_multiplier` | 1.0x | Sem custo extra de resolução |
| `VsyncEnable` | false | Elimina latência de sincronização |
| `hw_mipmap` | false | Reduz tráfego de textura |
| `fxaa` | false | Post-process pesado desnecessário |
| `SkipDuplicateFrames` | true | Economiza ciclos em cenas paradas |
| `accurate_blending_unit` | 1 (Basic) | Padrão do PCSX2; Minimum quebrava transparências/sombras em muitos jogos |
| `texture_preloading` | 2 (Full) | Evita stutter por carregamento tardio |
| `MaxAnisotropy` | 0 (Off) | Sem custo de filtragem anisotrópica |
| `HWDownloadMode` | 0 (Enabled) | Unsynchronized (2) retornava dados desatualizados e corrompia efeitos que releem o render target |
| `HardwareReadbacks` | false | Chave legada — não é lida pelo engine (mantida só pela UI) |
| `dithering_ps2` | 0 (Off) | Sem custo de dithering |
| `Speedhacks/vuThread` | true | VU em thread separada |
| `Speedhacks/vu1Instant` | true | VU1 sem latência artificial |
| `Speedhacks/WaitLoop` | true | Economiza CPU em loops de espera |
| `Speedhacks/IntcStat` | true | Reduz interrupções desnecessárias |
| `Speedhacks/vuFlagHack` | true | Melhora throughput de flags VU |
| `Speedhacks/fastCDVD` | true | Carregamento de disco mais rápido |
| `EECycleRate` | 0 | Padrão do EE; evita slowdown artificial em jogos sensíveis |
| `EECycleSkip` | 0 | Sem pulo de ciclo (estabilidade) |
| `EnableFastBoot` | true | Pula animação de boot da Sony |
| `Logging/*` | false | Logging nativo desligado em produção |

---

## 3. Preset de Desempenho na UI

### Funcionalidade

Spinner no topo da seção **Performance** em `SettingsActivity` com três opções localizadas:

| Posição | EN | PT-BR | AR | ZH |
|---------|-----|-------|----|----|
| 0 | Best performance | Melhor desempenho | أفضل أداء | 最佳性能 |
| 1 | Balanced | Equilibrado | متوازن | 均衡 |
| 2 | Best quality | Melhor qualidade | أفضل جودة | 最佳质量 |

O valor selecionado é persistido em `EmuCore/AndroidPerformancePreset` (int).

### Configurações por preset

| Chave | Melhor desempenho | Equilibrado | Melhor qualidade |
|-------|:-----------------:|:-----------:|:----------------:|
| `upscale_multiplier` | 1x | 2x | 3x |
| `hw_mipmap` | false | true | true |
| `fxaa` | false | false | true |
| `accurate_blending_unit` | 1 (Basic) | 1 (Basic) | 2 (Medium) |
| `MaxAnisotropy` | 0 | 2x | 4x |
| `CASMode` | 0 (None) | 0 (None) | 1 (Sharpen) |
| `HWDownloadMode` | 0 (Enabled) | 0 (Enabled) | 0 (Enabled) |
| `HardwareReadbacks` | false | false | true |
| `dithering_ps2` | 0 | 2 (Unscaled) | 2 (Unscaled) |
| `EECycleRate` | 0 | 0 | 0 |

Speedhacks (`vuThread`, `WaitLoop`, `IntcStat`, `vuFlagHack`, `fastCDVD`, `vu1Instant`) ficam habilitados em todos os presets — são seguros e melhoram FPS sem afetar qualidade visual.

### Comportamento ao trocar preset

1. Salva `AndroidPerformancePreset` via `NativeApp.setSetting`.
2. Aplica todos os valores do preset via `NativeApp.setSetting` / `NativeApp.renderUpscalemultiplier`.
3. Atualiza todos os controles visíveis da tela (spinners, sliders, switches) para refletir os novos valores imediatamente.
4. Notifica `MainActivity` via `pendingSettingsResult.putExtra("SET_RENDERER", -1)` para reaplicar renderer se o emulador estiver rodando.

### Localização

Arquivos de recursos criados/atualizados:

| Arquivo | Tipo |
|---------|------|
| `values/arrays.xml` | Array `performance_presets` (EN) |
| `values-pt-rBR/arrays.xml` | Array `performance_presets` (PT) |
| `values-ar/arrays.xml` | Array `performance_presets` (AR) |
| `values/strings.xml` | Label `settings_performance_preset` (EN) |
| `values-pt-rBR/strings.xml` | Label (PT) |
| `values-ar/strings.xml` | Label (AR) |
| `values-zh-rCN/strings.xml` | Label (ZH) |

---

## 4. Arquivos Modificados

| Arquivo | Mudança |
|---------|---------|
| `app/src/main/cpp/main.cpp` | Centralizou defaults/migração; preset padrão; EECycleRate 0; respeita `RendererSetByUser` |
| `app/src/main/cpp/pcsx2/GS/GSUtil.cpp` | Android resolve Auto para Vulkan aprovado, com fallback OpenGL |
| `app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp` | Allowlist Android em `IsSuitableDefaultRenderer` (Adreno 6xx+/Xclipse, API ≥ 29) |
| `app/src/main/cpp/pcsx2/GS/GS.cpp` | Fallback Auto→OpenGL se Vulkan falhar em runtime |
| `app/src/main/java/.../SettingsActivity.java` | Spinner de preset; helpers de UI; renderer default corrigido |
| `app/src/main/res/layout/include_settings_card_performance.xml` | Adicionado spinner `sp_performance_preset` |
| `app/src/main/res/values*/arrays.xml` | Array `performance_presets` em EN/PT/AR |
| `app/src/main/res/values*/strings.xml` | Label `settings_performance_preset` em EN/PT/AR/ZH |
