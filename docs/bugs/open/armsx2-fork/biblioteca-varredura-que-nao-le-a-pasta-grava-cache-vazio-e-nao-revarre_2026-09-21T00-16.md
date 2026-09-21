# Bug: uma varredura que não consegue ler a pasta grava cache vazio como sucesso, e a biblioteca fica vazia até o ↻ manual

- **Detectado em:** 2026-09-21 00:16 (achado ao investigar o relato de cliente de 2026-09-20:
  "os jogos que tinha baixado no app sumiram")
- **Origem:** **delta do fork** — `GameLibraryRepository` e `HomeViewModel` são nossos
- **Errors (serviço):** nenhum — a falha de leitura é engolida por `runCatching` e nunca vira erro
- **Classe:** fail silencioso; estado ruim persistido por cima de estado bom
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda
- **Relacionado:**
  [a pasta de ROMs semeada uma vez não acompanha a raiz de dados](../../done/biblioteca-pasta-de-roms-semeada-uma-vez-nao-acompanha-a-raiz-de-dados_2026-09-21T00-16.md)
  — outro jeito de a varredura olhar para uma pasta que não é a dos jogos

## Sintoma

A biblioteca abre **vazia** — "nenhum jogo" — num aparelho que tinha jogos na abertura anterior.
Nada mudou de configuração. Fechar e abrir de novo **não** resolve. Tocar no ↻ da barra resolve,
se a pasta voltou a ser legível; o usuário não tem como saber disso.

Gatilhos plausíveis, todos externos ao app: cartão SD que ainda não montou quando o app abriu
(pasta de ROMs no SD, ou armazenamento do app no SD), armazenamento emulado ainda em
"verificando" logo após o boot do aparelho, permissão de todos-os-arquivos revogada, pasta SAF cuja
permissão persistida caiu.

## Evidência — a cadeia, verificada elo a elo

1. **Falha de leitura é silêncio, não erro.**
   [`scanRawDirectory`](../../../../platforms/android/app/src/main/java/com/armsx2/data/library/GameLibraryRepository.kt#L232):

   ```kotlin
   val children = runCatching { directory.listFiles() }.getOrNull() ?: return
   ```

   (`:239`; o mesmo padrão em `scanDocumentTree`, `:218`). `listFiles()` devolve `null` quando a
   pasta não pode ser lida; a função devolve sem acrescentar nada e sem sinalizar.

2. **Uma pasta POSIX ilegível cai num ramo que também não produz nada.**
   [`scan()`](../../../../platforms/android/app/src/main/java/com/armsx2/data/library/GameLibraryRepository.kt#L76):
   `plainDir = rawUri.takeIf { it.startsWith("/") }?.let(::File)?.takeIf { it.isDirectory && it.canRead() }`
   (`:91`). Se `canRead()` é falso, `plainDir` é nulo, o caminho vira `Uri` sem esquema, e
   `DocumentFile.fromTreeUri(context, uri)` (`:103`) devolve nulo. Resultado: zero jogos, sem erro.

3. **O resultado vazio é persistido por cima do cache bom, incondicionalmente.**
   `:106`: `collected.values.sortedBy { ... }.also { saveCache(directories, it) }`.
   [`saveCache`](../../../../platforms/android/app/src/main/java/com/armsx2/data/library/GameLibraryRepository.kt#L312)
   grava `gamesCache = []` e `gamesCacheKey = <as mesmas pastas>`.

4. **Para o `HomeViewModel`, isso é sucesso.**
   [`refresh()`](../../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L231):
   `runCatching { repository.scan(directories) }.onSuccess { games -> localGames = games; ... }`.
   Só uma exceção cairia em `onFailure`, e o passo 1 garante que não há exceção.

5. **Na abertura seguinte, o cache vazio é aceito e nenhuma revarredura automática acontece.**
   [`load()`](../../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L184):
   `pendingInitialScan = romDirectories.isNotEmpty() && cached.key != repository.cacheKey(romDirectories)`
   (`:199`). A chave é a lista de pastas, que não mudou, então `pendingInitialScan` é falso;
   `initialized = validCached.isNotEmpty() || !pendingInitialScan` (`:216`) é verdadeiro; a tela
   mostra o estado "nenhum jogo" em vez de "varrendo". `refresh()` só é chamado no `load` quando
   `pendingInitialScan` é verdadeiro (`:225`, `:227`).

Portanto uma **única** varredura num instante em que a pasta não estava legível converte uma
biblioteca cheia numa biblioteca vazia **persistente**, e o app não tenta de novo sozinho.

## Impacto

- Para o usuário é indistinguível de "meus jogos foram apagados".
- O caminho de recuperação (↻) existe mas não é sugerido em lugar nenhum; o estado vazio da
  biblioteca aponta para a configuração de pastas, não para revarrer.
- Com a pasta própria do app no armazenamento interno o gatilho é raro (armazenamento emulado
  ainda montando no boot do aparelho); com cartão SD ou pasta SAF é rotina.

## Correção proposta

Duas mudanças pequenas, independentes:

1. **Não persistir o que não foi lido.** `scan()` passa a saber quais pastas leu de fato (contar
   as que caíram no `?: return`/`plainDir == null`). Se alguma pasta ficou sem leitura, devolver o
   resultado parcial para a tela mas **não** chamar `saveCache` — ou gravar o cache só das pastas
   lidas. O cache anterior sobrevive ao arranque ruim.
2. **Cache vazio não conta como cache.** Em `load()`, tratar `cached.games.isEmpty()` com pastas
   configuradas como motivo para `pendingInitialScan = true`. É uma varredura a mais só quando a
   biblioteca está vazia — barata por definição.

Opcional: quando uma pasta configurada não pôde ser lida, mostrar um aviso na biblioteca com o ↻
como ação, em vez do estado "nenhum jogo".

## Como validar

Sem aparelho especial:

1. Com jogos na biblioteca, revogar "Acesso a todos os arquivos" nas configurações do Android para
   uma pasta de ROMs fora de `Android/data`, ou desmontar o cartão SD que contém a pasta.
2. Abrir o app: **hoje** a biblioteca fica vazia e `gamesCache` nas prefs vira `[]`.
3. Devolver a permissão / montar o cartão e abrir o app de novo: **hoje** continua vazia;
   **esperado** os jogos voltam sozinhos.
4. Teste unitário: `scan()` com uma pasta que devolve `listFiles() == null` não pode chamar
   `saveCache` com lista vazia.
