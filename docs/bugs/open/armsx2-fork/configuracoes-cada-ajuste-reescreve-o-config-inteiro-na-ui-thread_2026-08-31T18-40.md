# Bug: cada ajuste em Configurações reescreve o config inteiro — na thread da UI

- **Detectado em:** 2026-08-31 (relato do usuário: "no menu, configurações a navegação fica
  extremamente lenta")
- **Origem:** `platforms/android/app/src/main/java/com/armsx2/ui/InGameOverlay.kt::saveSettings`
  (secundário: `ui/settings/SettingsWidgets.kt::controllerFocusable`)
- **Errors (serviço):** nenhum — não lança e não trava; só demora
- **Classe:** fail (desempenho)
- **Complexidade:** media (deslocamento de recomposição Compose e I/O de disco para coroutines assíncronas)
- **Reincidência:** primeira vez registrada

- **Feature:** nenhuma
- **Tasks que o resolvem:**
  [TASK-0071](../../../task/TASK-0071-passo-do-direcional-nao-recompoe-a-pagina.md) — **item 2 apenas**,
  em andamento · [TASK-0084](../../../task/TASK-0084-ajuste-em-configuracoes-nao-bloqueia-a-thread-da-ui.md) — **itens 1 e 3**,
  concluída. **Continua aberto pelo item 2**, e pelo resíduo do item 1 medido abaixo.

## Sintoma

Navegar em Configurações trava. Pior ao varrer um slider ou alternar toggles com o direcional: o
auto-repeat dispara a cada **110 ms** (`MainActivityRuntime.kt:119`, `NAV_REPEAT_INTERVAL_MS`) e
cada repetição enfileira o trabalho descrito abaixo na thread da UI.

## Causa raiz

São **duas** causas independentes. A primeira domina; a segunda sobra mesmo depois de corrigir a
primeira.

### 1. Ajustar um valor = três releituras do config + 254 chamadas JNI + duas escritas em disco

Toda linha de todo tab escreve por `InGameOverlay.saveSettings` (`InGameOverlay.kt:96`) — é o
`fun apply(updated: Settings)` repetido em AudioTab, FixesTab, NetworkTab, OverlayTab,
PerformanceTab, RendererTab. Um único toque (ou uma única repetição do direcional) executa, em
sequência e **de forma síncrona na thread da UI**:

| Etapa | Onde | Custo |
|---|---|---|
| `ConfigStore.save(...)` | `InGameOverlay.kt:102` → `ConfigStore.kt:289` | `loadGlobal()` (parse JSON do `Settings` inteiro) + `diff` + `toJson()` + `loadOverrides` (2º parse) |
| `writeBackupMirror()` | `ConfigStore.kt:365`, chamado por `saveOverrides`/`saveGlobal` | itera **`prefs.all`**, faz `JSONObject(v)` de **cada jogo** com override e escreve o arquivo `armsx2-settings.json` |
| `updated.applyTo()` | `InGameOverlay.kt:119` → `Settings.kt:793` | **222 `put()` → `NativeApp.setSetting`** (101 em `applyTo` + 121 em `writeGsToNative`, `Settings.kt:1401`) + 32 chamadas JNI diretas + `NativeApp.commitSettings()` (→ `VMManager::ApplySettings`) |
| `ConfigStore.resolveForGame(serial)` | `InGameOverlay.kt:128` → `ConfigStore.kt:264` | **2º** `loadGlobal()` (parse) + `loadOverrides` + `merge` |
| `.writeGameSettingsIni(ConfigStore.loadGlobal())` | `Settings.kt:1367` | **3º** `loadGlobal()` (parse) + `applyTo()` **duas vezes** com `emitSink` (444 emissões) + `gameIniBeginWrite`/`gameIniPut`/`gameIniCommitWrite` (escrita de arquivo nativa) |

Ou seja, por tecla: **3 parses completos do Settings, 2 escritas em disco, ~254 chamadas JNI e um
commit do VMManager**. O `writeBackupMirror` ainda escala com o número de jogos que têm override —
quem tem biblioteca grande paga mais.

Quanto disso roda depende do escopo, e as guardas estão em `InGameOverlay.kt:105`, `:118` e `:150`:

| Situação | O que roda |
|---|---|
| Escopo Jogo, jogo rodando (menu de pausa) | tudo da tabela — o caso pior |
| Escopo Jogo, sem VM (biblioteca) | tudo menos `applyTo()` ao vivo: ainda 3 `loadGlobal()`, o espelho de backup e a escrita do INI |
| Escopo Global, sem jogo | `save` + `writeBackupMirror` (1 parse, `prefs.all`, 1 escrita de arquivo) |

**O mecanismo para não fazer isso já existe e está morto.**
`config/LiveGsApplyQueue.kt` é uma fila coalescente fora da UI thread (`AtomicReference` do último
valor + executor de uma thread). Um `grep` no `app/src` inteiro encontra o nome **só em
comentários** — `LiveGsApplyQueue.applySettings` e `Settings.applyGsLive()` não têm nenhum
call-site. Foram escritos, documentados no `NativeApp.java` ("call it off the UI thread (via
LiveGsApplyQueue)") e nunca ligados. O mesmo vale para `Settings.gsDiffersFrom()`; e
`InGameOverlay.applySafeLiveDelta`, citada em dois comentários, **não existe em lugar nenhum**.

> ⚠️ **Correção da TASK-0084: essa fila NÃO é o que este caso pede.** Ela roda
> `settings.applyGsLive()`, que é `writeGsToNative() + applyGSSettingsLive()` — só a seção
> `EmuCore/GS`, sem `commitSettings()`. `applyTo()` escreve muito além disso (speedhacks, clamps do
> recompilador, SPU2, `Framerate/NominalScalar`, USB) e termina no commit. Ligar uma no lugar da
> outra pararia de empurrar **todo ajuste que não é de GS** para o core, em silêncio. A fila segue
> morta de propósito; a coalescência foi feita em `config/SettingsApplyQueue.kt`.

### 2. Mover o destaque recompõe a página inteira

Independe do item 1 — vale para Cima/Baixo, que não salvam nada.

- `SettingsControllerNav.isSelected(id)` lê `selectedId` (um `mutableStateOf` global) e é chamado
  dentro do `Modifier.composed{}` de **toda** linha registrada (`SettingsWidgets.kt:505`). Toda
  linha da página vira assinante desse estado: mudar a seleção invalida **todas** elas, não as duas
  que realmente mudaram de cor.
- `SettingsScreen` assina o mesmo estado no próprio corpo: `LaunchedEffect(SettingsControllerNav
  .selectedIndex.intValue)` (`SettingsScreen.kt:148`) — a expressão da chave é lida em composição,
  então o escopo da tela inteira (barra de topo + as 12 chips + o painel) recompõe a cada passo.
- A página não é lazy, e **não pode ser**: o registro de foco acontece em `SideEffect` de filho
  COMPOSTO, e o comentário em `SettingsScreen.kt::SettingsCategoryBar` documenta que um `LazyRow`
  já quebrou exatamente isso nas chips. Então "todas as linhas" é literal: 57 no tab App, 78 no
  Fixes.
- Como `controllerFocusable` é `Modifier.composed{}`, cada recomposição reinstala
  `onGloballyPositioned`/`onPreviewKeyEvent`/bordas com lambdas novos, forçando novo layout e
  desenho das linhas.

### 3. (menor) O relógio de quadros nunca dorme

`ControllerAutoScroll` (`SettingsWidgets.kt:452`) roda `while (true) { withFrameNanos { … } }` sem
condição de saída: pede quadro ao Recomposer a 60 Hz enquanto Configurações estiver aberta, mesmo
com `scrollVelocity == 0`. Não recompõe nada, mas impede o app de ficar ocioso.

## Como reproduzir

1. Abrir Configurações (biblioteca ou menu de pausa), tab Performance ou Fixes.
2. Segurar Esquerda/Direita sobre um slider: cada repetição a 110 ms dispara a tabela acima.
3. Comparar com Cima/Baixo (sem salvar): mais rápido, mas ainda recompõe as 57–78 linhas por passo.

## Medição

Feita em **2026-08-31**, aparelho **SM-A127M** (Galaxy A12s, Android 13, 8 núcleos), APK
**1.0.24 / versionCode 38, build DEBUGGABLE** instalado no mesmo dia. Método: `dumpsys gfxinfo
<pkg> reset`, rajada de 10 × `input keyevent 20` (Baixo) já com a aba assentada, depois
`dumpsys gfxinfo`. **Escopo Global, sem VM — nenhuma dessas teclas grava nada.**

| Aba | Linhas | p50 | p90 | Slow UI thread | Janky |
|---|---|---|---|---|---|
| Áudio | 10 | 25 ms | **57 ms** | 10 | 45,8 % |
| Desempenho | 28 | 40 ms | **97 ms** | 24 | 88,9 % |
| App | 57 | 101 ms | **117 ms** | 9 de 9 | 100 % |

O p90 é a comparação justa: o p50 é diluído pelos quadros baratos da animação de `bringIntoView`.
`Number Slow UI thread` bate com o número de teclas em cada caso — **um quadro estourado por
passo do direcional**.

**A GPU não é o gargalo:** na aba App o histograma de GPU é `13ms=9` — todos os 9 quadros a 13 ms
de GPU, contra 101–117 ms de quadro. O custo é de thread da UI.

Decomposição de um quadro por `framestats` (aba Desempenho, uma tecla):

```
HandleInputStart   → AnimationStart          0,005 ms   (o despacho da tecla é instantâneo)
AnimationStart     → PerformTraversalsStart  48,7 ms    ← recomposição
PerformTraversals  → DrawStart                0,22 ms   (medida + layout)
                                    quadro:  ~81 ms
```

Ou seja: **não é layout, não é desenho, não é I/O — é recomposição**, e o item 2 sozinho já
estoura o orçamento de 16,7 ms por um fator de 3 a 7 sem gravar nada.

O custo fixo é grande: mesmo com 10 linhas o p90 é 57 ms. Isso é a parte do item 2 que não depende
do número de linhas — o corpo de `SettingsScreen` (barra de topo + as 12 chips) recompondo a cada
passo por causa da leitura de `selectedIndex`.

### O que NÃO foi medido

- **O caminho de gravação (item 1) não foi isolado.** Uma tecla Direita num slider deu quadro de
  113 ms (Global, sem VM — a variante leve), contra ~97 ms de p90 de um passo puro na mesma aba;
  mas `saveSettings` roda no despacho da tecla, num quadro que muitas vezes não chega a desenhar,
  então o `gfxinfo` não o limita. A variante **pesada** (escopo Jogo e/ou VM rodando: 3× `loadGlobal`
  + 222 JNI + `commitSettings` + escrita do INI) não foi exercitada.
- Nenhum aviso `Choreographer: Skipped N frames` apareceu em nenhum dos casos — o limiar é ~500 ms,
  então isto só diz que não há um bloqueio único acima disso, não que o caminho seja barato.
- **Build de debug.** Pela TASK-0058 o release é materialmente mais rápido que o debug nesta árvore;
  os números acima são portanto um teto pessimista. Um fator 2× de folga ainda deixaria a aba App em
  ~50 ms por tecla.

### Armadilha na reprodução

Medir varrendo um slider com `input keyevent 22` **contamina a medição**: as teclas caíram no
"Limite de FPS de Exibição" e o baixaram para 8 fps, que limita a própria apresentação do app — o
que aparece como "2 quadros para 8 teclas" e parece bloqueio de thread sem ser. Use uma linha que
não afete a apresentação, e faça backup de `shared_prefs/ARMSX2.xml` antes (`run-as`, o build é
debuggable). Vale também que `IntSliderRow` chama `onChange` **mesmo quando o valor satura no
limite** — cada tecla no batente ainda dispara o `saveSettings` inteiro sem mudar nada.

## O que a TASK-0084 mediu, e o que ela mudou (2026-09-04)

O item 1 estava descrito **por leitura** e este relatório dizia, corretamente, que "o caminho de
gravação não foi isolado". Foi isolado: `saveSettings` instrumentado com `elapsedRealtimeNanos` em
volta do corpo e de cada etapa, APK debug, **SM-A127M**, jogo `SLUS-21414` a ~13 fps.

### Antes

| Variante | `ui_ms` na thread da UI | `store_ms` | `ini_ms` | `apply_ms` |
|---|---|---|---|---|
| Global, sem VM | 13,6–16,1 (med. **14,6**) | 13,1–15,4 | 0 | 0 |
| Jogo, sem VM | 22,6–29,3 (med. **26,3**) | 12,1–14,6 | 9,6–15,6 | 0 |
| Jogo, VM rodando | 136,1–157,8 (med. **145,8**) | 9,9–12,4 | 9,1–11,0 | **114,2–136,3** |

**A lista de causas deste relatório está certa; a proporção estava errada.** Na variante pesada,
`applyTo()` sozinho é ~80 % do custo, e o que domina dentro dele não são as 254 chamadas JNI: é
`NativeApp.commitSettings()`, que no nativo é `Host::RunOnCPUThread(…, block=true)`
(`native-lib.cpp:1616`) e **bloqueia** até a thread da CPU drenar no limite de vsync — ~83 ms num
jogo a 12 fps. O custo escala com a lentidão do jogo, que é por que o relato veio de um A12. Os 3
parses, o `writeBackupMirror` e as escritas de disco somam ~20 ms dos ~146.

### Depois

| Variante | antes | depois | fator |
|---|---|---|---|
| Global, sem VM | 14,6 ms | **9,1 ms** | 1,6× |
| Jogo, sem VM | 26,3 ms | **4,9 ms** | 5,4× |
| Jogo, VM rodando | 145,8 ms | **3,4 ms** | **43×** |

As três variantes passaram a caber no quadro de 16,7 ms. Rajada de 8 × Direita num seletor de 5
opções: **8 teclas → 4 gravações** (as 4 do batente saem cedo, custo zero) **→ 1 apply**
(`coalesced=4`). ~1,17 s de thread da UI viraram ~0,09 s.

Item 3, A/B limpo com `git stash` de um arquivo, tela de Configurações aberta e intocada por 10 s,
trocas de contexto voluntárias da thread principal: **627 (62,7/s, vsync exato) → 22–23 (2,2/s)**,
−96 %.

Nenhum ajuste se perde: valor mudado → HOME → `am force-stop` (SIGKILL) → reaberto, e o valor está
lá, na tela e no `shared_prefs/ARMSX2.xml`. `ConfigStore.save` **não** foi adiado, justamente para
que isso não dependesse do flush.

### O que continua aberto

1. **Item 2** — é da TASK-0071, ainda em andamento (p90 de 85 ms na aba App após o merge, alvo
   16,7 ms), e o `EmulationMenuScreen.kt:1508` com o mesmo `isSelected` direto.
2. **Resíduo do item 1:** o apply coalescido ainda custa **61–76 ms de thread principal** quando
   dispara — uma vez por gesto, não nove por segundo, mas ainda um quadro estourado por gesto.
   Apagá-lo exige tirar `applyTo()`/`commitSettings()` da thread da UI, e isso está bloqueado:
   `NativeApp.setSetting` escreve num `MemorySettingsInterface` **sem mutex**
   (`common/MemorySettingsInterface.h`) e há escritores diretos na thread da UI fora do `applyTo`
   (`MainActivityRuntime`: BIOS, pads, frame limit; `MemoryCardViewModel`). Mover hoje troca um
   problema de desempenho por uma corrida de dados. Precisa de trava no lado nativo — mudança de
   core, portanto contribuição ao upstream.
3. **Janela de durabilidade do `apply()`:** um SIGKILL nos primeiros milissegundos após o toque
   ainda perde o valor. É a latência de escrita do `SharedPreferences.apply()`, **igual no código
   antigo**; o que mudou é que `saveSettings` deixou de gastar 143 ms depois dela e o `apply()`
   perdeu essa folga de brinde. Nenhum encerramento real do app (HOME, recentes, deslizar) passa por
   essa janela — todos entregam `onPause`, onde o framework drena o `QueuedWork`.

## Próximos passos

Ordem revista **pela medição**: o item 2 é o que está confirmado como causa de primeira ordem, e é
o que o usuário descreveu (navegar). O item 1 continua sendo trabalho pesado comprovado por leitura,
mas não foi isolado no aparelho.

1. **Item 2 — primeiro, é o medido.** Duas mudanças pequenas e independentes:
   - a linha assina só o próprio booleano —
     `remember(id) { derivedStateOf { nav.isSelected(id) } }` em vez de ler `selectedId` direto,
     para que um passo invalide as 2 linhas que mudaram de cor e não as 57;
   - tirar `SettingsControllerNav.selectedIndex.intValue` do corpo de `SettingsScreen`
     (`SettingsScreen.kt:148`) — ler dentro do `LaunchedEffect` via `snapshotFlow`. É o que explica
     os 57 ms de piso com só 10 linhas na tela.
   Medir de novo com o mesmo protocolo (as três abas, 10 × Baixo, p90) — o alvo é p90 < 16,7 ms.
2. ~~**Item 1.**~~ **Feito na TASK-0084**, com um desvio deliberado do plano abaixo: a
   **persistência não foi adiada** (ela custava ~10 ms de ~146 e adiá-la abriria janela de perda e
   de leitura velha); o que foi coalescido é o INI + `applyTo()`. Números acima. O plano original
   dizia:

   Separar "aplicar o que o usuário vê" de "persistir". O estado em memória e o poke ao
   vivo continuam imediatos; `ConfigStore.save` + `writeBackupMirror` + `writeGameSettingsIni` vão
   para a `LiveGsApplyQueue` (coalescente: só o último valor sobrevive) ou para um debounce de
   ~300 ms, com flush obrigatório ao sair da tela e no `onPause` — a persistência não pode ser
   perdida, é o que os comentários do `saveSettings` protegem.
   Antes disso, medir a variante pesada (escopo Jogo com VM rodando), que é a que ninguém exercitou.
   Extra barato no caminho: `loadGlobal()` é chamado 3× por gravação e a própria doc do
   `ConfigStore` diz "No caching — ... reads happen at game launch (once per launch)"; essa
   premissa deixou de valer. E `IntSliderRow` deveria sair cedo quando o valor satura, em vez de
   gravar tudo de novo para escrever o mesmo número.
3. ~~**Item 3.** Suspender o laço de `withFrameNanos` enquanto a velocidade é zero.~~ **Feito na
   TASK-0084**, com A/B medido: 62,7 → 2,2 trocas de contexto voluntárias por segundo.
