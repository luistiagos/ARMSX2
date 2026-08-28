# MIG-0008: Reconciliação do Discord Rich Presence com a Identidade RetroSystem PS2

- **Prioridade:** Baixa (Identidade e Integração Social)
- **Status:** Aberto
- **Origem:** `version1` (`app/src/main/java/kr/co/iefriends/pcsx2/utils/DiscordBridge.java`) vs Upstream (`platforms/android/app/src/main/java/com/armsx2/discord/`)
- **Documento de referência:** [`docs/plano-fork-sobre-upstream.md`](../../plano-fork-sobre-upstream.md) §5

---

## 1. Contexto e Objetivo

O aplicativo oficial do upstream possui um módulo de Discord Rich Presence que conecta via RPC e exibe o status de jogo do usuário no perfil do Discord. No entanto, ele utiliza o App ID, assets e textos padrão do ARMSX2 original.

Na `version1`, utilizávamos o nome de exibição "RetroSystem PS2". É necessário reconciliar o módulo de Discord do fork para que ele reporte a identidade do produto correta no Discord.

---

## 2. Análise Técnica

- O upstream implementa `com.armsx2.discord.*` e `DiscordNative.java`.
- É necessário alinhar o `client_id` da aplicação do Discord e os nomes das strings de status ("Jogando no RetroSystem PS2").

---

## 3. Escopo da Implementação

**Arquivos a modificar:**
- `platforms/android/app/src/main/java/com/armsx2/discord/`
- `platforms/android/app/src/main/cpp/pcsx2/` (onde o Rich Presence é atualizado com nome do jogo e tempo de sessão)

---

## 4. Como Validar

1. Abrir um jogo no RetroSystem PS2 com o aplicativo do Discord aberto no mesmo aparelho ou conectado via RPC.
2. Conferir no perfil do Discord se a atividade exibe o logo e o título: *"Jogando RetroSystem PS2 - <Nome do Jogo>"*.
