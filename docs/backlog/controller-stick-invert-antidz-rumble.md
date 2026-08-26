# Backlog: Controller — Stick Invert / Anti-Deadzone / Rumble

**Origem:** ARMSX2 2.4.5 `refresh-experimental`, commit `ea39490e`  
**Data da análise:** 2026-06-30  
**Prioridade:** Média-Alta — melhora diretamente a experiência com controles genéricos/baratos

---

## Resumo executivo

O 2.4.5 adicionou 6 funcionalidades de controller em Kotlin (`Main.kt`, `ControllerMappings.kt`, `PadTab.kt`). Nosso projeto usa Java e ViewFlipper. Após análise do código:

| Feature | Estado atual | Esforço para portar |
|---|---|---|
| Rumble toggle | ✅ **Já existe** | — |
| Rumble fallback p/ vibrador do telefone | ✅ **Já existe** | — |
| Trigger re-normalização (L2/R2) | ⚠️ Parcial — diferente | ~30min |
| Stick invert X/Y (esquerdo e direito) | ❌ Ausente | ~2h |
| Stick swap X↔Y | ❌ Ausente | ~30min (junto com invert) |
| Anti-deadzone (output floor) | ❌ Ausente | ~1.5h |
| Stick-to-hotkey binding | ❌ Ausente | ~5h (complexo) |

---

## O que já existe — não implementar

### Rumble toggle
`MainActivity.java:162` — `sVibrationEnabled` (volatile boolean)  
`MainActivity.java:5176` — `setVibrationPreference(boolean)`  
`SettingsActivity.java:1367` — `sw_vibration` lê/grava `Pad1/Vibration`  

Toggle global já funciona. O refresh apenas replicou isso com nomes diferentes.

### Rumble fallback para vibrador do telefone
`MainActivity.java:5127` — usa `device_id = 999999` (sentinel)  
`SDLControllerManager.java:5130` — `hapticRun(vibratorServiceId, combined, RUMBLE_DURATION_MS)`  

Quando nenhum controle tem rumble registrado, o P1 já usa o vibrador do dispositivo. Mesmo comportamento do `cab3752b`.

---

## Trigger re-normalização — diferença minor

**Refresh (`ea39490e`):**
```kotlin
const val TRIGGER_DEAD = 0.06f
// Re-normalizes: remove dead band and ramp smoothly from 0
val normalized = ((raw - TRIGGER_DEAD) / (1f - TRIGGER_DEAD)).coerceIn(0f, 1f)
```

**Nosso código (`MainActivity.java:226` + `4988`):**
```java
private static final float TRIGGER_DEADZONE = 0.04f;  // smaller dead band

private float normalizeTrigger(float raw) {
    if (raw < 0f) return Math.min(1f, Math.max(0f, (raw + 1f) * 0.5f)); // -1..0 → 0..0.5
    return Math.min(1f, raw);  // 0..1 passthrough — sem re-normalização!
}
```

**Problema:** nossa versão não re-normaliza após o dead band. Se o trigger começa em 0.04, o output também começa em 0.04 (não 0). Em controles baratos isso causa "flicker" na pressão mínima.

**Fix simples:** alterar `normalizeTrigger` para aplicar a re-normalização:
```java
private static final float TRIGGER_DEAD = 0.06f;

private float normalizeTrigger(float raw) {
    if (Float.isNaN(raw)) return 0f;
    if (raw < 0f) raw = (raw + 1f) * 0.5f;  // -1..0 → 0..0.5 (trigger invertido)
    if (raw <= TRIGGER_DEAD) return 0f;
    return Math.min(1f, (raw - TRIGGER_DEAD) / (1f - TRIGGER_DEAD));
}
```
**Arquivo:** [app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java:4988](../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java)

---

## Stick Invert X/Y + Swap — o que implementar

### Onde os valores entram no sistema

`MainActivity.java:4902` — `handleGamepadMotion()`:
```java
float lx = getCenteredAxis(e, MotionEvent.AXIS_X);  // ← aplicar invert/swap aqui
float ly = getCenteredAxis(e, MotionEvent.AXIS_Y);
sendAnalog(player, 111, Math.max(0f, lx));
// ...
float rx = getCenteredAxis(e, MotionEvent.AXIS_RX); // ← mesma coisa
float ry = getCenteredAxis(e, MotionEvent.AXIS_RY);
```

### Plano de implementação

**1. Armazenar preferências — estender `ControllerMappingManager` ou criar `ControllerTuningManager`**

```java
// Chaves SharedPreferences (prefs "controller_mapping_prefs" já existentes)
static final String KEY_L_INVERT_X  = "tuning_l_invert_x";
static final String KEY_L_INVERT_Y  = "tuning_l_invert_y";
static final String KEY_L_SWAP_XY   = "tuning_l_swap_xy";
static final String KEY_R_INVERT_X  = "tuning_r_invert_x";
static final String KEY_R_INVERT_Y  = "tuning_r_invert_y";
static final String KEY_R_SWAP_XY   = "tuning_r_swap_xy";

// Getters/setters + cache em campos estáticos para leitura rápida em onGenericMotionEvent
```

**2. Aplicar em `handleGamepadMotion` antes dos `sendAnalog`:**

```java
// Left stick correction
float lx = getCenteredAxis(e, MotionEvent.AXIS_X);
float ly = getCenteredAxis(e, MotionEvent.AXIS_Y);
if (ControllerTuningManager.isLSwapXY()) { float t = lx; lx = ly; ly = t; }
if (ControllerTuningManager.isLInvertX()) lx = -lx;
if (ControllerTuningManager.isLInvertY()) ly = -ly;
// ... sendAnalog calls unchanged

// Right stick correction (idêntico)
```

**3. UI — novo bloco no card de controller em `SettingsActivity`**

Layout: 6 `MaterialSwitch` agrupados em dois sub-grupos (Left Stick / Right Stick).  
Arquivo: [app/src/main/res/layout/include_settings_card_controller.xml](../../app/src/main/res/layout/include_settings_card_controller.xml) (verificar se existe) ou criar.

---

## Anti-Deadzone — o que implementar

### O que é

Jogos como Cold Fear e Area 51 têm um deadzone interno grande (~20%). O analógico precisa se mover bastante antes de o jogo reagir. O anti-deadzone adiciona um "floor" de output: o valor enviado ao emulador nunca vai abaixo de um mínimo, eliminando a zona morta do jogo.

**Fórmula (refresh `ea39490e`):**
```kotlin
// floor ∈ [0.0, 0.6], default 0.0 (off)
fun applyAntiDz(raw: Float, floor: Float): Float {
    if (raw == 0f || floor == 0f) return raw
    val sign = if (raw > 0f) 1f else -1f
    return sign * (floor + (1f - floor) * abs(raw))
}
```

### Implementação

**1. SharedPreferences:** `tuning_anti_dz` (int 0–60, representa %)

**2. Aplicar após correção de invert/swap, antes de `sendAnalog`:**
```java
float antiDz = ControllerTuningManager.getAntiDeadzone(); // 0.0 - 0.6
lx = applyAntiDeadzone(lx, antiDz);
ly = applyAntiDeadzone(ly, antiDz);
// idem rx, ry
```

**3. UI:** Slider 0–60% no card de controller, após os switches de invert.

---

## Stick-to-Hotkey Binding — não recomendado agora

Permite mapear direções do analógico (cima/baixo/esquerda/direita > limiar) a hotkeys do emulador (Quick Save, Quick Load, toggle menu). Complexo porque:

- Requer mudança em `ControllerMappingDialog.java` (capture de stick direction)
- Requer edge-trigger tracking por porta (array `stickHotkeyHeld[2][8]`)
- Requer API de hotkey no NativeApp para disparar save/load via Java
- A UI de captura de botão precisaria detectar eixos além de `KeyEvent`

Deixar para versão futura.

---

## Pontos de implementação resumidos

| O que | Arquivo | Linha aprox. |
|---|---|---|
| `normalizeTrigger` | [MainActivity.java](../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java) | 4988 |
| Aplicar invert/swap/anti-dz | [MainActivity.java](../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java) | 4902–4940 |
| Armazenar preferências | `ControllerMappingManager.java` ou novo `ControllerTuningManager.java` | — |
| UI toggles + slider | `include_settings_card_controller.xml` + `SettingsActivity.java` | — |
| Strings PT-BR | `values-pt-rBR/strings.xml` | — |

**Esforço total estimado (excluindo hotkey):** 3–4h
