# TASK-0101: a rota longa para de mandar o usuário a um app pago

- **Status:** aberta
- **Criada em:** 2026-09-22
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/armsx2-fork/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** —
- **Revertida por:** —
- **Publicado em:** —

## Contexto

O passo 2 do assistente manda instalar o **LADB**, que é **pago** (≈US$ 3–4 na Play), e o passo 1
promete *"You are going to install a **free** app"*. Anunciamos de graça e cobramos — e o
`tytydraco/LADB` **não publica release nenhum**, então a saída "compila do GitHub" não é instrução
para cliente.

Existe substituto grátis com a mesma função:

| Ferramenta | Grátis | Onde | Apps | Observação |
|---|---|---|---|---|
| **aShell You** (`in.hridayan.ashell`) | sim, GPL-3.0+ | [F-Droid](https://f-droid.org/packages/in.hridayan.ashell/), [IzzyOnDroid](https://android.izzysoft.de/repo/apk/in.hridayan.ashell), [GitHub](https://github.com/DP-Hridayan/aShellYou/releases) — **não está na Play** | 1 | o wiki declara *"Own device as target"* por depuração sem fio, **sem Shizuku**, e lê o código de pareamento pela **notificação** |
| Shizuku + **aShell** (`in.sunilpaulmathew.ashell`) | sim, os dois | Play e F-Droid | 2 | plano B: mais um app, o `Start` do Shizuku e um diálogo de permissão entre eles |

O sideload não é barreira para o nosso público: **quem tem o emulador já instalou um APK de fora.**

**O que não está apurado, e por isso é a primeira coisa da task:** o modo "próprio aparelho" do
aShell You não foi exercido por nós, e a ficha do F-Droid é ambígua sobre ele (lista
*"Shizuku, ROOT or Wireless Debugging"* e fala de *outros* aparelhos por OTG/Wi-Fi). Ensinar um
caminho que ninguém aqui percorreu é o erro do "Forçar parada" outra vez — aquele texto prometia
conserto e entregava alívio.

## Objetivo

O assistente da rota longa deixa de custar dinheiro ao usuário: ensina uma ferramenta **grátis**, e
o texto do passo 1 passa a ser verdade. O comando ensinado é o que sobrevive no aparelho — medido,
não herdado.

## Escopo

**Entra:**

1. **Medição que escolhe a ferramenta, antes de escrever texto** (no `SM-A127M`):
   - a. O aShell You pareia com o **próprio** aparelho por depuração sem fio, **sem Shizuku**?
   - b. Ele executa `pm disable-user --user 0 com.samsung.android.game.gos` com sucesso?

   Passou nas duas → é ele, e o assistente fica com **um** app. Falhou em qualquer uma → plano B
   (Shizuku + aShell), e o texto passa a dizer "dois apps grátis". Conferir o id do pacote do
   Shizuku na própria página da loja antes de escrevê-lo no código — não herdar id de memória.

2. **Medição da durabilidade do comando**, que decide qual comando o assistente ensina:
   `pm disable-user`, `adb reboot`, e `pm list packages -d | grep gos` depois do boot. O nosso guia
   afirma que sobrevive e **admite não ter testado**; o [droidwin](https://droidwin.com/disable-uninstall-samsung-game-optimizing-service-app-via-adb/)
   afirma o contrário e recomenda `pm uninstall -k --user 0`. Se não sobreviver:
   - `FIX_COMMAND` passa a ser `pm uninstall -k --user 0 com.samsung.android.game.gos`;
   - a tela de conferência muda (não há mais `new state: disabled-user` para procurar);
   - a tela do desfazer muda — o inverso de um `uninstall --user 0` é `pm install-existing`, e não
     `pm enable`. Conferir o texto exato da resposta no aparelho antes de escrevê-lo na tela.

3. **Passo 2 reescrito.** `LADB_PACKAGE` sai; `openPlayStore()` perde o sentido do nome e do
   comportamento, porque o aShell You **não está na Play**: vira `ACTION_VIEW` para a página do
   F-Droid (URL estável, com botão de download visível), com a de releases do GitHub como
   alternativa no texto.

   **O app não instala o APK e não vai passar a instalar.** Ele não declara
   `REQUEST_INSTALL_PACKAGES` — e o `build-play-aab.sh` **reprova o build** se essa permissão
   sobreviver no bundle (o comentário está no `AndroidManifest.xml`). Quem instala é o navegador ou o
   F-Droid, então o texto precisa avisar, em uma frase de leigo, do pedido *"permitir instalar apps
   desconhecidos"* que vai aparecer.

4. **A promessa do passo 1 passa a ser verdadeira** — "a free app" só fica na tela se a ferramenta
   escolhida for grátis de fato, e o número de apps no texto tem de bater com o caminho escolhido em
   (1).

5. **A tela da tela dividida muda de função.** O passo 6 existe só porque o código de pareamento
   expira e desaparece — é o erro nº 1 de quem tenta. Se o aShell You o lê da notificação, a tela
   passa a ser "conceda o acesso às notificações a ele", que é um toque em vez de um truque. Se o
   plano B vencer, a tela continua como está.

6. **Strings em `BASE_EN` e em `assets/i18n/pt-BR.json`** — as duas, porque pt-BR é o idioma do nosso
   cliente e já traz as 32 chaves do assistente.

**Não entra:**

- A **rota nativa** do Game Booster — é a [TASK-0100](TASK-0100-assistente-da-rota-nativa-do-game-booster.md).
  Esta task só mexe nas telas da rota longa.
- **Pareamento dentro do nosso app** — [`backlog/pareamento-adb-dentro-do-app`](../backlog/pareamento-adb-dentro-do-app.md).
- **Instalar o APK pelo app.** Ver item 3: a permissão é barrada de propósito pelo guard do build.
- Traduzir para os outros 17 idiomas; degradam para o inglês, como hoje.

## Medições (2026-09-22, `SM-A127M`, Android 13 / One UI, pt-BR)

Feitas **antes** de qualquer string mudar, como manda a validação. O que não foi exercido está
dito como não exercido.

### Item 2 do escopo — o `pm disable-user` sobrevive ao reboot. **Sobrevive.**

Estado antes: `uptime` de 1.049.257 s (12,1 dias) e o pacote já em `enabled=3`
(`disabled-user`), `installed=true` — ou seja, o aparelho **nunca tinha reiniciado** desde que o
comando foi dado. Era exatamente o buraco que o guia admitia.

| passo | comando | resposta medida |
|---|---|---|
| aplica | `pm disable-user --user 0 com.samsung.android.game.gos` | `Package com.samsung.android.game.gos new state: disabled-user` |
| reinicia | `adb reboot`, espera `sys.boot_completed=1` | uptime 159 s |
| confere | `pm list packages -d \| grep gos` | `package:com.samsung.android.game.gos` |
| confere | `dumpsys package … \| grep enabled=` | `enabled=3`, `installed=true` |

**Consequência:** o `FIX_COMMAND` **não muda**. O droidwin não vale para este aparelho, o
`pm uninstall -k --user 0` não é necessário, e as telas 9 e 10 continuam válidas — mas agora
medidas, não herdadas:

- a tela 9 procura `new state: disabled-user`, que é **literalmente** a resposta do aparelho;
- o desfazer da tela 10 foi exercido: `pm enable com.samsung.android.game.gos` responde
  `Package com.samsung.android.game.gos new state: enabled` e leva o pacote a `enabled=1`. O
  aparelho foi devolvido a `enabled=3` logo em seguida.

Corroboração independente, já depois do reboot e com um jogo em primeiro plano: `scaling_cur_freq`
da cpu0 em **2002000** e o overlay do app marcando `Speed: 99% (T: 100%)` — o teto de 1053 MHz do
bug não voltou.

### Item 1 do escopo — a ferramenta. **Parcial: 1a confirmado no essencial, 1b não exercido.**

Medido sobre o **aShell You `in.hridayan.ashell` v7.4.0 (versionCode 62)**, baixado de
`https://f-droid.org/repo/in.hridayan.ashell_62.apk` (11.490.894 bytes, `minSdk 28`,
`targetSdk 36`) e instalado no aparelho.

**A ambiguidade da ficha do F-Droid está resolvida: o modo "próprio aparelho" é de primeira
classe.** Ao tocar em `Parear`, o app pergunta *"Qual dispositivo você gostaria de parear?"* e a
**primeira** opção é **"Emparelhar este dispositivo"**, marcada como `Auto`. A segunda é *"Parear
outro dispositivo"*. Não é um uso torto de um recurso feito para outra coisa.

**Sem Shizuku e sem root.** O onboarding oferece os dois e diz, na própria tela, *"Conceder estas
permissões é opcional!"*. Só o modo `ADB Local` os exige (*"usando Shizuku ou root"*); o modo
`ADB via Depuração por Wi-Fi` não. O caminho inteiro abaixo foi percorrido com as duas recusadas.

**O app já vem em português** — *"ADB na ponta dos seus dedos"*, `Parear`, `Instruções`. Não
precisamos ensinar rótulo em inglês ao nosso cliente.

**O mecanismo da notificação, corrigido.** A tabela do Contexto dizia que ele *lê* o código pela
notificação. Não é isso, e a diferença importa para o texto: o app **posta uma notificação dele
mesmo**, e é **nela** que o usuário digita o código. O manifesto prova: **não há
`NotificationListenerService`**; há `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES`,
`CHANGE_WIFI_MULTICAST_STATE` e os serviços `…wifi_adb_shell.service.SelfPairingService` e
`AdbConnectionService`. A própria tela do app diz: *"Para completar o processo de emparelhamento,
você precisará interagir com uma notificação da aShell You."*

**E é um toque, como a task apostou.** A permissão chega **negada**
(`POST_NOTIFICATIONS: granted=false`, `importance=NONE`), e é isto que faria o usuário travar em
silêncio. Mas o app tem um botão `Configurações de notificação` que cai **direto** na tela de
notificações dele (`Settings$AppNotificationSettingsActivity`), onde um único toque em
*"Permitir notificações"* levou a `granted=true`. Nada de tela dividida.

**O que NÃO foi exercido, e continua em aberto:** o pareamento não chegou a se completar e o
`pm disable-user` não foi executado dentro do aShell You (item 1b). A medição foi interrompida
porque o aparelho passou a ser usado por uma pessoa no meio dela — o ARMSX2 veio para o primeiro
plano com um jogo carregando. **Enquanto 1b não for exercido, nenhuma string muda.**

### Rótulos do aparelho — dois que as nossas strings erram hoje

Medidos na UI em pt-BR, não herdados:

| onde | rótulo real no `SM-A127M` | o que o nosso texto manda procurar |
|---|---|---|
| Opções do desenvolvedor | **"Depuração por Wi-Fi"** | `step4`: *"Depuração sem fio"* ❌ |
| dentro dela | **"Parear o dispositivo com um código de pareamento"** | `step5`: *"Parear dispositivo com código de pareamento"* ❌ |

O passo 5 acerta no resto: a linha tem o **nome à esquerda**, que abre o menu, e a **chavinha à
direita**, que só liga — e o próprio aShell You avisa disso (*"a parte esquerda da opção de
depuração por Wi-Fi é clicável"*). A tela também oferece *"Parear o dispositivo com um código QR"*,
que não usamos.

## Como validar

No `SM-A127M`, com o APK instalado de fato, e **seguindo só as telas do app** — quem valida finge
ser o usuário leigo, sem usar `adb` para nada além de observar:

1. Os itens 1 e 2 do escopo medidos e **escritos na task** antes de a primeira string mudar.
2. Instalar a ferramenta pelo botão da tela, parear, colar o comando copiado e executar.
3. `pm list packages -d | grep gos` mostra o pacote (ou, no caminho do `uninstall`, `pm list
   packages --user 0 | grep gos` deixa de mostrar).
4. Abrir um jogo: velocidade cheia, e **o aviso de abertura não volta** na próxima abertura do app.
5. `adb reboot` e repetir o passo 3 — é o que prova que o texto não promete permanência falsa.
6. `I18nKeysTest` e os testes JVM do módulo.
7. `python scripts/check_traceability.py`.

## Notas para quem implementa

Abra antes de escrever:

- `ThrottleHelp`: `LADB_PACKAGE`, `FIX_COMMAND`, `openPlayStore()`, `copyCommand()`,
  `warnUnavailable()`, e os passos 2, 6, 8, 9 e 10 na `Host()`.
- `I18n` (cabeçalho, `languages`, `resolveSystemLanguage`) e `assets/i18n/pt-BR.json`.
- `AndroidManifest.xml`: o comentário do guard de `REQUEST_INSTALL_PACKAGES`, e o `<queries>`.
- `I18nKeysTest.kt`.

E dois tropeços registrados: o `market://` caiu num `ResolverActivity` no A12 (TASK-0059) — um
`ACTION_VIEW` de `https` pode cair no mesmo seletor, e não é defeito novo; e editar o `I18n.kt` pode
falhar com `Unresolved reference` por causa do Kotlin incremental, não do código.
