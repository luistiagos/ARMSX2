# Bug: a notificação de progresso do download nunca aparece no Android 13+

- **Detectado em:** 2026-08-27 22:20 (confirmado no aparelho durante a TASK-0040)
- **Origem:** `AndroidManifest.xml` declara `POST_NOTIFICATIONS`, mas nenhum ponto do app a pede em
  runtime; `catalog/DownloadForegroundService.java` posta numa notificação que o sistema descarta
- **Errors (serviço):** nenhum — não é crash; o sistema simplesmente não mostra
- **Classe:** funcionalidade inerte (permissão declarada e nunca solicitada)
- **Reincidência:** primeira vez registrada; existe desde que o fork nasceu
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0041](../../task/TASK-0041-permissao-de-notificacao-do-download.md)

## Sintoma

Com um download em andamento e o app fechado, não há nenhum sinal de que algo está acontecendo. A
notificação de progresso do serviço de primeiro plano — que existe, é construída e é atualizada a
cada 500 ms — nunca chega à tela.

No aparelho de teste:

```
$ adb shell dumpsys notification --noredact | grep come.nanodata.armsx2
AppSettings: come.nanodata.armsx2 (10259) importance=NONE userSet=false
```

`userSet=false` é a parte que interessa: **não foi o usuário que desligou**. Nasceu assim.

## Causa raiz

O app tem `targetSdk=37`. No Android 13 (API 33) em diante, `POST_NOTIFICATIONS` é permissão de
runtime: declarar no manifesto não basta, e enquanto não for concedida o sistema **descarta** o que
o app posta — inclusive a notificação de um serviço de primeiro plano, que continua rodando, só que
invisível.

`grep` por quem pede, em todo o app:

```
$ grep -rn "RequestPermission\|POST_NOTIFICATIONS" app/src/main/java/com/armsx2/
(nada)
```

Ninguém pede. A única `checkSelfPermission` do projeto está em `org/libsdl/app/SDLActivity.java`, do
SDL, e não trata desta permissão.

## Efeito colateral que isto escondeu

Como a notificação nunca foi vista, o texto dentro dela nunca foi revisto — e está errado:

```java
.setContentTitle("ARMSX2 — Download")          // nome do produto do upstream
new NotificationChannel(CHANNEL_ID, "ROM Downloads", …)   // inglês fixo
channel.setDescription("Downloads de ROMs em segundo plano");  // português fixo
buildNotification("Iniciando download...", -1)            // português fixo
notify("Download em fila...", -1, true)                   // português fixo
```

Conceder a permissão sem mexer nisso publicaria "ARMSX2" na aba de notificações de todo usuário.

## Como reproduzir

```
adb shell pm revoke come.nanodata.armsx2 android.permission.POST_NOTIFICATIONS
# iniciar um download pelo app, depois fechá-lo
adb shell dumpsys notification --noredact | grep come.nanodata.armsx2   # importance=NONE
```

O `.part` cresce em `files/roms/`; a aba de notificações fica vazia.

## Correção

[TASK-0041](../../task/TASK-0041-permissao-de-notificacao-do-download.md): o pedido acontece ao
entrar na tela de Downloads **com a fila não vazia** — o instante em que a notificação passa a ter
função — e o texto que se torna visível foi corrigido junto (título de `R.string.app_name`, canal e
mensagens por `I18n.get`).

Sem flag de "já perguntei": quem limita é o Android, que exibe o diálogo no máximo duas vezes e
depois nega em silêncio. Guardar flag própria só tiraria do usuário a segunda chance.

## Validação (device físico)

SM-A127M (Android 13), `github/release`, partindo de `granted=false`:

| Passo | Resultado |
|---|---|
| Baixar → tela de Downloads | Sistema pede: *"Permitir que o app RetroSystem PS2 envie notificações?"* |
| Permitir | `importance=NONE userSet=false` → `importance=DEFAULT userSet=true` |
| Notificação | `android.title=RetroSystem PS2`, `android.text=` nome do jogo, `android.progress=14` |
| Canal | `mName=Downloads de ROMs`, reescrito num canal já existente |
| App em segundo plano | Progresso segue: `14` → `19` |
| Downloads com fila vazia | Não pede nada; `granted=false` intacto |

Resolvido.
