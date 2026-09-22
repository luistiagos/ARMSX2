# TASK-0099: pasta de download própria no catálogo, adoção legada e fragile user data

- **Status:** em andamento
- **Criada em:** 2026-09-21
- **Concluída em:** 2026-09-21
- **Feature:** nenhuma
- **Bugs que resolve:** [catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria](../bugs/open/armsx2-fork/catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria_2026-09-21T00-16.md), [catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork](../bugs/open/armsx2-fork/catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork_2026-09-21T00-16.md)
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
   - Conferência de `hasFragileUserData` no manifesto compilado.
   - Configuração de pasta de download customizada em `RomFoldersScreen`.
   - Teste de persistência e reconhecimento de jogo na pasta customizada.
