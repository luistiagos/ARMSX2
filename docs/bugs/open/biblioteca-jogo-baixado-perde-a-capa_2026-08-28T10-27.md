# Bug: o jogo perde a capa assim que é baixado

- **Detectado em:** 2026-08-28 10:27 (mesmo relato do usuário: "no catálogo tinham capa e depois de
  baixados aparecem sem")
- **Origem:** `ui/home/HomeViewModel.kt` — `mergeCatalog`; e `GameInfo.coverUrl` (`GameInfo.kt:317`)
- **Errors (serviço):** nenhum — é visual
- **Classe:** regressão de dado na fusão catálogo↔disco
- **Reincidência:** primeira vez registrada
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0045](../../task/TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md)

## Sintoma

A linha do catálogo mostra a capa do manifesto. Terminado o download, a **mesma** linha — agora na
sua forma local — fica sem capa nenhuma.

## Causa raiz

`GameInfo.coverUrl` monta a URL a partir do **serial**, e só cai no `catalogCoverUrl` quando não há
serial:

```kotlin
val coverUrl: String? get() = serial?.let { ... } ?: catalogCoverUrl?.takeIf { it.isNotBlank() }
```

E `mergeCatalog`, ao converter a linha do catálogo na linha do arquivo em disco, copia **só** o
vínculo, nunca a capa:

```kotlin
if (fileName != null && fileName in byFileName) game.copy(catalogFileName = fileName) else game
```

O serial vem de sondar o disco (`GameLibraryRepository.probeRaw` → `NativeApp.getGameSerialFromFd`).
Se a sonda não devolve serial — e não devolve para os arquivos entregues com formato errado
([bug](catalogo-download-entrega-formato-nao-bootavel_2026-08-28T10-27.md)), nem para qualquer dump
que o núcleo não consiga ler —, sobram os dois lados nulos: sem serial e sem `catalogCoverUrl`.
Resultado: sem capa.

**São dois defeitos, não um.** O formato errado explica por que a sonda falha nestes jogos; o
`mergeCatalog` explica por que a falha da sonda custa a capa que o app **já tinha em mãos**. Corrigir
só o download deixaria o segundo de pé para todo dump sem serial legível.

## Situação

Endereçado pela [TASK-0045](../../task/TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md).
Aberto até a validação no aparelho.
