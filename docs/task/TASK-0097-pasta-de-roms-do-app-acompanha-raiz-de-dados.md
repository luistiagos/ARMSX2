# TASK-0097: a pasta de ROMs do app vira implícita e acompanha a raiz de dados

- **Status:** concluída
- **Criada em:** 2026-09-21
- **Concluída em:** 2026-09-21
- **Feature:** nenhuma
- **Bugs que resolve:** [biblioteca-pasta-de-roms-semeada-uma-vez-nao-acompanha-a-raiz-de-dados](../bugs/done/biblioteca-pasta-de-roms-semeada-uma-vez-nao-acompanha-a-raiz-de-dados_2026-09-21T00-16.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0097:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Investigando o relato de usuário de 2026-09-20 ("os jogos que tinha baixado no app sumiram"),
descobriu-se que a pasta de ROMs do app era semeada uma única vez por `seedOwnRomsFolder()` em
`MainActivityRuntime.kt` com um caminho absoluto em `romsDirs` (SharedPreferences `ARMSX2`), apenas
quando a lista estava vazia.

Quando o usuário altera o **Armazenamento** (interno → cartão SD ou pasta própria) na configuração,
o `systemDir` muda, o que invalida e atualiza `assetCopyRoot`. O destino dos downloads do catálogo
segue `assetCopyRoot/roms`. Porém, `romsDirs` continuava com o caminho absoluto antigo. Como a
biblioteca varre apenas o que está em `romsDirs`, todo jogo baixado após a troca terminava de baixar
mas nunca era encontrado pela biblioteca (`needsDownload = true`, `isCatalogOnly = true`), sumindo
da aba Salvos e voltando a oferecer "baixar" no catálogo.

## O que muda

Separar a pasta própria do app das pastas escolhidas pelo usuário:
1. A pasta de ROMs do app é **implícita e derivada**: calculada dinamicamente a partir de
   `assetCopyRoot(context)/roms`. Além disso, para evitar que jogos baixados antes da troca de
   armazenamento sumam, todos os volumes de armazenamento do app existentes em
   `context.getExternalFilesDirs(null)` também são incluídos na varredura e no `markDownloaded`.
2. `romsDirs` em SharedPreferences guarda **apenas** pastas externas configuradas pelo usuário.
   `seedOwnRomsFolder()` é removido, e na carga inicial de `romsDirs` qualquer caminho legado
   correspondente à pasta privada do app é higienizado da lista.
3. O `setupRecoveryNeeded` só é acionado se o usuário tiver configurado pastas externas e nenhuma
   estiver acessível (`romsDirs.value.isNotEmpty() && !romsAccessible(...)`).
4. `CatalogParser.markDownloaded` ganha suporte a receber múltiplos diretórios de ROMs, marcando
   jogos baixados tanto no armazenamento interno quanto no cartão SD.
5. Na `HomeScreen`, o `LaunchedEffect` que invoca `load()` passa a observar também `systemDir`,
   recarregando e revarrendo imediatamente se a raiz de dados for alterada.

## Escopo

**Entra:**
- `MainActivityRuntime.kt`: remoção de `seedOwnRomsFolder()`, filtro/higienização de pastas privadas em `romsDirs`, ajuste do guard em `setupRecoveryNeeded`.
- `HomeViewModel.kt`: cálculo de diretórios efetivos (`effectiveDirectories()`) incluindo `romsDir()` e volumes de arquivos do app; atualização de `DownloadQueueManager.setRomsDir`; chamada a `markDownloaded` com as pastas do app.
- `HomeScreen.kt`: inclusão de `systemDir` na chave do `LaunchedEffect(directories, systemDir, nativeReady)`.
- `CatalogParser.java`: sobrecarga `markDownloaded(entries, Collection<File> romsDirs)`.
- `OnboardingViewModel.kt`: avanço permitido na página 3 mesmo sem pastas externas adicionadas.
- Testes unitários para `CatalogParser.markDownloaded` multi-diretório e detecção de pastas privadas.

**Não entra:**
- Migração física em disco de arquivos já baixados ao trocar de armazenamento (`migrateData`) — isso consumiria bateria/tempo de I/O em background com arquivos de até dezenas de GB; em vez disso, mantemos a leitura dos volumes do app para que ambos continuem funcionando.
- Alterações em `pcsx2/` ou código nativo C++.

## Como validar

1. **Testes automatizados:**
   - Teste unitário verificando que `CatalogParser.markDownloaded` marca como baixados jogos presentes em diretórios distintos (ex: interno e SD).
   - Teste unitário de higienização de `romsDirs`.
   - Bateria de testes unitários do Android (`:app:testGithubDebugUnitTest`).
   - Rastreabilidade via `python scripts/check_traceability.py`.

2. **No aparelho (Moto G86 5G, Android 16, SDK 36):**
   - **Boot limpo e SharedPreferences:** Instalação do APK com pacote de teste isolado `come.nanodata.armsx2.task0097`. `shared_prefs/ARMSX2.xml` verificado: `romsDirs` não foi criado nem semeado com caminho absoluto (está vazio).
   - **Descoberta implícita:** Arquivo de teste colocado na pasta do app (`/sdcard/Android/data/come.nanodata.armsx2.task0097/files/roms/Shadow of the Colossus (USA).chd`). Na aba "Salvos", o jogo foi reconhecido e exibido imediatamente ("Total de jogos: 1") sem que nenhuma pasta estivesse cadastrada em `romsDirs`.
   - **Troca de armazenamento (Multi-Volume):** Configurado `systemDir = /sdcard/ARMSX2_Custom` e colocado segundo jogo (`Grand Theft Auto - San Andreas (USA).iso`). Ao reabrir, o app varreu tanto a nova pasta ativa quanto o volume anterior do app: ambos os jogos foram exibidos em "Salvos" ("Total de jogos: 2").
   - **Higienização de instalações legadas:** Injetado `romsDirs` contaminado com a pasta privada do app mais uma pasta de usuário (`/sdcard/UserCustomGames`). Ao reiniciar o app, `MainActivityRuntime.onCreate` expurgou automaticamente a pasta privada do app, persistindo apenas `["/sdcard/UserCustomGames"]`.

