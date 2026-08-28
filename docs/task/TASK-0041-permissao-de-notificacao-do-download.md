# TASK-0041: pedir a permissão de notificação, e arrumar o que a notificação diz

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [notificacao-de-download-invisivel-sem-pedir-permissao](../bugs/done/notificacao-de-download-invisivel-sem-pedir-permissao_2026-08-27T22-20.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0041:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pendência levantada na [TASK-0038](TASK-0038-fila-de-download-visivel.md) e confirmada no aparelho
durante a [TASK-0040](TASK-0040-fila-de-download-em-tela-propria.md): o app declara
`POST_NOTIFICATIONS` no manifesto mas **nunca pede em runtime**. Com `targetSdk=37` no Android 13+ a
permissão nasce negada, e o `dumpsys notification` confirma:

```
AppSettings: come.nanodata.armsx2 (10259) importance=NONE userSet=false
```

O serviço de primeiro plano roda, o download anda, e a notificação de progresso — a superfície que
mostra o download com o app fechado — é invisível.

## Escopo

**Entra:**

1. **Pedir a permissão**, uma vez, ao entrar na tela de Downloads **com a fila não vazia** — o
   momento em que a notificação passa a ter função. Nada é pedido no boot, nem com a fila vazia.
2. **Arrumar o texto que passa a ser visto.** Tornar a notificação visível transforma quatro
   literais de código em texto de produto, e hoje eles estão errados:

   | Onde | Hoje | Problema |
   |---|---|---|
   | `setContentTitle` | `"ARMSX2 — Download"` | nome do produto do upstream, não o nosso |
   | Nome do canal | `"ROM Downloads"` | inglês fixo |
   | Descrição do canal | `"Downloads de ROMs em segundo plano"` | português fixo |
   | `onStartCommand` | `"Iniciando download..."` | português fixo |
   | `onQueueChanged` | `"Download em fila..."` | português fixo |

   O título passa a vir de `R.string.app_name` — **um** lugar decide o nome do produto, como fixou a
   [TASK-0017](TASK-0017-identidade-do-produto.md) — e os demais passam por `I18n.get`.

**Fica de fora, deliberadamente:**

- **Insistir depois de negada.** O próprio Android limita o pedido a duas exibições e depois nega em
  silêncio; é ele quem decide. Não guardo flag nem invento um segundo caminho de pedido.
- **Uma linha em Configurações para reabrir o pedido.** Depois de negado de vez, só as configurações
  do sistema reativam — e mandar o usuário para lá é outra decisão de UI, não desta task.
- Tocar no throttle, no ícone ou no `PendingIntent` da notificação: funcionam.

## Por que na tela de Downloads

É onde o pedido se explica sozinho: o usuário acabou de mandar baixar e está olhando a fila. Pedir
no boot é pedir sem contexto para quem talvez nunca baixe nada — e é o padrão que o Android
desaconselha explicitamente.

## Como validar

No SM-A127M (Android 13), começando com a permissão negada
(`adb shell pm revoke come.nanodata.armsx2 android.permission.POST_NOTIFICATIONS`):

1. Biblioteca → jogo do catálogo → **Baixar**.
2. Ao chegar na tela de Downloads, o sistema pede a permissão de notificação.
3. Permitir → `dumpsys notification` sai de `importance=NONE`.
4. Puxar a aba de notificações: aparece **"RetroSystem PS2"** (não "ARMSX2") com o nome do jogo e a
   barra de progresso andando.
5. Sair do app: a notificação continua lá, andando.
6. Abrir a tela de Downloads com a fila vazia: **não** pede nada.

## Resultado

Entregue. SM-A127M (Android 13), APK `github/release`, começando com
`pm revoke … POST_NOTIFICATIONS` e conferindo `granted=false` antes de cada rodada:

| Passo | Resultado |
|---|---|
| Baixar → tela de Downloads | O sistema pede: *"Permitir que o app **RetroSystem PS2** envie notificações?"*, sobre a fila já rodando |
| Permitir | `importance=NONE userSet=false` → `importance=DEFAULT userSet=true` |
| Conteúdo da notificação | `android.title=RetroSystem PS2`, `android.text=007 - Everything or Nothing (Europe) (En,Es,It,Nl,Sv)`, `android.progress=14` |
| Canal | `mName=Downloads de ROMs` — atualizado **num canal que já existia**, confirmando que recriar com o mesmo id reescreve nome e descrição |
| App em segundo plano | Notificação continua, progresso `14` → `19` |
| Downloads com fila vazia | **Não pede nada**; `granted=false` intacto |

O título saiu de `"ARMSX2 — Download"` para `RetroSystem PS2`, lido de `R.string.app_name`. Era o
risco concreto desta task: conceder a permissão sem mexer no texto publicaria o nome do upstream na
aba de notificações de todo usuário.

### Nota sobre o aparelho de teste

Deixei a permissão **concedida** ao terminar (`pm grant`), para o app não ficar com a notificação
muda por causa dos meus testes.

### Achado fora do escopo

Ao lançar um jogo por engano durante o teste, o emulador mostrou o aviso do upstream sobreposto à
tela: *"You are using ARMSX2, and it should not be sold, or distributed as part of any other app. If
you paid for this app, you should get your money back."* Não mexi nisso — é decisão de produto e de
licença, não de implementação, e merece task própria.
