# Bug: o Game Optimizing Service da Samsung trava a CPU em metade do clock enquanto o jogo roda

- **Detectado em:** 2026-08-29 12:40 (relato do usuário: "ao iniciar um jogo no A12 fica
  extremamente lento e com o áudio também lento"; e "vários usuários de Samsung reportavam lentidão")
- **Origem:** fora do nosso código — `com.samsung.android.game.gos` (Game Optimizing Service),
  aplicado enquanto o nosso app é o jogo em primeiro plano
- **Errors (serviço):** nenhum — não é crash, não gera telemetria. Chega como reclamação de
  lentidão
- **Classe:** fail (performance), causa externa
- **Reincidência:** sistêmico — atinge todo aparelho Samsung com o GOS ativo
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0050](../../task/TASK-0050-detectar-limite-de-clock-do-aparelho.md)
  (detecta e explica), [TASK-0051](../../task/TASK-0051-acao-para-o-limite-do-aparelho.md) (leva o
  usuário ao botão que desarma) e
  [TASK-0052](../../task/TASK-0052-avisar-do-limite-a-cada-sessao.md) (avisa a cada jogo, não uma
  vez só) e [TASK-0053](../../task/TASK-0053-aviso-do-limite-vira-dialogo.md) (o aviso vira
  diálogo com os passos) e
  [TASK-0054](../../task/TASK-0054-assistente-do-limite-do-aparelho.md) (assistente passo a
  passo que cabe deitado) — **nenhuma delas corrige o defeito**, porque ele é do aparelho: o app não pode
  desabilitar o GOS nem forçar a parada dele

## Sintoma

Jogo aberto no aparelho roda a ~um terço da velocidade, com o áudio lento junto. Não há
travamento, crash nem tela preta: a emulação inteira anda devagar, e o áudio acompanha porque
segue o relógio da emulação.

## Causa raiz (medida, não inferida)

Enquanto o nosso app está em primeiro plano **com o jogo rodando**, os 8 núcleos do Exynos 850
ficam presos em **1.053 MHz de 2.002 MHz** — 52,6% do teto de hardware. O corte:

- não é térmico: `Thermal Status: 0`, bateria a 34 °C, AP a 40 °C;
- não é economia de bateria: `low_power=0`;
- não é limite de política: `scaling_max_freq` continua em `2002000`;
- é **do SoC inteiro**, não das nossas threads: um laço de CPU rodando no shell, sem relação com o
  app, também fica preso em 1053 MHz;
- **é específico do nosso app**: com o app em background o mesmo laço sobe a 2.002 MHz na hora, e
  com o Candy Crush em primeiro plano (que o `GameManagerService` também classifica como jogo) o
  laço chega a 1846–2002 MHz.

O `time_in_state` do cpufreq confirma zero tempo acima de 1053 MHz na janela medida, enquanto a
tabela de vida do aparelho mostra 3,2 h acumuladas em 2002 MHz — o hardware alcança, e naquele
momento não alcançava.

## Prova (SM-A127M, Exynos 850, Android 13, APK `githubDebug` 1.0.24 / versionCode 38)

Mesmo protocolo nas seis medições: `am force-stop`, abrir o app, tocar no mesmo jogo
(`10 Pin - Champions Alley`, PAL, alvo 50 fps), esperar 115 s, ler o clock e o `PerfLog` do
`emulog.txt`. AP entre 40 e 47 °C em todas.

| # | condição | clock dos núcleos | PerfLog (alvo 50 fps) |
|---|---|---|---|
| 1 | linha de base — GOS ligado | 1053 MHz | 18,7 → 8,5 → 25,8 |
| 2 | + Game Mode `performance`, `--downscale disable --fps disable` | 1053 MHz | 18,7 → 8,7 → 26,6 |
| 3 | + APK **sem** `isGame`, `appCategory="game"` e `category.GAME` | 1053 MHz | 18,8 → 8,7 → 26,4 |
| 4 | + bancos de `gos`, `gamehome` e `gametools` zerados (`pm clear`) | 1053 MHz | 18,8 → 8,6 → 26,1 |
| 5 | + `sem_enhanced_cpu_responsiveness=1` | 1053 MHz | 18,7 → 8,6 → 25,8 |
| 6 | **controle — `pm disable-user com.samsung.android.game.gos`** | **2002 MHz** | 25,6 → 45,1 → **49,8** |

Com o GOS fora do caminho o jogo roda em **velocidade cheia** (49,8 de 50). O run 6 foi feito com o
mesmo APK e o mesmo estado do run 4, minutos depois — a única variável é o GOS.

## O que já foi descartado como solução

- **`android.game_mode_config` com `supportsPerformanceGameMode` e opt-out das intervenções.**
  O run 2 reproduziu exatamente o estado que esse manifesto produziria: `cmd game mode performance`
  era recusado com *"not supported by come.nanodata.armsx2"* até o overlay ser registrado, e depois
  o `cmd game list` passou a mostrar `Game Mode:2, Scaling:1.0, Fps:`. Nada mudou. Bate com a
  documentação do Android: o Game Mode governa **só** *backbuffer downscaling* e *FPS override* —
  não existe intervenção de frequência de CPU nesse framework.
- **Tirar a classificação de jogo do manifesto.** Runs 3 e 4: o APK saiu do `GameManagerService`
  (`dumpsys game` deixou de listar o pacote) e o GOS continuou cortando, inclusive de banco limpo.
- **Ajuste de desempenho por jogo no Game Booster.** *Não existe neste aparelho.* As Configurações
  do Game Launcher trazem só Visor e notificações, Privacidade, Publicidade, Sobre e Ajuda; não
  aparece ícone flutuante durante o jogo mesmo com `game_show_floating_icon=1`; e `dumpsys
  notification` não traz uma linha de `com.samsung.android.game.*` com o jogo rodando. O painel com
  o seletor de desempenho é de linha superior.

## O que o app pode e o que não pode

**O app não consegue desabilitar o GOS, e não é questão de faltar tentar.** Medido no aparelho:
`android.permission.CHANGE_COMPONENT_ENABLED_STATE` — a permissão que `pm disable-user` exige —
tem `protectionLevel: signature|privileged|role`. Só a alcança quem é assinado com a chave da
plataforma, é privilegiado ou detém um role. O nosso APK é sideload e não tem uma única referência
a ela (`dumpsys package come.nanodata.armsx2 | grep -c CHANGE_COMPONENT_ENABLED_STATE` → 0). O
`adb` consegue porque o UID do shell a detém.

**Mas existe caminho sem PC, e ele foi medido.** A página de informações do GOS abre por intent
documentada, e isso funciona a partir de um app comum:

```
am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.samsung.android.game.gos
```

Nessa página o botão **Desativar está morto** — esmaecido, e o toque não produz diálogo nem
mudança (verificado). O que está vivo é **Forçar parada**, e ele resolve:

| momento | clock | emulação |
|---|---|---|
| jogo rodando, GOS vivo | 1053 MHz | 8,5 fps |
| 30 s após forçar parada do GOS | 2002 MHz | 39,9 fps (subindo) |
| 100 s após, processo do GOS ainda morto | 2002 MHz | **49,8 / 49,8 / 50,0 fps** |

O GOS não voltou nos 100 s seguintes, e a velocidade cheia se manteve com AP a 48 °C. **Mas ele
volta sozinho:** cerca de 40 min depois, sem nenhum `pm enable` e sem reiniciar o aparelho, o
processo estava vivo de novo (`pidof` → 11295) e o teto de 1053 MHz tinha voltado. A parada
forçada alivia a sessão, não conserta o aparelho — o texto ao usuário diz isso.

Para quem tem PC, o caminho permanente continua sendo:

```
adb shell pm disable-user --user 0 com.samsung.android.game.gos
```

## Contexto externo

O comportamento é o mesmo do episódio de 2022, em que o GOS foi flagrado limitando ~10.000 apps em
**até 50%** — aqui, 1053/2002 = 52,6%. A decisão de qual app limitar vem de uma lista consultada em
servidor da Samsung (`gos-api.gos-gsp.io`), o que explica um pacote recém-instalado passar a ser
limitado sem nada ter mudado no app. Não há API oficial de opt-out para desenvolvedores.
[Game Mode API](https://developer.android.com/games/optimize/adpf/gamemode/gamemode-api) ·
[Game Mode interventions](https://developer.android.com/games/optimize/adpf/gamemode/gamemode-interventions) ·
[TechCrunch](https://techcrunch.com/2022/03/04/samsung-says-it-will-release-an-update-to-address-app-throttling-issues)

**Isto não é regressão do fork.** A linha anterior declara `isGame`/`appCategory="game"`/
`category.GAME` exatamente como o fork — verificado em `feature/handoff-end-to-end`.

## Como reproduzir

Num Samsung com o GOS ativo, abrir qualquer jogo e ler, com o jogo rodando:

```
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq
```

Se o primeiro ficar parado bem abaixo do segundo com a emulação atrasada, é este defeito. Repetir
com o app em background: o valor sobe.

## Próximos passos

- [TASK-0050](../../task/TASK-0050-detectar-limite-de-clock-do-aparelho.md) — detectar o corte
  dentro do app e dizer ao usuário o que está acontecendo. É paliativo declarado: o defeito é do
  aparelho.
- **Levar o usuário ao "Forçar parada" a partir do aviso.** O detector já sabe quando o corte
  existe; falta uma ação no banner e na linha de Configurações que abra a página do GOS pela intent
  acima e diga o que tocar. É a única saída que não exige PC, e está medida. Precisa de task
  própria, e de confirmar que a intent parte do nosso app (foi verificada pelo shell).
- Reteste quando houver um aparelho Samsung de linha superior à mão: lá o Game Booster expõe o
  seletor de desempenho, que pode ser um caminho que o A12 não tem.

## Achados de lado, colhidos na mesma investigação

Nenhum dos dois causa esta lentidão — ambos foram medidos e descartados —, mas ficam registrados:

- **A thread `MTVU` fica 100% ocupada em estado `R` com a VM PAUSADA e o telefone bloqueado**
  (724 ticks em ~7 s). É o mesmo defeito de família da
  [`mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm`](../done/mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm_2026-08-28T15-24.md),
  que a [TASK-0046](../../task/TASK-0046-encerrar-thread-mtvu-no-shutdown.md) fechou **só para o
  shutdown**. O fork usa `WaitForWorkWithSpin()` em [`pcsx2/MTVU.cpp:136`](../../../pcsx2/MTVU.cpp)
  onde a 1.0.23 usava `WaitForWork()`. Custa um núcleo e calor; não causa o teto de clock
  (medido: com a VM pausada e a MTVU girando, o clock estava em 2002 MHz).
- **Na tela "Salvos", parada, a UI queima ~1,15 núcleo continuamente** desenhando o fundo animado
  (RenderThread 60%, main 17%, hwuiTask0/1 13% cada, mali 12%), com 99,3% de 126.937 quadros em
  jank. Não afeta o jogo (essas threads zeram durante a emulação), mas esquenta o aparelho antes de
  o jogo começar.
