# MIG-0008: Reconciliação do Discord Rich Presence com a Identidade RetroSystem PS2

- **Prioridade:** Baixa (Identidade e Integração Social)
- **Status:** Implementado; aguardando cadastro e validação no portal Discord
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

1. No portal Discord, usar uma aplicação chamada **RetroSystem PS2**, registrar o redirect
   `discord-<application id>:/authorize/callback` e enviar o logo com a chave
   `retrosystem_ps2`.
2. Gerar o APK com o SDK privado e
   `RETROSYSTEM_DISCORD_APPLICATION_ID=<application id>` (ou
   `-Pretrosystem.discordApplicationId=<application id>`). Um build que recebe o SDK sem esse ID
   falha de propósito, em vez de voltar silenciosamente à aplicação ARMSX2.
3. Vincular novamente a conta Discord. O token antigo pertence ao client ID do ARMSX2 e é
   descartado; ele não pode ser migrado entre aplicações OAuth.
4. Abrir um jogo no RetroSystem PS2 com o aplicativo do Discord aberto no mesmo aparelho ou
   conectado via RPC.
5. Conferir no perfil do Discord o logo, o cabeçalho *"Jogando RetroSystem PS2"*, o nome do jogo
   e o contador de tempo decorrido. Trocar de jogo deve reiniciar o contador; atualizações do
   RetroAchievements e reinícios do processo auxiliar não devem reiniciá-lo.

---

## 5. Resultado

- O client ID fixo `1531447040435814411`, que identifica a aplicação ARMSX2, foi removido do C++
  e do manifesto. Gradle agora injeta o ID da aplicação RetroSystem PS2 nos dois pontos, e o CMake
  também valida o valor em builds diretos.
- A presença usa `RetroSystem PS2` nos textos auxiliares e a chave de asset
  `retrosystem_ps2`. O nome principal acima da atividade continua vindo do cadastro da aplicação,
  como exige o Discord Social SDK.
- O IPC transporta o início da sessão em epoch milliseconds. O relógio nasce ao abrir um jogo,
  permanece estável em atualizações de presença e reconexões do helper, zera na biblioteca e
  reinicia ao trocar de título.
- O token salvo pelo client ID anterior fica em uma chave legada separada e é removido ao iniciar
  a integração; o usuário autoriza uma vez o client correto.
- `DiscordSessionClockTest`: 4 casos, 0 falhas. O build/test `GithubDebug` também processou o
  manifesto sem o SDK privado e sem o esquema OAuth do upstream.
- A conferência visual no perfil e o upload do asset continuam pendentes porque dependem do
  cadastro externo da aplicação RetroSystem PS2 e de um aparelho com Discord.
