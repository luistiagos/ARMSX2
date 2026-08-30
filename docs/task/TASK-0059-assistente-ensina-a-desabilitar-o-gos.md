# TASK-0059: o assistente abre no início do app e ensina o conserto que funciona

- **Status:** concluída
- **Criada em:** 2026-08-30
- **Concluída em:** 2026-08-30
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** o código está em `ea80c1041e`, commitado com o prefixo **`TASK-0055:`** —
  ver a nota de colisão abaixo
- **Revertida por:** —
- **Publicado em:** —

> ⚠️ **Esta task nasceu como TASK-0055 e foi renumerada.** Outra sessão alocou o mesmo
> número no mesmo dia, para a task dos contadores de desempenho, e as TASK-0056 a 0058
> já dependem dela. Mover esta custou um arquivo e uma linha de índice; mover a outra
> custaria quatro, com uma sessão ativa mexendo neles.
>
> O commit do código (`ea80c1041e`) ficou com o assunto `TASK-0055:` porque a colisão só
> apareceu depois. Não reescrevi o histórico de propósito — é o estrago que o validador
> tem uma checagem inteira para detectar. É o mesmo defeito de processo já registrado em
> [`numeros-de-task-colidem-entre-ramos`](../bugs/open/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md),
> agora visto **dentro** do mesmo ramo, entre duas sessões simultâneas.

## Contexto

As TASK-0050 a 0054 construíram um aviso que **ensina o procedimento errado**. Ele manda o usuário
forçar a parada do GOS — e um monitor de 5 min mostrou que isso é auto-derrotante: para executar os
toques o usuário **sai do app**, e a volta ao jogo ressuscita o serviço. O GOS renasceu **2 s**
depois da transição de foco, com o teto de 1053 MHz de volta e cravado pelos 35 s seguintes.

Nas medições em que a parada forçada segurou (88 s, 58 s, 15 s), o app **já estava** em primeiro
plano — nunca houve a volta. Foi por isso que a medição e o relato do testador se contradiziam.

O que resolve de verdade é `pm disable-user`, e existe caminho sem PC para ele
([`docs/gos-samsung-desabilitar-sem-pc.md`](../gos-samsung-desabilitar-sem-pc.md)). É mais longo,
mas é **uma vez na vida do aparelho** — contra 3 toques por sessão que na maioria das vezes não
funcionam.

## Objetivo

Quando o app abre num Samsung com o GOS ativo, avisar **uma vez** e oferecer um assistente que
ensina, passo a passo e em linguagem de leigo, a desabilitar o GOS de vez.

## Escopo

**Entra:**

- **Gatilho novo: início do app.** `ThrottleWatcher.maybeShowStartupNotice()`, chamada do
  `onCreate`, exibe o aviso quando **as duas** condições valem: fabricante Samsung **e** pacote do
  GOS instalado e habilitado (`applicationInfo.enabled`, via `<queries>` já declarado). Uma vez por
  abertura do app.
- **Sai o gatilho antigo.** O detector de clock não abre mais diálogo. Ele continua medindo e
  gravando `@@ANDROID_THROTTLE@@` no log de sessão, que é o que o suporte lê.
- **Assistente reescrito**, de 4 para 10 telas, ensinando o caminho do `pm disable-user` pelo LADB:
  visão geral, instalar o app, liberar opções do desenvolvedor, ligar depuração sem fio, parear,
  a dica da tela dividida, digitar código e porta, **copiar o comando**, conferir, e testar.
- **Botões que agem.** O botão grande de cada tela executa e avança: abre a Play Store no LADB,
  abre as telas de Ajustes certas, e **copia o comando para a área de transferência** — digitar
  `pm disable-user --user 0 com.samsung.android.game.gos` à mão é pedir erro.
- Linha de Configurações → App passa a **reabrir o assistente**, para quem fechou e quer voltar.

**Não entra:**

- Ensinar Brevent ou Shizuku. O Brevent leva ao *force-stop* pela função nativa dele — o mesmo beco
  — e o Shizuku exige um segundo app cliente. O guia de suporte explica a escolha.
- Detectar que o usuário concluiu. Quem confere é a velocidade do jogo, e o próprio aviso some
  sozinho: com o GOS desabilitado a condição do gatilho passa a ser falsa.
- Traduzir para os outros 18 idiomas. Inglês (que é o fallback de `I18n.get`) e pt-BR.

**Autolimitante de propósito:** o aviso só reaparece enquanto o problema existir. Assim que o
usuário seguir o assistente, `vendorThrottlerActive()` fica falso e ele nunca mais aparece — sem
precisar de "não mostrar de novo".

## Como validar

No SM-A127M:

1. Com o GOS **ativo**, abrir o app → o aviso aparece logo no início, sem depender de abrir jogo.
2. Percorrer as 10 telas com *Avançar*/*Voltar*; conferir que o botão de copiar põe o comando na
   área de transferência e que os botões de Ajustes abrem as telas certas.
3. Com o GOS **desabilitado** (`pm disable-user`), abrir o app → **nenhum aviso**.
4. Interruptor desligado em Configurações → App → nenhum aviso com o GOS ativo.

## Resultado

Validada no SM-A127M em 2026-08-30, os quatro critérios:

| # | condição | resultado |
|---|---|---|
| 1 | GOS ativo, app aberto | aviso aparece **na abertura**, sem abrir jogo |
| 2 | percorrer o assistente | 10 telas, *Voltar*/*Avançar*, botões de ação funcionando |
| 3 | GOS desabilitado | **nenhum** aviso — biblioteca limpa |
| 4 | interruptor desligado, GOS ativo (pid 31778) | **nenhum** aviso |

Os botões que saem do app foram testados um a um, e o desenho "avança antes de sair" se provou:
depois de tocar em *Abrir a loja* e voltar com o Voltar do aparelho, o assistente estava no
**passo 3**, e não no 2 que acabara de ser executado.

As telas de Ajustes abertas são as certas, confirmadas pelo foco:

- *Abrir "Sobre o telefone"* → `com.android.settings/…Settings$MyDeviceInfoActivity`
- *Abrir "Opções do desenvolvedor"* → `com.android.settings/…Settings$DevelopmentSettingsDashboardActivity`

O botão de copiar funcionou: o banner *"Comando copiado — agora cole no LADB"* apareceu, que só
sai quando `setPrimaryClip` retorna sem exceção.

Uma observação de campo: o `market://` caiu num `ResolverActivity` em vez de abrir a loja direto —
o aparelho tem mais de um app capaz de resolver. Não é defeito (o usuário escolhe e chega à
página), mas é um toque a mais para um leigo. Se incomodar, vale tentar a Play Store por nome de
pacote antes de cair no `market://` genérico.
