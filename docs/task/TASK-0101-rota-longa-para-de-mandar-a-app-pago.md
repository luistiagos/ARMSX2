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
