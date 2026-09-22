# TASK-0099: pasta de download própria no catálogo, adoção legada e fragile user data

- **Status:** concluída
- **Criada em:** 2026-09-21
- **Concluída em:** 2026-09-21
- **Feature:** nenhuma
- **Bugs que resolve:** [catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria](../bugs/done/catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria_2026-09-21T00-16.md), [catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork](../bugs/done/catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork_2026-09-21T00-16.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0099:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

No fork, os downloads do catálogo eram direcionados compulsoriamente para `File(assetCopyRoot(app), "roms")`,
que por padrão fica em `/storage/emulated/0/Android/data/come.nanodata.armsx2/files/roms`.

Essa pasta é considerada privada do aplicativo pelo Android. Ao desinstalar o app ou tocar em
"Limpar dados / Limpar armazenamento" nas Configurações do sistema, o Android apaga toda a árvore
silenciosamente, destruindo dezenas de gigabytes de jogos baixados.

Além disso:
1. O manifesto não declarava `android:hasFragileUserData="true"`, impedindo o Android de perguntar
   "Manter dados do app?" ao desinstalar.
2. A versão 1.0.x gravava `download_dir_path` nas SharedPreferences `armsx2` para salvar jogos numa
   pasta do usuário (ex: `/storage/emulated/0/RetroSystem`), mas o fork não adotava essa chave nem
   oferecia opção equivalente.
3. A função `validateSystemDirWritable` usava `probe.createNewFile()`, que falha permanentemente caso
   um arquivo de sonda `.armsx2-write-probe` órfão tenha ficado para trás.

## O que muda

1. **Manifesto:** `android:hasFragileUserData="true"` adicionado em `<application>` no `AndroidManifest.xml`.
2. **Runtime e Persistência (`MainActivityRuntime.kt`):**
   - Correção da sonda `validateSystemDirWritable` para limpar sonda órfã e testar escrita direta com stream.
   - Adicionado gerenciamento da preferência `downloadDir` em `ARMSX2.xml` com métodos auxiliares `setDownloadDir`,
     `hasCustomDownloadDir`, `customDownloadDirPosix` e `downloadDirFile`.
   - Adotada a chave `download_dir_path` das SharedPreferences legadas (`armsx2`) em `adoptLegacyDataRoot()`.
3. **Catálogo e Biblioteca (`HomeViewModel.kt`, `HomeScreen.kt`):**
   - `romsDir()` passa a usar `MainActivityRuntime.downloadDirFile(getApplication())`.
   - `allAppRomsDirs()` inclui tanto o destino ativo de download quanto a pasta privada padrão e pastas de outros volumes, garantindo que jogos baixados antes continuem acessíveis.
   - Observação de mudanças em `downloadDir` na `HomeScreen`.
4. **Interface (`RomFoldersScreen.kt`, `CatalogDownloadModal.kt`):**
   - Adicionada seção dedicada a "Diretório de Download" em `RomFoldersScreen`, permitindo escolher pasta própria ou restaurar padrão.
   - Aviso informativo no modal de download alertando sobre retenção de dados quando a pasta padrão do app estiver em uso.

## Escopo

**Entra:**
- `AndroidManifest.xml`: `android:hasFragileUserData="true"`.
- `MainActivityRuntime.kt`: gestão de `downloadDir`, adoção de `download_dir_path`, correção de `validateSystemDirWritable`.
- `HomeViewModel.kt`: uso de `downloadDirFile` em `romsDir()`, atualização de `allAppRomsDirs()`.
- `HomeScreen.kt`: recarga ao mudar `downloadDir`.
- `RomFoldersScreen.kt`: seletor de pasta de download e restauração ao padrão.
- `CatalogDownloadModal.kt`: aviso de armazenamento privado no modal de download.
- Strings de tradução em `pt-BR.json` e outros idiomas relevantes.
- Testes unitários para `validateSystemDirWritable` e resolução de diretório de download.

**Não entra:**
- Modificações em código nativo C++ do emulador (`pcsx2/`).
- Mover fisicamente arquivos existentes em disco ao alterar a pasta de download.

## Como validar

1. **Testes unitários automatizados:**
   - `:app:testGithubDebugUnitTest` cobrindo resolução de pastas e sonda de escrita.
   - `python scripts/check_traceability.py`.
2. **Validação no dispositivo real:**
   - Conferência de `hasFragileUserData` no manifesto compilado via `aapt2`.
   - Execução do script automatizado `docs/task/TASK-0099-validar-no-aparelho.py` no aparelho conectado.

## Validação no Aparelho Físico (Samsung SM-A127M — Android 11+)

Executada em 2026-09-22 no dispositivo real conectado via ADB (`RX8R90G1D6E`):

1. **Manifesto Compilado:**
   - Inspecionado APK (`app-github-debug.apk`) com `aapt2 dump xmltree`:
   - Confirmado atributo `android:hasFragileUserData(0x0101058e)=0xffffffff` presente no nó `<application>`.
2. **Pasta Customizada de Download fora de `Android/data`:**
   - Criada pasta `/storage/emulated/0/RetroSystem_Task0099_Test` e inserido jogo de teste `TestGame_Task0099.chd`.
   - Configurado `downloadDir` com o caminho customizado e disparada a inicialização do app.
   - A biblioteca varreu a pasta customizada e indexou com sucesso o jogo no cache (`gamesCache`).
3. **Resiliência da Sonda de Escrita a Arquivo Órfão:**
   - Injetado arquivo órfão `/storage/emulated/0/RetroSystem_Task0099_Test/.armsx2-write-probe`.
   - Inicializado o app: a sonda limpou o arquivo órfão com sucesso e validou as permissões de escrita sem travar.
4. **Adoção Automática de Preferências Legadas:**
   - Injetado arquivo de SharedPreferences da versão 1.0.x (`armsx2.xml`) com `download_dir_path = /storage/emulated/0/RetroSystem_Legacy_Task0099`.
   - Removido `downloadDir` do fork para simular atualização a partir da versão antiga.
   - Inicializado o app: o runtime detectou o valor legado e migrou automaticamente para `downloadDir` no `ARMSX2.xml`.

