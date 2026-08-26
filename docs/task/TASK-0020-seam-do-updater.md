# TASK-0020: apontar o updater para o nosso canal e encerrar o conceito de nightly

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0020:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Fechar o que a [TASK-0019](TASK-0019-mecanismo-de-atualizacao.md) deixou aberto: fazer a UI de
atualização deles consultar o **nosso** `version.json` em vez da API de releases do GitHub deles.

## Por que isto também resolve o R8

A TASK-0019 verificou no DEX que o `AppUpdateManager` **não estava no APK**: ninguém o referenciava,
e o R8 removeu a classe inteira junto com a constante do endpoint. Trocar o seam é o que cria a
referência — as duas coisas são a mesma mudança, e a verificação desta task é justamente confirmar
no DEX que a classe passou a existir.

## Escopo

**Entra:**
- `checkForUpdate` e `downloadAndInstall`, as duas funções privadas que a UI Compose deles chama,
  reescritas para delegar ao `AppUpdateManager` por `suspendCancellableCoroutine`. **A UI deles
  fica intacta** — diálogo, barra de progresso, strings, tudo.
- `UpdateState.Available` passa a carregar o `AppUpdateManager.UpdateInfo`, porque o download
  precisa do `sha256` e a UI só mostra `version`/`notes`.
- Remoção do que era do GitHub: `LATEST_URL`, `RELEASES_URL`, `httpGet`, `apkAssetForThisDevice`,
  `isNewer`, `nightlyTagDay`, `epochSecToYyyymmdd`, `supportsV82Build` e os marcadores `-sdkNN`.
- Remoção do toggle **"incluir builds nightly"** e do texto "você está num nightly", com as três
  chaves de i18n correspondentes apagadas em todos os idiomas.
- `update.checkOnLaunch.desc` corrigido: dizia *"check GitHub for a new version"*.

**NÃO entra:**
- Notas de versão. O nosso `version.json` não carrega `notes`, então a UI cai no
  `update.notesUnavailable` dela mesma. Acrescentar um campo no publicador é o caminho, se um dia
  quisermos mostrar changelog.

## O que "nightly" era, e por que não temos

Do lado deles é um canal de **builds diários automáticos**: um workflow (`nightly.yml`) roda às
08:00 UTC, compila e publica um *pre-release* com tag `nightly-YYYYMMDD`, cujo `versionCode` é o
**timestamp Unix em segundos** — por isso sempre maior que qualquer estável (os deles são ~1300). O
toggle deixava o usuário optar por receber esses builds.

O RetroSystem PS2 publica **um** `version.json`, com `channel = "default"` e a série 38, 39… Não há
o que o toggle oferecesse, e um controle que não faz nada é o mesmo defeito que este projeto já
catalogou duas vezes: `isNativeInitializationSucceeded` sem consumidor e o toggle de gravar log que
não ligava log nenhum.

## A diferença que não é de endereço

O updater deles **não verifica hash nenhum**. O nosso verifica o SHA-256 declarado no
`version.json`, e isso não é zelo abstrato: a URL canônica do APK, atrás do cache de borda,
continua servindo os bytes da versão **anterior** por um tempo depois do upload, ignorando
`Cache-Control`. Sem a verificação, o app instala o APK errado e não percebe.

## Como validar

1. Compila com R8 ligado.
2. **No DEX do APK de release**: `AppUpdateManager` presente, e a URL do nosso `version.json`
   presente. Era exatamente o que faltava na TASK-0019.
3. Em aparelho: o app oferece a atualização quando o `version.json` anuncia um `versionCode` maior,
   e **recusa** quando o SHA-256 não bate.

A validação 3 depende de aparelho.

## Resultado

Entregue, e a verificação no DEX fechou o que a TASK-0019 tinha deixado em aberto:

| No DEX do release | TASK-0019 | agora |
|---|---|---|
| `AppUpdateManager` | ausente | **presente** |
| a URL do nosso `version.json` | ausente | **presente** |
| `retrosystem-ps2-update.apk` | ausente | **presente** |
| `update.includeNightly` / `update.onNightly` | presentes | **ausentes** |

### Um erro que só a verificação pegou

Eu tinha dado o nightly por removido depois de apagar o toggle e as chaves de i18n. O `grep` no DEX
mostrou `update.includeNightly` ainda lá. Origem: `ui/settingshub/SettingsSearchIndex.kt`, um índice
de busca das Configurações que continuava listando a linha apagada — a busca ofereceria um item que
não existe mais. Removido.

É o mesmo padrão que esta sessão já viu duas vezes: **a árvore compilar não prova que o binário está
certo.** Aqui foi um índice; na TASK-0019 foi o R8 apagando a classe inteira.

### Achado que não é desta task

`main/java/com/armsx2/News.kt` também consulta `api.github.com/repos/ARMSX2/ARMSX2/releases` — é a
tela de novidades **deles**. No RetroSystem PS2 ela mostraria o changelog do ARMSX2. É identidade,
não updater, e precisa de decisão própria: apontar para outro lugar ou remover a tela.

A validação em aparelho (oferecer a atualização e **recusar** um SHA-256 que não bate) continua
pendente.
