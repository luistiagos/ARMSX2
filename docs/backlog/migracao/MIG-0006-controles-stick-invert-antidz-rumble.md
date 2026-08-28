# MIG-0006: Inversão de Eixos Analógicos, Anti-Deadzone e Rumble para Controles Físicos

- **Prioridade:** Baixa / Média (Melhoria de Compatibilidade com Gamepads Genéricos)
- **Status:** Aberto
- **Origem:** [`docs/backlog/controller-stick-invert-antidz-rumble.md`](../controller-stick-invert-antidz-rumble.md)

---

## 1. Contexto e Objetivo

Diversos controles Bluetooth/USB genéricos populares no mercado brasileiro (modelos Ipega, D3, BSP-D8, controles tipo DualShock 2 com adaptador USB) apresentam problemas conhecidos:
1. Deadzone de hardware muito alta (zona morta excessiva).
2. Eixos analógicos invertidos vertical ou horizontalmente (especialmente analógico direito da câmera).
3. Resposta de vibração/rumble inexistente ou não calibrada.

O upstream moderno do ARMSX2 em Jetpack Compose possui um editor de botões e mapeamento SDL, mas não expõe os multiplicadores de **anti-deadzone**, **inversão de eixos individuais (X/Y)** e intensidade de **rumble**.

---

## 2. Análise Técnica

- A camada de leitura de controles do Android/SDL captura os eixos `AXIS_X`, `AXIS_Y`, `AXIS_Z`, `AXIS_RZ`.
- A transformação matemática de deadzone e inversão é pura:
  $$\text{valor\_corrigido} = \text{sign}(v) \times \frac{|v| - \text{deadzone}}{1 - \text{deadzone}} \times (\text{invert} ? -1 : 1)$$
- No Compose, adicionar sliders e checkboxes nas configurações de controles por porta/perfil (`ControllerSettingsTab.kt`).

---

## 3. Escopo da Implementação

**Arquivos a modificar:**
- `platforms/android/app/src/main/java/com/armsx2/ui/settingshub/` (tela de configuração de controles)
- `platforms/android/app/src/main/cpp/native-lib.cpp` / SDL Controller bindings (aplicação dos multiplicadores)
- `assets/i18n/*.json` (traduções de anti-deadzone e invert axes)

---

## 4. Como Validar

1. Conectar um gamepad Bluetooth com eixos invertidos.
2. Ativar "Inverter Eixo Y do Analógico Direito" nas configurações.
3. Testar a movimentação de câmera em um jogo 3D (ex: *GTA San Andreas* ou *God of War*) e conferir se o comportamento inverteu perfeitamente.
