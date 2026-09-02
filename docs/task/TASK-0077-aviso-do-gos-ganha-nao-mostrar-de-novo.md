# TASK-0077: o aviso do GOS ganha "não mostrar de novo", e o item de menu vira a porta de volta

- **Status:** concluída
- **Criada em:** 2026-09-02
- **Concluída em:** 2026-09-02
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
  (paliativo — o defeito é do aparelho)
- **Commit:** ed3e7e7c46 (o vínculo é o prefixo `TASK-0077:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

A [TASK-0059](TASK-0059-assistente-ensina-a-desabilitar-o-gos.md) entregou o aviso de abertura e o
assistente de 10 passos, e **decidiu não ter** um "não mostrar de novo":

> *"Assim que o usuário seguir o assistente, `vendorThrottlerActive()` fica falso e ele nunca mais
> aparece — sem precisar de 'não mostrar de novo'."*

O argumento só fecha para quem **faz** o procedimento. Para quem não vai fazer — instalar o LADB,
liberar opções do desenvolvedor, parear por depuração sem fio — o diálogo nasce em **toda abertura
do app**, para sempre, e as duas saídas existentes são ruins:

- *"Agora não"* chama `hide()`, que não persiste nada: volta na próxima abertura.
- O interruptor **Aviso do limite da Samsung**, em Configurações → Aplicativo, cala o aviso mas
  também desarma a medição de clock que grava `@@ANDROID_THROTTLE@@` no log de sessão — que é o que
  o suporte lê. Silenciar o aviso não deveria custar o diagnóstico.

Decisão do dono do produto em 2026-09-02: o diálogo tem checkbox de não exibir de novo, e o item de
menu é a porta de volta para quem marcou a caixa e depois mudou de ideia.

## Objetivo

Um caminho de saída honesto para o aviso, sem perder a porta de volta e sem oferecer nada a quem
não tem o problema.

## Escopo

**Entra:**

- **Checkbox no diálogo.** Só na tela de abertura (passo 0), entre o texto e os botões. Marcada,
  grava `throttle.notice.dismissed` e o aviso não nasce mais sozinho. É **caixa de duas vias**: quem
  reabrir pelo menu a encontra marcada e pode desmarcar. Focável por controle
  (`controllerFocusable`), como toda linha deste app.
- **Pref própria, e não o interruptor.** `throttle.notice.dismissed` governa **só** o disparo
  automático. O interruptor `throttle.warnings` continua sendo o mestre (aviso + medição), e a
  medição do `ThrottleWatcher` segue viva com a caixa marcada.
- **Um único portão, compartilhado.** `ThrottleWatcher.deviceAffected()` passa a ser a **única**
  definição de "este aparelho tem o problema": fabricante Samsung **e** pacote do GOS instalado e
  habilitado. O aviso de abertura e o item de menu leem a mesma função, então não podem discordar.
  Antes, `vendorFixAvailable()` checava só o pacote — num aparelho não-Samsung que por qualquer
  motivo tivesse o pacote, o menu ofereceria um conserto que o aviso não oferecia.
- **O item de menu passa a sumir sozinho.** `ThrottleWatcher.refresh()` no `onResume`. O usuário sai
  do app para rodar o `pm disable-user` no LADB e volta: o estado do pacote é reconsultado na
  volta, e o item some na hora — antes só era reconsultado no `onCreate`, então ele ficava visível
  até o app ser morto e reaberto.
- **A caixa se desarma quando o problema é resolvido.** Ao observar a transição *GOS ativo → GOS
  inativo*, `throttle.notice.dismissed` é limpa. Se o GOS voltar um dia (atualização de sistema,
  restauração de fábrica), o aviso volta a nascer em vez de ficar calado para sempre por causa de
  uma caixa marcada meses antes.
- **Índice de busca.** `app.throttleWarnings` entra no `SettingsSearchIndex`, para que buscar
  "Samsung" nas Configurações leve à linha — o item de menu não serve de porta de volta se não for
  encontrável.
- Textos em inglês (fonte da verdade) e pt-BR.

**Não entra:**

- **Mudar o gatilho ou o conteúdo do assistente.** As 10 telas, os botões que agem e o
  `pm disable-user` continuam exatamente como a TASK-0059 os validou. Esta task mexe em *quando o
  aviso nasce* e em *como se sai dele*, não no que ele ensina.
- **Um segundo lugar para o item de menu.** Ele fica onde já está — Configurações → Aplicativo,
  logo abaixo do interruptor. Pôr uma cópia no menu de pausa daria duas portas para a mesma coisa,
  e o procedimento não é para ser feito com um jogo aberto.
- **Traduzir para os outros 18 idiomas.** Inglês (fallback do `I18n.get`) e pt-BR, como na
  TASK-0059.

## Os dois pontos de entrada, detalhados

### 1. O diálogo de abertura

| | |
|---|---|
| **Onde nasce** | `ThrottleWatcher.maybeShowStartupNotice()`, chamada do `onCreate` |
| **Quando nasce** | interruptor `throttle.warnings` ligado **E** `throttle.notice.dismissed` falsa **E** `deviceAffected()` verdadeira |
| **O que mostra** | passo 0: título, o texto que explica que é o celular e não o emulador, a checkbox *"Não mostrar isto de novo"*, e os botões *Agora não* / *Quero resolver* |
| **Saídas** | *Agora não* fecha (volta na próxima abertura, salvo se a caixa estiver marcada); *Quero resolver* entra no assistente de 10 passos |
| **Frequência** | uma vez por abertura do app — não por jogo, não por sessão de emulação |

### 2. O item de menu

| | |
|---|---|
| **Onde** | Configurações → Aplicativo, imediatamente abaixo do interruptor *Aviso do limite da Samsung* |
| **Quando aparece** | `deviceAffected()` verdadeira — e **só** isso: independe do interruptor e da checkbox, porque é justamente a porta de volta para quem calou o aviso |
| **O que é** | uma linha de texto explicando, e o botão *Ver o passo a passo*, que reabre o assistente no passo 0 |
| **Quando some** | assim que o GOS for desabilitado — reavaliado no `onCreate` e no `onResume`, então some na volta do LADB, sem precisar reabrir o app |

Em nenhum dos dois um aparelho não-Samsung, ou um Samsung com o GOS já desligado, vê qualquer
coisa: os dois leem `deviceAffected()`.

## Como validar

No SM-A127M (o aparelho do registro do bug):

1. Com o GOS **ativo** e a pref limpa, abrir o app → o aviso aparece, agora com a checkbox.
2. Marcar a checkbox e tocar em *Agora não*; fechar o app e reabrir → **nenhum aviso**.
3. Configurações → Aplicativo → o item **continua lá**; *Ver o passo a passo* reabre o assistente,
   com a caixa **marcada**; desmarcá-la e reabrir o app → o aviso volta.
4. Rodar o `pm disable-user` e voltar ao app sem matá-lo → o item de menu **some no `onResume`**, e
   a pref `throttle.notice.dismissed` volta a falsa.
5. `pm enable` no GOS e reabrir o app → o aviso nasce de novo (a caixa foi desarmada em 4).
6. Aparelho não-Samsung → nada, nos dois pontos de entrada.

## Resultado

Implementada e compilada (`:app:compileGithubDebugKotlin`, exit 0) em 2026-09-02.

**Validação em aparelho: pendente.** Não havia Samsung conectado à máquina nesta sessão
(`adb devices` vazio), e os seis critérios acima só se fecham com o GOS vivo do lado de lá. O que
foi conferido aqui é o que dá para conferir daqui: o código compila, os portões são um só e as duas
tabelas de texto (EN e pt-BR) têm as três chaves novas.

> ⚠️ **Nada disto chegou a nenhum usuário ainda**, e não é defeito desta task. O aviso do GOS
> inteiro — TASK-0050 a 0059 e agora esta — existe **só nesta branch**. As branches da linha
> publicada (`main`, `version1`, `feature/handoff-end-to-end`) não têm um arquivo sequer de
> `ThrottleWatcher`/`ThrottleHelp`, e o que está no ar em `rgs/ps2/` é `versionCode 37` / `1.0.23`,
> daquela linha. Quem relatar que "o diálogo não aparece" estando em 1.0.x está certo: não há
> diálogo naquele APK. A porta para isso sair é a trilha `rgs/ps2fork/` da
> [TASK-0075](TASK-0075-publicacao-do-fork-em-trilha-propria.md).
