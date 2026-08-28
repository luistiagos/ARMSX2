# MIG-0003: Expor Hack "Skip MPEG Videos (FMV)" na UI de Configurações

- **Prioridade:** Média (Performance e Correção de Cutscenes Pesadas)
- **Status:** Concluído
- **Origem:** `version1` (Commit `656d94f6fc` e [`docs/backlog/fmv-skip-skip-mpeg-hack-ui.md`](../fmv-skip-skip-mpeg-hack-ui.md))

---

## 1. Contexto e Objetivo

O emulador possui um hack de JIT no core chamado `SkipMPEGHack` (`iR5900.cpp:2245` / `iR5900-arm64.cpp:3389`), que detecta o padrão `sceMpegIsEnd` e pula cutscenes em vídeo FMV (MPEG) para evitar travamentos ou quedas bruscas de FPS em celulares de entrada/intermediários.

No fork moderno em Jetpack Compose, o switch está exposto na interface visual de configurações na aba **Correções** (`FixesTab.kt`, seção *Correções GameDB*), integrado ao `Settings.kt` com persistência em `EmuCore/Gamefixes/SkipMPEGHack` e com suporte a escopo Global e Por Jogo.

---

## 2. Análise Técnica

- O C++ lê e aplica `EmuCore/Gamefixes/SkipMPEGHack` via `Pcsx2Config.cpp` (`SettingsWrapBitBool(SkipMPEGHack)`).
- O recompiler ARM64 (`iR5900-arm64.cpp`) e x86 (`iR5900.cpp`) avaliam `CHECK_SKIPMPEGHACK`.
- No Android Kotlin/Compose:
  - `Settings.kt` define `gamefixSkipMpeg`, serializa via `toJson`/`fromJson`, calcula `diff`/`merge` e despacha para `put("EmuCore/Gamefixes", "SkipMPEGHack", "bool", ...)`.
  - `FixesTab.kt` renderiza o `ToggleRow` com chave `perf.fix.skipMpeg` e aviso contextual `perf.fix.skipMpeg.warning`.
  - `SettingsResetFields.kt` e `SettingsSearchIndex.kt` indexam o campo sob `SettingsCategory.Advanced`.
  - `I18n.kt` e `pt-BR.json` fornecem as strings e avisos localizados em inglês e português.

---

## 3. Arquivos Integrados

- `platforms/android/app/src/main/java/com/armsx2/ui/settings/FixesTab.kt`
- `platforms/android/app/src/main/java/com/armsx2/config/Settings.kt`
- `platforms/android/app/src/main/java/com/armsx2/ui/settingshub/SettingsResetFields.kt`
- `platforms/android/app/src/main/java/com/armsx2/ui/settingshub/SettingsSearchIndex.kt`
- `platforms/android/app/src/main/java/com/armsx2/i18n/I18n.kt`
- `platforms/android/app/src/main/assets/i18n/pt-BR.json`

---

## 4. Como Validar

1. Abrir **Configurações → Correções** (ou aba Avançado).
2. Na seção **Correções GameDB**, ativar o toggle **"Pular vídeos MPEG (FMV)"** (ou **"Skip MPEG"** em inglês).
3. Confirmar a exibição do alerta amarelo de advertência sobre possíveis impactos em cutscenes interativas.
4. Executar um jogo com vídeos de abertura longos (ex: *Katamari Damacy*, *Final Fantasy X*, *Tekken 5*) e confirmar que o vídeo é pulado instantaneamente direto para a tela de título ou gameplay.
