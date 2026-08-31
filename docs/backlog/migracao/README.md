# Backlog de Migração: `version1` → `feature/fork-upstream-android`

Este diretório contém os itens pendentes de migração da versão customizada antiga (`version1`) para a nova árvore oficial moderna baseada em Jetpack Compose (`feature/fork-upstream-android`).

> **Observação:** O item *"Diagnóstico de Saúde Gráfica (GraphicsHealthMonitor)"* foi deliberadamente descartado por decisão de arquitetura, visto que os problemas gráficos de drivers Mali na versão nova foram sanados pelo banco de perfis de GPU do upstream.

---

## 📌 Índice de Tarefas de Migração

| ID | Título | Prioridade | Status | Área |
| :--- | :--- | :---: | :---: | :--- |
| [MIG-0001](MIG-0001-savestates-legados-0x9A54.md) | Compatibilidade com Savestates Legados da version1 (`0x9A54` → `0x9A59`) | 🔴 Alta | Implementado, aguardando `.p2s` real ([TASK-0049](../../task/TASK-0049-carregar-savestates-0x9A54.md)) | Core C++ (`SaveStateLegacy.cpp`, `SPU2`) |
| [MIG-0002](MIG-0002-seletor-icones-alternativos-app.md) | Seletor de Ícones Alternativos do App (`AppIconManager`) | 🟡 Média | Aberto | UI Jetpack Compose / Launcher |
| [MIG-0003](MIG-0003-skip-mpeg-fmv-toggle-ui.md) | Expor Hack "Skip MPEG Videos (FMV)" na UI de Configurações | 🟡 Média | Concluído | UI Compose / Configuração |
| [MIG-0004](MIG-0004-coletor-adaptadores-rede.md) | Coletor de Adaptadores de Rede para Telemetria | 🟢 Baixa | Aberto | Telemetria / Diagnóstico |
| [MIG-0005](MIG-0005-descompactacao-roms-7z-zip.md) | Descompactação Automática de ROMs `.7z` e `.zip` no Catálogo | 🟡 Média | Implementado ([TASK-0048](../../task/TASK-0048-descompactar-7z-e-zip-no-download.md)) | Catálogo de ROMs / Download |
| [MIG-0006](MIG-0006-controles-stick-invert-antidz-rumble.md) | Inversão de Eixos Analógicos, Anti-Deadzone e Rumble | 🟢 Baixa | Aberto | Controles Físicos / Gamepad |
| [MIG-0007](MIG-0007-toggle-opt-out-telemetria-ui.md) | Toggle de Opt-Out de Telemetria na UI de Configurações | 🟢 Baixa | Concluído | UI Compose / Privacidade |
| [MIG-0008](MIG-0008-discord-rich-presence-retrosystem.md) | Reconciliação do Discord Rich Presence com a Identidade RetroSystem PS2 | 🟢 Baixa | Implementado; aguardando portal Discord | Presença Social / RPC |

---

## 🎯 Ordem de Execução Recomendada

1. **MIG-0001 (Savestates Legados):** Evita que qualquer jogador perca o progresso ao atualizar o app.
2. **MIG-0003 (Skip MPEG FMV na UI):** Ganho rápido de performance e jogabilidade em cutscenes pesadas.
3. **MIG-0002 (Seletor de Ícones Alternativos):** Devolve a opção de customização visual do launcher.
4. **MIG-0005 (Descompactação de `.7z`):** Aumenta os títulos jogáveis diretamente pelo catálogo.
5. **MIG-0007, MIG-0004, MIG-0006, MIG-0008:** Recursos complementares e ajustes finos de sistema.
