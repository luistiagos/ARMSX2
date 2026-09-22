# Backlog: o app faz o pareamento e roda o comando, sem app de terceiro

**Origem:** análise das rotas para desarmar o GOS da Samsung, 2026-09-22 — ver
[`bugs/open/gos-samsung-limita-clock-a-metade-em-jogo`](../bugs/open/armsx2-fork/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
**Data da análise:** 2026-09-22
**Prioridade:** Média — **não começar antes do dado de campo** (ver [Gatilho](#gatilho-para-promover-a-feature))
**Tasks relacionadas:** [TASK-0100](../task/TASK-0100-assistente-da-rota-nativa-do-game-booster.md)
(rota nativa do Game Booster) · [TASK-0101](../task/TASK-0101-rota-longa-para-de-mandar-a-app-pago.md)
(a rota longa deixa de custar dinheiro)

## A ideia

O app abre o pareamento da **depuração sem fio** do próprio aparelho, recebe a porta e o código de
6 dígitos, executa `pm disable-user --user 0 com.samsung.android.game.gos` e **confirma sozinho**,
medindo o clock no jogo seguinte com o `ThrottleWatcher` que já existe.

Nenhum app de terceiro, nenhuma loja, nenhum sideload, nenhum jargão que não seja o das telas dos
Ajustes — e cada tela é nossa, então dá para tentar de novo em vez de largar o usuário num terminal.

## Por que isto existe como ideia

Todas as rotas externas — a melhor delas incluída — têm **cinco passos e passam por "opções do
desenvolvedor"**. Para o perfil que o produto atende (usuário leigo, aparelho de entrada), a taxa de
conclusão esperada é baixa mesmo na melhor. O problema que sobra não é *qual ferramenta*, é *quantas
mãos o usuário tem de usar*.

A rota nativa da Samsung (TASK-0100) resolve para quem tem Game Booster. Quem não tem — A02s, A03s,
A12, A14 5G e companhia, justamente a faixa em que o corte dói mais — continua com cinco passos.

## O que já está apurado

- **É possível: é o que o LADB faz.** Ele embute um servidor adb nas libs do app e conecta ao próprio
  aparelho pela depuração sem fio do Android 11+.
- **Licença fecha.** O LADB é GPLv3 e o nosso core é GPL-3.0+ — dá para estudar e reusar. (O app
  ainda ficaria com as obrigações de fonte correspondente, que já valem para o emulador.)
- **Nenhuma biblioteca JVM faz o pareamento.** O [dadb](https://github.com/mobile-dev-inc/dadb) fala
  o protocolo do adb sem binário e sem servidor, mas o pedido de `adb pair`
  ([issue #25](https://github.com/mobile-dev-inc/dadb/issues/25)) está **aberto desde 29/07/2022**,
  sem resposta. O pareamento do Android 11+ é outro protocolo — TLS com SPAKE2 —, e não o
  `CNXN`/`AUTH`/RSA clássico.
- **O app não instala APK e não vai passar a instalar** — não declara `REQUEST_INSTALL_PACKAGES`, e o
  guard do `build-play-aab.sh` reprova o build se ela aparecer no bundle. Esta ideia é justamente a
  que **dispensa** instalar coisa alguma.
- **A ponte nativa já existe.** O módulo tem CMake e NDK; embarcar um binário ou uma lib a mais não é
  território novo.

## O que falta decidir e medir

| # | Pergunta | Por que decide o tamanho |
|---|---|---|
| 1 | Binário `adb` do NDK (caminho do LADB) **ou** implementar SPAKE2 + TLS-PSK em Kotlin? | é a diferença entre "embarcar e chamar" e "escrever criptografia" |
| 2 | Quanto o binário soma ao APK, por ABI? | o APK já é grande; um número ruim muda a decisão |
| 3 | A porta e o código dão para ler da **notificação** de pareamento (acesso a notificações), ou o usuário digita? | é o passo em que todo mundo tropeça hoje |
| 4 | A depuração sem fio **continua sendo o usuário que liga**, nas Opções do desenvolvedor. Sobram quantos passos? | se sobrarem quatro, o ganho encolhe e talvez não pague |
| 5 | O `pm disable-user` sobrevive a reinício? (medição da TASK-0101) | **se não sobreviver, esta ideia vale mais**: refazer o conserto a cada boot é inviável por app de terceiro e trivial por dentro |
| 6 | Restringir ao flavor `github`? | o LADB está na Play, então não é proibido; mas a nossa trilha de release é sideload e não há motivo para arriscar a revisão do `play` |

## Gatilho para promover a feature

**Número de conclusão das rotas mais baratas.** Enquanto não se souber quantos usuários terminam a
rota nativa (TASK-0100) e a rota do app grátis (TASK-0101), investir uma feature aqui é apostar no
escuro. O sinal pode vir do suporte ou de uma contagem no app — e essa contagem é decisão à parte,
porque hoje o app não reporta uso, só erro.

## Riscos, para não descobrir depois

- **Binário de terceiro no APK**, com a manutenção que vem com ele (ABI, atualização, revisão).
- **Falha silenciosa de pareamento**: código expirado, Wi-Fi ausente, serviço desligado. Cada uma
  precisa de mensagem própria, senão o usuário fica olhando uma tela que não diz nada — e aí o
  assistente novo é pior que o antigo.
- **A permissão que o `pm disable-user` exige continua sendo de shell.** O que muda é de onde o shell
  vem (o adb no próprio aparelho), e não o nível do nosso APK: o
  `CHANGE_COMPONENT_ENABLED_STATE` é `signature|privileged|role` e seguirá fora do alcance do app.
- **Uma atualização de sistema da Samsung reabilita o GOS.** A rota embutida deixa isso barato de
  refazer, mas não some — o texto tem de continuar dizendo a verdade.
