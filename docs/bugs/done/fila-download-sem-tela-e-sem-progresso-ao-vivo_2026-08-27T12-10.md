# Bug: a fila de download não tem tela, e o progresso não chega na biblioteca

- **Detectado em:** 2026-08-27 12:10 (relato do usuário, reproduzido no aparelho)
- **Origem:** `ui/home/HomeScreen.kt` (`GameCover`/`CoverStateBadge`) + `ui/home/HomeViewModel.kt`
  (`republish`, `loadCatalog`) — o motor em `catalog/DownloadQueueManager.java` está intacto
- **Errors (serviço):** nenhum — não é crash; nada é reportado porque nada falha
- **Classe:** regressão de porte (funcionalidade da versão anterior não veio para o fork)
- **Reincidência:** primeira vez; não existe na `version1`
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0038](../../task/TASK-0038-fila-de-download-visivel.md)

## Sintoma

Relato: *"quando clicamos em um jogo e colocamos para iniciar o download nada acontece, não aparece
a fila de download e o download sendo feito igual aparecia na aplicação antiga do branch
version1."*

Reproduzido no SM-A127M (Android 13) com o APK do fork `1.0.24` (versionCode 38):

1. Toque num jogo do catálogo → o painel abre normalmente.
2. Toque em **Baixar** → o painel fecha e a tela não muda nada.
3. Por baixo, **o download começa de verdade**: `acquireWifiLock uid=10259`, `setNumberOfFgs: 1`,
   DNS resolvido, e `007 - Nightfire (Japan).iso.part` cresce 180 MB → 446 MB → 1,02 GB → 1,23 GB.
4. Aos 8 s do toque, com 180 MB já no disco, a tarja do cartão ainda é o `↓` cinza. Aos 40 s,
   noutro teste, idem.
5. Digitando na busca (que reconstrói a lista e força recomposição) a tarja **aparece com `34%`** —
   e congela ali de novo.

## Causa raiz

São **dois defeitos independentes**, e o relato junta os dois.

### 1. Não existe tela de fila no fork

`DownloadQueueManager.getActiveQueue()` não é chamado por nenhuma UI — só por
`DownloadForegroundService`, para a notificação (que neste aparelho está bloqueada,
`importance=NONE`, e o app **nunca pede `POST_NOTIFICATIONS` em runtime**). A `version1` tinha a
seção "Saving" em `HomeActivity` (`ll_queue_section`/`ll_queue_items`) inflando
`res/layout/item_download_queue.xml` por item: capa 56×72, título, status
`%1$s MB of %2$s MB (%3$d%%)`, barra de progresso, botão pausar/retomar e botão cancelar. Nada
disso foi portado.

### 2. O progresso ao vivo não invalida o cartão

A cadeia pretendida é `DownloadQueueManager` notifica → `HomeViewModel.onProgress` → `republish()`
→ `CatalogLibrary.bump()` + `state.tick++` → `GameCover` (que lê `CatalogLibrary.version.intValue`)
recompõe. Conferido no DEX do release que **todos os elos existem e o R8 não removeu nenhum**:

```
0fd730: iput-object v1, v2, Lxn0;.n        ← setRomsDir(dir)
0fd734: iget-object v2, v2, Lxn0;.j        ← addListener(this): contains/add
2cdeb8: sget-object v0, Lb00;.a:Llt2;      ← GameCover lê CatalogLibrary.version…
2cdebc: invoke-virtual {v0}, Llt2;.h:()I   ← …chamando getIntValue()
2d267c: sget/add-int/setIntValue           ← republish() incrementa a versão
122802: invoke-interface {v0, v2}, Lwn0;.a ← o laço de despacho chama onProgress
```

Mesmo assim o cartão não redesenha. O mecanismo é frágil por construção: o downloader **muta um
objeto Java compartilhado** (`CatalogEntry`) e sinaliza por um contador global; os parâmetros que
os composables recebem nunca mudam de valor, então a única coisa que pode redesenhar a tarja é a
invalidação por esse contador — e ela não está chegando no build publicado.

Duas fragilidades estruturais no mesmo caminho:

- `loadCatalog()` faz `if (CatalogLibrary.entries.isNotEmpty()) return` **antes** de `setRomsDir` e
  `addListener`. Como `CatalogLibrary` é um `object` de processo e `onCleared()` remove o listener,
  qualquer segundo `HomeViewModel` fica mudo para sempre. Na `version1` o `addListener` era
  incondicional no `onCreate`, pareado com `onDestroy`.
- `CatalogEntry.downloadedBytes`/`totalBytes` existem no fonte mas o R8 os elimina do APK, porque
  nenhuma UI os lê — prova mecânica de que a tela de fila sumiu no porte.

## Como reproduzir

```
adb shell monkey -p come.nanodata.armsx2 -c android.intent.category.LAUNCHER 1
# tocar num jogo do catálogo → "Baixar"
adb shell "ls -l /sdcard/Android/data/come.nanodata.armsx2/files/roms/ | grep part"   # cresce
adb shell screencap -p /sdcard/s.png                                                  # tarja: ↓
```

O arquivo cresce; a tarja não muda.

## Correção

[TASK-0038](../../task/TASK-0038-fila-de-download-visivel.md), em três movimentos:

1. **A fila virou valor.** `HomeUiState` ganhou `queue: List<DownloadQueueItem>` e
   `downloads: Map<String, DownloadQueueItem>`, remontados a cada callback a partir de
   `getActiveQueue()`. Item imutável ⇒ progresso diferente produz estado diferente ⇒ o redesenho
   passa a ser consequência das regras normais do Compose.
2. **A seção de fila voltou**, em `ui/catalog/DownloadQueueSection.kt`, com o conteúdo e as regras
   de visibilidade de `item_download_queue.xml`.
3. **O contador global saiu.** `CatalogLibrary.version`/`bump()` foram removidos, e a tarja da capa
   passou a ler `LocalDownloadStates` (um `compositionLocalOf` dinâmico, não `static`: o mapa muda
   duas vezes por segundo e a variante estática recomporia a grade inteira).

`setRomsDir` + `addListener` saíram de dentro do `if (entries.isNotEmpty()) return`.

## Validação (device físico)

SM-A127M (Android 13, SDK 33), APK `github/release` com R8 ligado — o mesmo perfil de build em que
o defeito aparecia:

| Passo | Resultado |
|---|---|
| Tocar "Baixar" | Seção **BAIXANDO** em 4 s, com `0,0 MB de 2079,4 MB (0%)` |
| Esperar sem tocar em nada | `0%` → `9%` → `21%` → `30%` → `37%`, batendo com o `.part` no disco |
| Tarja do cartão | Sai de `↓` e acompanha (`21%`, `30%`) |
| Pausar | Status `Pausado`, arquivo parado em 346.799.867 bytes em duas leituras |
| Retomar | Volta a crescer do mesmo ponto (346,8 MB → 386,8 MB) |
| Enfileirar um segundo | Entra como `Aguardando…`, sem barra e sem botão primário; tarja `⋯` |
| Cancelar | Linha some e o `.part` é apagado do disco |

Resolvido.
