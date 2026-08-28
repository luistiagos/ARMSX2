# MIG-0007: Toggle de Opt-Out de Telemetria na UI de Configurações

- **Prioridade:** Baixa (Privacidade e Controle do Usuário)
- **Status:** Concluído
- **Origem:** TASK-0018 / Módulo de Telemetria
- **Documento de referência:** [`docs/task/TASK-0018-telemetria-no-fork.md`](../../task/TASK-0018-telemetria-no-fork.md)

---

## 1. Contexto e Objetivo

Na **TASK-0018**, o `TelemetryReporter` foi trazido da versão anterior para o fork com suporte completo a kill-switch e desativação programática por flag nas `SharedPreferences`.

No entanto, atualmente não existe um toggle visual na tela de Configurações em Jetpack Compose que permita ao usuário optar ativamente por não enviar relatórios anônimos de telemetria e crash reports.

---

## 2. Análise Técnica

- `TelemetryReporter.java` já lê a preferência booleana `telemetry_error_reporting`.
- O switch fica na aba App (`ui/settings/AppTab.kt`), junto às demais opções globais de privacidade.

---

## 3. Escopo da Implementação

**Arquivos a modificar:**
- `platforms/android/app/src/main/java/com/armsx2/ui/settings/AppTab.kt`
- `platforms/android/app/src/main/java/com/armsx2/telemetry/TelemetryReporter.java`
- `platforms/android/app/src/main/java/com/armsx2/telemetry/CrashReporter.java`
- `platforms/android/app/src/main/java/com/armsx2/i18n/I18n.kt` e `assets/i18n/*.json`

---

## 4. Como Validar

1. Desativar a chave "Enviar dados anônimos de telemetria e falhas" nas configurações.
2. Provocar um erro ou fechar a VM.
3. Confirmar que nenhum pacote HTTP é despachado para o endpoint `/logErr`.

---

## 5. Resultado

- O toggle persiste a chave `telemetry_error_reporting` no `SharedPreferences("ARMSX2")` e atualiza
  imediatamente o kill-switch em memória.
- O opt-out legado no arquivo `SharedPreferences("armsx2")` continua sendo respeitado até o usuário
  fazer uma escolha explícita na UI.
- O bloqueio cobre tanto `/logErr` quanto o upload de arquivos completos do `CrashReporter`.
- A opção foi adicionada à busca de configurações e localizada nos 19 idiomas do app.
- O build Kotlin/Compose passou em checkout isolado; a validação do tráfego em aparelho físico
  continua pendente.
