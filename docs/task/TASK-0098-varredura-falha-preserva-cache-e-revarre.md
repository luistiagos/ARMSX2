# TASK-0098: varredura que falha preserva o cache e biblioteca vazia revarre automaticamente

- **Status:** concluída
- **Criada em:** 2026-09-21
- **Concluída em:** 2026-09-21
- **Feature:** nenhuma
- **Bugs que resolve:** [biblioteca-varredura-que-nao-le-a-pasta-grava-cache-vazio-e-nao-revarre](../bugs/done/biblioteca-varredura-que-nao-le-a-pasta-grava-cache-vazio-e-nao-revarre_2026-09-21T00-16.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0098:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Quando o app abre em situações onde o armazenamento de ROMs está temporariamente inacessível
(cartão SD ainda não montado após o boot do aparelho, armazenamento emulado em verificação,
permissão de arquivos revogada ou permissão SAF expirada), a varredura (`repository.scan`)
encontra zero jogos sem acusar exceção.

Antes desta task:
1. `scan()` chamava `saveCache(directories, it)` incondicionalmente, persistindo `gamesCache = []`
   com a mesma chave das pastas configuradas. O cache bom anterior (que continha todos os jogos do
   usuário) era sobrescrito e destruído silenciosamente.
2. Na abertura seguinte, `HomeViewModel.load()` comparava a chave do cache com as pastas. Como a
   chave coincidia, marcava `pendingInitialScan = false` e `initialized = true`.
3. O app entrava no estado "nenhum jogo" permanente e nunca tentava revarrer sozinho, mesmo após o
   cartão SD ter sido montado ou a permissão concedida. Apenas o toque manual em ↻ recuperava os jogos.

## O que muda

1. **`GameLibraryRepository.kt`**:
   - `scan()` rastreia o sucesso da leitura de cada diretório individualmente (`scanSingleDirectory`).
   - Um diretório é considerado lido com sucesso apenas se existir, for legível e `listFiles()`
     retornar um array não nulo.
   - Suporta caminhos POSIX (iniciando com `/` ou caminhos absolutos do sistema de arquivos),
     URIs `file:` e Tree URIs SAF (`content:`).
   - Se **qualquer** diretório configurado falhar na leitura, o resultado parcial é retornado para a
     UI, mas `saveCache` **não é chamado**. O cache anterior em SharedPreferences permanece intacto.
   - `saveCache` só é invocado quando todas as pastas configuradas foram lidas com sucesso.
   - Expõe a propriedade `lastScanAllRead: Boolean` indicando se todas as pastas foram lidas.

2. **`HomeViewModel.kt`**:
   - Em `load()`, se o cache estiver vazio (`cached.games.isEmpty()`) ou se nenhum jogo do cache
     for válido no disco (`validCached.isEmpty()`), mas houver pastas configuradas
     (`scanDirs.isNotEmpty()`), define `pendingInitialScan = true`.
   - Isso garante que qualquer biblioteca vazia seja revarrida automaticamente ao abrir o app,
     sem depender do toque manual em ↻.
   - Na condição subsequente de `load()`, revarre se o cache em disco estiver vazio e houver pastas.

## Escopo

**Entra:**
- `GameLibraryRepository.kt`: controle estrito de leitura por pasta em `scan()`, preservação do cache em caso de falha de leitura, flag `lastScanAllRead`.
- `HomeViewModel.kt`: revarredura automática em `load()` quando cache for vazio e houver pastas.
- Testes unitários para `GameLibraryRepository` comprovando que diretórios ilegíveis não gravam cache vazio e preservam o cache pré-existente.
- Atualização e fechamento do bug report correspondente.

**Não entra:**
- Modificações em código C++ nativo do emulador (`pcsx2/`).
- Alterações no gerenciador de downloads ou catálogo.

## Como validar

1. **Testes unitários automatizados:**
   - Teste unitário em `GameLibraryRepositoryTest.kt` validando que:
     - `scan()` com pasta cujo `listFiles()` é nulo (ou inexistente) não chama `saveCache`, deixando o cache anterior intacto.
     - `scan()` com pasta válida e legível atualiza o cache normalmente.
     - `lastScanAllRead` indica falso quando há pasta ilegível.
   - Bateria completa de testes unitários do Gradle (`:app:testGithubDebugUnitTest`).
   - Rastreabilidade consistente (`scripts/check_traceability.py`).

2. **Validação no dispositivo conectado (Samsung Galaxy A12, Android 13):**
   - Com jogos no cache, simular diretório ilegível adicionando pasta inacessível ou pasta temporária inexistente.
   - Abrir o app / disparar refresh: verificar via `run-as come.nanodata.armsx2 cat shared_prefs/ARMSX2.xml` que `gamesCache` **não** foi zerado para `[]`.
   - Limpar o cache para `[]` manualmente e reabrir o app com pastas válidas: verificar que o app revarre automaticamente no boot e repovoa os jogos sem necessidade do ↻ manual.

## Resultado

Implementado e validado com sucesso em duas frentes:

1. **Testes unitários automatizados (`:app:testGithubDebugUnitTest`):**
   - Implementada suíte `GameLibraryRepositoryTest` cobrindo cenários com diretório inexistente, caminho que não é diretório, diretório válido/legível e lista vazia.
   - Adicionado `org.json:json` em `testImplementation` permitindo teste real da persistência do cache sem stubs.
   - Todos os testes unitários passaram com 100% de sucesso.

2. **Validação no dispositivo real (Samsung Galaxy A12 - SM-A127M, Android 13):**
   - Script automatizado `docs/task/TASK-0098-validar-no-aparelho.py` executado com sucesso:
     - **Teste 1 (Preservação do cache):** Com 15 jogos em cache e pasta inacessível configurada (`/storage/emulated/0/PastaInexistente_12345`), o app abriu, executou a varredura e o cache permaneceu com exatamente 15 jogos (não foi zerado para `[]`).
     - **Teste 2 (Revarredura automática):** Cache zerado propositalmente nas preferências mantendo a chave. Ao iniciar o app, a revarredura automática disparou no boot (`pendingInitialScan = true`) e repovoou os 15 jogos nas SharedPreferences sem necessidade de intervenção do usuário.

