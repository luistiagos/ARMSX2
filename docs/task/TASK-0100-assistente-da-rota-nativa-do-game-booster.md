# TASK-0100: guiar o leigo pela chave nativa da Samsung quando o aparelho a tem

- **Status:** aberta
- **Criada em:** 2026-09-22
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/armsx2-fork/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** —
- **Revertida por:** —
- **Publicado em:** —

## Contexto

O assistente de hoje ([`ThrottleHelp`](../../platforms/android/app/src/main/java/com/armsx2/ui/common/ThrottleHelp.kt))
manda **todo** usuário Samsung pelo caminho do `pm disable-user` via **LADB** — 10 telas, opções do
desenvolvedor, pareamento por código — e o LADB é **pago** (≈US$ 3–4 na Play; o repositório
`tytydraco/LADB` não publica release nenhum, então "grátis no GitHub" significa compilar). A
primeira tela ainda promete *"You are going to install a **free** app"*
([`I18n.kt:1511`](../../platforms/android/app/src/main/java/com/armsx2/i18n/I18n.kt)) — cobramos do
usuário o que anunciamos de graça.

A Samsung tem a própria chave para desligar o corte. Em aparelho que a tem, são **quatro toques,
sem instalar nada**:

| One UI | Caminho |
|---|---|
| 7 e acima | **Gaming Hub → ⋮ (Mais opções) → Game Booster → Otimização de jogos → Desempenho** |
| 4.1 a 6 | **Game Launcher → Game Booster → ⋮ → Labs → *Alternate game performance management*** (opções Desempenho / Padrão / Economia de bateria) |

**Ela não existe na linha de entrada, e é a Samsung quem diz:** *"O Game Booster não é suportado no
Galaxy A02s, A03S e A14 5G"* ([suporte BR](https://www.samsung.com/br/support/apps-services/como-melhorar-o-desempenho-em-jogos-com-o-game-booster-da-samsung/)).
Bate com o que medimos no `SM-A127M`: as Configurações do Game Launcher do A12 trazem só visor,
notificações, privacidade, publicidade, sobre e ajuda — não há seletor de desempenho, e o ícone
flutuante não aparece nem com `game_show_floating_icon=1`.

**Duas honestidades, antes de qualquer código:**

1. **A rota nativa não foi medida por nós.** Não temos aparelho com o painel. A fonte é a
   documentação da Samsung; o efeito sobre o teto de clock **do nosso pacote** é desconhecido — o
   GOS continua *habilitado*, e "Desempenho" pode ou não desarmar o corte para um app que ele não
   conhece. O desenho trata a rota como **hipótese a confirmar no aparelho do usuário**, nunca como
   conserto anunciado.
2. **Nenhum nome de componente da Samsung entra no código sem dump.** Detectar o painel por
   `resolveActivity` de uma activity do Game Booster seria o sinal certo — e não temos como colher o
   nome dela aqui. Adivinhar é escrever código sobre símbolo que não se abriu. Fica registrado como
   o sinal a usar quando houver aparelho de linha superior à mão.

## Objetivo

Num Samsung que provavelmente tem o Game Booster, o assistente abre na **rota nativa** — uma
instrução por tela, quatro toques, sem app e sem opções do desenvolvedor — e cai na rota longa do
`pm disable-user` só quando o usuário não encontra o menu **ou** quando a medição do app mostra que
o corte continuou. Em aparelho de linha de entrada, nada muda: abre como hoje.

## Escopo

**Entra:**

1. **Classificador do aparelho**, objeto novo (`SamsungGameBooster`, em `com.armsx2`), função
   **pura** sobre `Build.MANUFACTURER`, `Build.MODEL` e `Build.VERSION.SDK_INT`, exposta também como
   `classify(manufacturer, model, sdk)` para o teste JVM chamar sem Android.

   A regra, e ela **só ordena as rotas — nunca bloqueia nenhuma**:

   | `Build.MODEL` | Veredito |
   |---|---|
   | `SM-A0nn`, `SM-A1nn`, `SM-M0nn`, `SM-M1nn`, `SM-Jnnn` | **provavelmente sem** Game Booster → rota longa primeiro (é o caso do `SM-A127M`) |
   | qualquer outro Samsung (`SM-A2nn` e acima, `SM-S*`, `SM-N*`, `SM-F*`, `SM-X*`) | **provavelmente com** → rota nativa primeiro |

   O primeiro dígito do número de três casas é a faixa da linha A/M: `0` e `1` são entrada. O SDK
   **não** entra no veredito, só escolhe a redação: `SDK_INT >= 35` usa os rótulos do One UI 7
   ("Otimização de jogos"); abaixo disso, os do Labs.

2. **Rota nativa dentro do `ThrottleHelp`**, no desenho que já está lá: `PadModal` (e **não**
   `Dialog`/`AlertDialog` — o cabeçalho do arquivo explica: cada janela Android própria engole os
   KeyEvents do gamepad antes do `dispatchKeyEvent` da Activity), máquina de passos em
   `mutableIntStateOf`, rótulo e ação como **valores** compostos num ponto só, botão grande que
   *age e avança* no mesmo toque.

   Uma instrução por tela, porque o usuário é leigo:

   | Tela | Conteúdo | Botão grande |
   |---|---|---|
   | N1 | "Seu telefone tem um ajuste da própria Samsung que resolve isto. São 4 toques e não instala nada." | *Vamos lá* |
   | N2 | "Abra o Gaming Hub" — abre pelo `getLaunchIntentForPackage` | *Abrir o Gaming Hub* |
   | N3 | "Toque nos **⋮** do canto e depois em **Game Booster**" | *Entendi* |
   | N4 | "Toque em **Otimização de jogos** (ou **Labs → Alternate game performance management**)" | *Entendi* |
   | N5 | "Escolha **Desempenho**" | *Entendi* |
   | N6 | "Volte para cá e abra um jogo. Eu meço a velocidade e te digo se resolveu." | *Fechar* |

   **O rótulo do menu sai do idioma do SISTEMA, não do idioma do app.** São coisas diferentes aqui:
   o app tem 19 idiomas (`BASE_EN` no código, os outros em `assets/i18n/<code>.json`) e por padrão
   segue o aparelho (`I18n.SYSTEM_CODE`), **mas o usuário pode escolher outro** no seletor de
   idioma (`ui.language`). Quem estiver com o app em inglês num telefone em pt-BR precisa procurar
   *"Otimização de jogos"*, e não *"Game optimization"* — a instrução traduzida pelo idioma do app
   mandaria procurar um texto que não existe na tela dele.

   Portanto: o rótulo entra na frase por `%s`, resolvido de
   `context.resources.configuration.locales[0]` — **nunca** de `I18n.current` — por uma tabela
   pequena de rótulos da Samsung. Só dois estão apurados em fonte da Samsung: pt-BR
   (*Otimização de jogos*, *Desempenho*) e inglês (*Game optimization*, *Performance*); qualquer
   outro idioma de sistema cai no inglês, que é a mesma degradação que o `I18n.get` já faz. E
   mostre os dois, no formato `Game optimization ("Otimização de jogos")`, quando o idioma do
   sistema não é inglês: um rótulo a mais na tela custa nada, e o usuário reconhece o que vê.

3. **Saída de emergência em toda tela da rota nativa** — botão secundário *"I can't find this"* que
   salta para o passo 1 da rota longa. É o que impede o beco para quem o item 1 classificou errado:
   o classificador é palpite, e palpite errado não pode prender ninguém.

4. **O laço de cobrança que a rota nativa cria** — o ponto que não aparece em revisão de escritório.
   A rota nativa **deixa o GOS habilitado**. `ThrottleWatcher.deviceAffected()` é
   `vendorActive && MANUFACTURER == samsung`, e `vendorActive` é o `applicationInfo.enabled` do
   pacote do GOS (ver `refresh()`): quem resolver pela chave da Samsung continua satisfazendo a
   condição e **levaria o aviso em toda abertura do app, para sempre**. Conserto:

   - **Registrar o veredito limpo**, que hoje é descoberto e jogado fora em dois pontos de
     `sample()`: o `return` do `held == false` (algum cluster alcançou o teto) e a sessão que nunca
     desceu de `SLOW_BELOW_PCT` — nesse caminho o `while` só sai por `interrupt()`, então o registro
     vai no tratamento do `InterruptedException`/`finally`, decidido pelos `peakKHz` já colhidos.
     Pref nova e própria: `throttle.ceiling.clean`.
   - **`warn()` limpa a pref.** Corte medido é corte medido, e o aviso volta.
   - **`maybeShowStartupNotice()` respeita a pref**, depois do `refresh()`.
   - A linha de Configurações → Aplicativo **continua aparecendo** enquanto `deviceAffected()` for
     verdadeira ([`AppTab.kt:733`](../../platforms/android/app/src/main/java/com/armsx2/ui/settings/AppTab.kt)):
     é a porta de volta, como já é para quem marcou "não mostrar de novo".

   Efeito colateral aceito de propósito: num aparelho com o GOS ativo em que o jogo roda a ≥92% de
   qualquer maneira, o aviso também se cala. É a resposta certa — não há o que consertar na tela de
   quem está em velocidade cheia — e ele volta sozinho no primeiro jogo que for cortado.

5. **`<queries>` ganha `com.samsung.android.game.gamehome`.** Hoje o manifesto declara **só**
   `com.samsung.android.game.gos`, e sob a visibilidade de pacotes do Android 11+
   `getLaunchIntentForPackage("…gamehome")` devolve `null` **em silêncio** — o botão da tela N2
   falharia sem erro nenhum. O pacote existe no A12 (o `pm clear` dele está no registro do bug), o
   que também significa que *ter o `gamehome` instalado* **não** serve como detecção do painel.

6. **Strings novas em `BASE_EN`** com prefixo próprio (`throttle.native.*`), sem tocar nas
   `throttle.help.*` da rota longa, **e as mesmas chaves em `assets/i18n/pt-BR.json`** — que é o
   idioma do nosso cliente e já traz as 32 chaves do assistente atual. Os outros 17 JSONs podem
   ficar sem elas: o `I18n.get` cai no inglês e não mostra chave crua (é o desenho declarado no
   cabeçalho do `I18n.kt`, e todos os JSONs não-ingleses são traduzidos por IA de qualquer forma).

**Não entra:**

- **Trocar o LADB por um app grátis** na rota longa (aShell You, ou Shizuku + aShell). É decisão do
  dono ainda em aberto e é outra task: esta não altera nenhuma tela da rota longa além de passar a
  ser alcançada pelo atalho do item 3.
- **Corrigir a promessa "free app"** do passo 1 da rota longa — sai junto com a troca do app, na
  mesma task, porque as duas mexem na mesma tela.
- **`pm uninstall -k --user 0` e a persistência após reinício.** O nosso guia afirma que o
  `disable-user` sobrevive ao reboot e admite não ter testado; o droidwin afirma o contrário. É
  medição no A12, e é outra task.
- **Detectar o painel pelo componente da Samsung** (`resolveActivity`): sem aparelho com o painel,
  não há nome a usar.
- **Telemetria de qual modelo tem o painel.** Resolveria o palpite do item 1 com dado de campo, e é
  decisão à parte.
- **Game Booster Plus / Game Plugins** (ícone de quebra-cabeça dentro do jogo, com *Economia de
  bateria / Equilíbrio / FPS máx / Alta qualidade*): outro caminho, outros rótulos, app que vem da
  Galaxy Store. Não medido. Fica anotado como alternativa a avaliar.
- **Traduzir as telas novas para os outros 17 idiomas.** Entram inglês e pt-BR; o resto degrada
  para o inglês, como já acontece com o que não foi traduzido.
- **Apurar o rótulo do menu da Samsung em outro idioma além de inglês e pt-BR.** Sem fonte da
  Samsung para conferir, um rótulo inventado manda o usuário procurar texto que não existe.

## Como validar

No `SM-A127M` (o aparelho que temos), com o APK **instalado de fato** — `compile*Kotlin` verde não
empacota nada:

1. **Sem regressão na linha de entrada.** `classify` devolve *provavelmente sem* para `SM-A127M` e o
   assistente abre no passo 0/1 de hoje. Nenhuma tela nova no caminho.
2. **Rota nativa alcançável**, forçando o classificador (constante de depuração, não um menu novo):
   as seis telas percorrem com *Avançar*/*Voltar*, o botão da tela N2 **abre o Gaming Hub** no A12
   (provar com `dumpsys activity activities | grep mResumedActivity`), e o *"I can't find this"* de
   cada tela cai no passo 1 da rota longa.
3. **Gamepad.** Percorrer as telas novas só com controle — é a razão de o `PadModal` existir, e uma
   tela nova fora do `controllerFocusable` fica inalcançável em aparelho de controle.
4. **O laço de cobrança, nos dois sentidos** — é o critério que prova o item 4:
   - a. GOS desabilitado (`pm disable-user`), abrir um jogo e deixar rodar em velocidade cheia →
     `throttle.ceiling.clean` gravada. Reabilitar o GOS (`pm enable com.samsung.android.game.gos`) e
     reabrir o app → **nenhum aviso** (hoje: aviso em toda abertura).
   - b. Com o GOS ativo, jogar até sair `@@ANDROID_THROTTLE@@` no `emulog.txt` → pref limpa →
     reabrir o app → **o aviso volta**.
   - c. Nos dois casos, a linha de Configurações → Aplicativo continua visível.
5. **Telas novas em pt-BR.** Com o aparelho em português, percorrer a rota nativa e conferir que o
   texto está traduzido **e** que o rótulo citado é o pt-BR (*Otimização de jogos*, *Desempenho*).
   Depois trocar **só o idioma do app** para inglês, no seletor, e conferir que a frase em inglês
   continua citando o rótulo em português — é o critério que prova o item 2.
6. **Testes JVM do módulo**: casos de `classify` (`SM-A127M`, `SM-A037M`, `SM-A146M`, `SM-A356E`,
   `SM-S928B`, modelo vazio, fabricante não-Samsung) e o `I18nKeysTest`, que reprova `str("chave")`
   sem definição — é o teste que pega a chave nova escrita com erro de digitação.
7. `python scripts/check_traceability.py`.

## Notas para quem implementa

Abra estes símbolos antes de escrever. Todos foram lidos para escrever esta task, e cada um decide
uma linha do escopo:

- `ThrottleWatcher.deviceAffected()`, `maybeShowStartupNotice()`, `refresh()`, `sample()`, `warn()`,
  `setNoticeDismissed()` e as constantes de pref — o item 4 vive aí.
- `ThrottleHelp.Host()` inteiro: `STEPS`, `step`, e como `primaryLabel`/`primaryAction` são
  **valores** (ramificar a composição dos botões registra e desregistra o mesmo `controllerId` a
  cada passo — é o laço de recomposição que o comentário do arquivo manda não repetir); mais
  `openSettings`, `openPlayStore`, `copyCommand` e `warnUnavailable`.
- `PadModal` e `controllerFocusable`.
- `AppTab.kt` na linha do `deviceAffected()`.
- `<queries>` no `AndroidManifest.xml`.
- `I18n`: o cabeçalho do arquivo, `languages`, `SYSTEM_CODE`, `applySelection`,
  `resolveSystemLanguage` e `ensureLoaded` — é o que separa "idioma do app" de "idioma do sistema",
  que o item 2 usa. E `assets/i18n/pt-BR.json`, onde as chaves novas também entram.
- `I18nKeysTest.kt`.

Dois tropeços já registrados neste projeto:

- **`market://` caiu num `ResolverActivity`** no A12 (TASK-0059). Se a tela N2 abrir um seletor em
  vez do Gaming Hub, não é defeito novo.
- **Editar `I18n.kt` pode falhar com `Unresolved reference`** por causa do Kotlin incremental, não
  do código. Build limpo do módulo resolve.
