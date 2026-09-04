# TASK-0084: um ajuste em Configurações deixa de bloquear a thread da UI

- **Status:** concluída
- **Criada em:** 2026-09-04
- **Concluída em:** 2026-09-04
- **Feature:** nenhuma
- **Bugs que resolve:**
  [configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread](../bugs/open/armsx2-fork/configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread_2026-08-31T18-40.md)
  — **itens 1 e 3 apenas**. O bug continua aberto: o item 2 é da TASK-0071, ainda em andamento.
- **Commit:** — (o vínculo é o prefixo `TASK-0084:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem o número

O relatório do bug descreve o item 1 por **leitura** e diz, com todas as letras, que o caminho de
gravação "não foi isolado" no aparelho. Isolei. Instrumentei `InGameOverlay.saveSettings` com um
relógio (`elapsedRealtimeNanos`) em volta do corpo inteiro e de cada uma das três etapas, construí
um APK debug e medi no **SM-A127M** (Galaxy A12, Mali-G52, Android 13), APK debug 2.0.4:

| Variante | `ui_ms` (total na thread da UI) | `store_ms` | `ini_ms` | `apply_ms` |
|---|---|---|---|---|
| Global, sem VM | 13,6–16,1 (mediana **14,6**) | 13,1–15,4 | 0 | 0 |
| Jogo, sem VM | 22,6–29,3 (mediana **26,3**) | 12,1–14,6 | 9,6–15,6 | 0 |
| **Jogo, VM rodando** | 136,1–157,8 (mediana **145,8**) | 9,9–12,4 | 9,1–11,0 | **114,2–136,3** |

Quatro amostras por variante. Jogo medido: Delta Force Black Hawk Down (`SLUS-21414`), rodando a
~12 fps no A12 — é o aparelho fraco do relato.

**Cada uma das três variantes estoura o orçamento de 16,7 ms de um quadro. A pior estoura por 9×.**
E isso é **por tecla**: com o auto-repeat do direcional a 110 ms
(`MainActivityRuntime.kt:119`), segurar Direita num slider enfileira uma dessas na thread da UI a
cada 110 ms.

### O que a medição corrigiu no modelo do relatório

O relatório atribui o custo a "3 parses do `Settings` + 2 escritas em disco + ~254 chamadas JNI +
`writeBackupMirror`". Está certo na lista e **errado na proporção**:

- na variante pesada, **`applyTo()` sozinho é ~80 % do custo** (114–136 ms de 145,8);
- `ConfigStore.save` (os parses, o `diff`, o `toJson`, o `writeBackupMirror`) é ~10 ms;
- `writeGameSettingsIni` (os dois passes com `emitSink`, 444 emissões, mais a escrita nativa do INI)
  é ~10 ms.

E o que domina dentro de `applyTo()` **não são** as 254 chamadas JNI: é a última linha,
`NativeApp.commitSettings()`, que no nativo é
`Host::RunOnCPUThread([]{ VMManager::ApplySettings(); … }, /*block=*/true)`
(`native-lib.cpp:1616`). Ela **bloqueia** até a thread da CPU drenar a fila, e a fila drena no
limite de vsync — num jogo a 12 fps isso é ~83 ms de espera por chamada. Ou seja: o custo escala
com a **lentidão do jogo**, e é por isso que o relato veio de um A12.

## O achado do relatório, confirmado

`config/LiveGsApplyQueue.kt` existe e **não tem nenhum call-site**: um `grep` por
`LiveGsApplyQueue` em `app/src` acha só a definição e comentários. O mesmo vale para
`Settings.applyGsLive()` (`Settings.kt:1653`) e `Settings.gsDiffersFrom()` (`Settings.kt:1669`), e
`InGameOverlay.applySafeLiveDelta`, citada em dois comentários, **não existe em lugar nenhum**.

**Mas ligar `LiveGsApplyQueue.applySettings` onde hoje está `applyTo()` seria um defeito, não uma
correção.** A fila roda `settings.applyGsLive()`, que é `writeGsToNative() + applyGSSettingsLive()`
— só a seção `EmuCore/GS`, sem `commitSettings()`. `applyTo()` escreve muito além disso
(speedhacks, clamps do recompilador, SPU2, `Framerate/NominalScalar`, USB) e termina no commit.
Trocar um pelo outro pararia de empurrar **todo ajuste que não é de GS** para o core — em silêncio.
Registrado aqui em vez de silenciado; a fila continua morta e o motivo agora está escrito.

## Escopo

**Entra:**

1. **Coalescer o rabo caro de `saveSettings`** numa fila com debounce, `config/SettingsApplyQueue.kt`.
   - Fica **imediato**: `settingsState.value`, `frameLimitOn.value`, os dois pokes de delta
     (frame-limit / upscale) e — deliberadamente — **`ConfigStore.save(...)`**.
   - Vai para a fila, **na mesma ordem de hoje**: `writeGameSettingsIni` → `applyTo()` →
     `reapplyOsdMode()` (com VM), e `writeGameSettingsIni(serial)` (sem VM).
   - Debounce de 150 ms com teto de 600 ms, para que segurar o direcional não deixe o ajuste
     esperando para sempre.
2. **A persistência NÃO é adiada.** É a diferença entre esta task e o que o relatório sugeriu
   ("mandar `ConfigStore.save` para a fila"). Adiar a gravação abre duas janelas de perda — o app
   morrer antes do flush, e qualquer leitor de `ConfigStore` ver valor velho — e as duas custam
   caro. Como `store_ms` é ~10 ms de ~146 ms, adiá-la compraria 7 % de ganho ao preço de um bug de
   perda de dados. **O flush no `onPause` continua existindo**, mas para o INI e o apply nativo, que
   é o que de fato ficou pendente.
3. **Cache de parse do `loadGlobal()`**, chaveado pela **string crua** lida do `SharedPreferences`.
   Chavear pelo raw (e não por um flag de invalidação) faz o cache se corrigir sozinho diante de
   qualquer escritor externo — inclusive o `prefs.edit().clear()` do reset de fábrica
   (`MainActivityRuntime.kt:1679`) e o `reconcileReusedFolder`. Corta os 3 parses por gravação
   para 1.
4. **`writeBackupMirror()` sai da thread da UI**, para um executor de uma thread, coalescente. Ele
   é só um espelho de recuperação (lido apenas num install novo que reusa a pasta, quando
   `config.global` não existe), itera `prefs.all` e escreve arquivo — I/O de disco que não tem o que
   fazer na thread da UI. Flush no `onPause`.
5. **Sair cedo quando nada mudou.** `Settings` é `data class`, então `updated == previous` é
   comparação de campo. Cobre o que o relatório aponta no `IntSliderRow` (cada tecla no batente
   regravava tudo para escrever o mesmo número) e também tocar num chip já selecionado.
6. **Item 3 do bug:** `ControllerAutoScroll` (`SettingsWidgets.kt:453`) para de pedir quadro a 60 Hz
   com velocidade zero, pelo mesmo padrão já medido no `HomeScreen.kt:377` (TASK-0063).

**NÃO entra:**

- **Tirar `applyTo()` / `commitSettings()` da thread da UI.** É o que apagaria os 136 ms restantes,
  e é justamente o que não dá para fazer com segurança agora: `NativeApp.setSetting` escreve no
  `s_settings_interface`, que é um `MemorySettingsInterface` **sem nenhum mutex**
  (`common/MemorySettingsInterface.h`), e existem escritores diretos na thread da UI fora do
  `applyTo` — `MainActivityRuntime` (BIOS, pads, frame limit), `MemoryCardViewModel`. Rodar
  `applyTo` numa thread de fundo transforma isso em corrida de dados sobre `unordered_map`. Fica
  registrado com o número medido; é a próxima alavanca e precisa de um plano próprio (ou de um
  mutex no lado nativo, que é mudança de core e portanto contribuição ao upstream).
- **Ressuscitar `LiveGsApplyQueue` / `applyGsLive` / `gsDiffersFrom`.** Pelo motivo acima: não são
  substitutos de `applyTo()`. Um caminho "delta seguro" que use `gsDiffersFrom` para escolher entre
  `applyGsLive()` e `applyTo()` é uma task própria.
- **O item 2 do bug.** É da TASK-0071, que segue em andamento com o resíduo já medido lá.
- **`EmulationMenuScreen.kt`**, que tem o mesmo `isSelected` direto — continua registrado na
  TASK-0071, não silenciado.

## Como validar

Mesma instrumentação, mesmo aparelho, mesmo roteiro das três variantes acima (4 amostras cada):

```bash
adb logcat -c
# Global sem VM: alternar "Modo de baixa latência" 4x em Configurações > Desempenho
# Jogo sem VM:   o mesmo, entrando por segurar o jogo > Configurações
# Jogo com VM:   pausa > Correções > Correções de Ampliação > alternar "Offset de Meio Pixel"
adb logcat -d -s System.out | grep ANDROID_SETTINGS
```

Critérios:

1. **`ui_ms` < 16,7 ms nas três variantes** — o ajuste deixa de estourar o quadro.
2. **Uma rajada de N teclas produz 1 (ou ~N/5) execuções do rabo caro**, não N. Verificável pela
   contagem de linhas `@@ANDROID_SETTINGS_APPLY@@` contra as de `@@ANDROID_SETTINGS_SAVE@@`.
3. **Nenhum ajuste se perde**, e isto é medido, não argumentado: mudar um valor, **matar o app**
   (`am force-stop`, que não chama `onPause` de forma confiável), reabrir e conferir que o valor
   está lá — e conferir também na `shared_prefs/ARMSX2.xml` puxada do aparelho.
4. `p90` do `gfxinfo` numa varredura de slider com o direcional, antes e depois, mesmo caminho.
5. Comportamento inalterado à mão: o valor na tela muda na hora; o efeito no emulador aparece ao
   soltar; sair da tela e voltar mostra o valor novo.
6. `:app:testGithubDebugUnitTest` continua verde (37 testes).

## Resultado

**Critérios 1, 2, 3, 5 e 6 atingidos. O critério 4 (gfxinfo) não foi feito, e digo abaixo por quê.**

Mesmo aparelho (SM-A127M), mesmo APK debug, mesmo roteiro, mesma instrumentação nos dois lados.

### Critério 1 — custo na thread da UI por ajuste

| Variante | antes (mediana) | depois (mediana) | fator |
|---|---|---|---|
| Global, sem VM | 14,6 ms | **9,1 ms** (17,2 na 1ª, com o cache frio) | 1,6× |
| Jogo, sem VM | 26,3 ms | **4,9 ms** | 5,4× |
| **Jogo, VM rodando** | **145,8 ms** | **3,4 ms** | **43×** |

Amostras do depois, na íntegra:

```
Global, sem VM:   ui_ms=17,18 · 9,52 · 9,36 · 8,52
Jogo, sem VM:     ui_ms=5,26 · 4,88 · 4,37 · 5,15   (apply diferido: 10,55 · 5,58 · 6,38 · 5,80)
Jogo, VM rodando: ui_ms=3,97 · 4,17 · 3,79 · 4,26 · 2,81 · 4,13
                  (apply diferido: 74,93 · 67,80 · 66,20 · 72,24 · 61,17 · 70,56)
```

**As três variantes passaram a caber no quadro de 16,7 ms.** A pior, que era 9× o orçamento, é hoje
1/5 dele.

Repare que o Jogo (4,9 ms) ficou **mais barato** que o Global (9,1 ms): em escopo Jogo grava-se o
blob esparso de override; em Global grava-se o `Settings` inteiro em JSON, e o `toJson` de ~300
campos é praticamente todo o custo que sobrou. É o preço deliberado de **não** adiar a persistência.

### Critério 2 — coalescência

Rajada de **8 × Direita** (`input keyevent 22 …`, um único comando, sem espera) sobre um seletor de
5 opções, escopo Jogo com VM rodando:

```
@@ANDROID_SETTINGS_SAVE@@  ui_ms=5,15
@@ANDROID_SETTINGS_SAVE@@  ui_ms=2,53
@@ANDROID_SETTINGS_SAVE@@  ui_ms=2,26
@@ANDROID_SETTINGS_SAVE@@  ui_ms=2,42
@@ANDROID_SETTINGS_APPLY@@ ms=76,16 coalesced=4
```

Duas coisas de uma vez, e as duas são o que se queria:

- **8 teclas → 4 gravações.** As 4 últimas caíram no `updated == previous` e não custaram nada: o
  seletor tinha saturado na última opção. Antes, cada uma dessas 4 teclas no batente rodava o
  caminho inteiro — 4 × 145,8 ms = **583 ms de thread da UI para não mudar nada**.
- **4 gravações → 1 apply**, com `coalesced=4`. As outras 4 × 145,8 ms = 583 ms viraram
  4 × ~3 ms na tecla mais **um** apply de 76 ms.

Somando: a rajada custava ~1,17 s de thread da UI e passou a custar ~0,09 s. **13×.**

### Critério 3 — nenhum ajuste se perde

Provado no aparelho, não argumentado:

1. Escopo Global, `config.global.vsyncQueueSize = 2`.
2. Tocar "Modo de baixa latência" → `@@ANDROID_SETTINGS_SAVE@@ ui_ms=16,14`, seguido de
   `@@ANDROID_SETTINGS_APPLY@@ ms=0,86 coalesced=1` 192 ms depois.
3. **HOME** (dispara `onPause` → `SettingsApplyQueue.flush()` + `ConfigStore.flushBackupMirror()`)
   e em seguida **`am force-stop`**, que é SIGKILL, sem `onPause`.
4. `shared_prefs/ARMSX2.xml` lido com `run-as`: **`vsyncQueueSize = 0`**. Sobreviveu.
5. App reaberto: a chave "Modo de baixa latência" aparece **ligada** na tela (captura `b2.png`).

Um segundo ensaio, escopo Jogo (`SLUS-21414`), sem HOME nenhum — só `input tap` e, 250 ms depois,
`am force-stop`: `{"vsyncQueueSize":0,…}` também sobreviveu.

**O que NÃO sobrevive, e é honesto dizer:** com o `am force-stop` disparado no *mesmo comando de
shell* que o toque, sem espera nenhuma, o valor se perde. Isso é a latência do
`SharedPreferences.apply()`, que escreve o disco numa thread de fundo: uma janela de poucos
milissegundos que **existe igual no código antigo** — `ConfigStore.save` continua sendo chamado no
mesmo ponto, de forma síncrona. O que mudou é que `saveSettings` retorna em 3 ms em vez de 146, e
com isso o `apply()` deixou de ganhar de brinde 143 ms de folga enquanto o resto do trabalho rodava.
Fechar essa janela de vez exigiria `commit()` (escrita síncrona de disco na thread da UI), que é
exatamente o que esta task existe para tirar de lá. **Nenhum caminho real de encerramento do app —
HOME, recentes, deslizar para fechar — passa por essa janela**: todos entregam `onPause`, e o
framework drena o `QueuedWork` do `apply()` ali.

### Critério 5 — comportamento

Conferido à mão no aparelho: o valor na tela muda no toque; o efeito no emulador chega ao soltar; a
tela reaberta mostra o valor novo; o Reset por aba continua funcionando (o `flush()` no
`resetCurrentScope` garante a ordem).

### Critério 6 — testes

`:app:testGithubDebugUnitTest` verde, **37 testes**, antes e depois.

### Item 3 do bug — A/B limpo

Medido o que o defeito de fato produz: **trocas de contexto voluntárias da thread principal** com a
tela de Configurações aberta e **intocada por 10 s** (é a métrica da TASK-0063; `gfxinfo` não serve,
porque o laço não desenha nada, só acorda).

Dois APKs, mesmo aparelho, mesma tela (Global → Desempenho), `git stash` de um único arquivo entre
os dois — ou seja, A/B limpo:

| | trocas voluntárias / 10 s | por segundo |
|---|---|---|
| Laço incondicional (controle) | **627** | 62,7 — vsync exato |
| Com a guarda de velocidade | **23** e **22** (duas corridas) | **2,2–2,3** |

**−96 %.** O 62,7/s bate com os 618/10 s que a TASK-0063 mediu no mesmo defeito na biblioteca.

### Critério 4 — o que NÃO foi medido, e por quê

**Não há `gfxinfo` antes/depois de uma varredura que grava.** A medição de origem do bug (e a da
TASK-0071) usa `gfxinfo` com Baixo/Baixo, que **não grava nada** — é o item 2. Para o item 1 eu não
tinha um "antes" de `gfxinfo` no mesmo protocolo, e fabricar um "depois" sem par não prova nada.

A instrumentação por `elapsedRealtimeNanos` dentro do `saveSettings` é medida melhor para este item:
ela mede exatamente a grandeza do bug — tempo de thread da UI por ajuste — sem depender de o quadro
chegar a desenhar. O relatório do bug registra justamente que o `gfxinfo` **não** enxerga esse
caminho ("`saveSettings` roda no despacho da tecla, num quadro que muitas vezes não chega a
desenhar").

### O que fica na mesa, com número

**O apply diferido ainda custa 61–76 ms de thread principal quando dispara** (escopo Jogo, VM
rodando, jogo a ~13 fps). Ele dispara uma vez por gesto em vez de nove vezes por segundo, mas
continua sendo um quadro estourado por gesto. Tirá-lo da thread da UI é a próxima alavanca e está
bloqueada pelo que a seção "NÃO entra" descreve: `MemorySettingsInterface` não tem mutex e há
escritores diretos na thread da UI. Enquanto isso não mudar, mover é trocar um problema de
desempenho por uma corrida de dados.

Também segue aberto o item 2 (TASK-0071) e o `EmulationMenuScreen.kt` com o mesmo `isSelected`
direto.

### Estado

**Concluída.** O bug continua em `open/` porque o **item 2 não é desta task** e segue em andamento
na TASK-0071, e porque o resíduo de 61–76 ms do item 1 está registrado acima em vez de silenciado.
