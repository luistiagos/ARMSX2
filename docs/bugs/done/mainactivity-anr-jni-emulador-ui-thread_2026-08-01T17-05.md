# Bug: ANR — chamadas JNI do emulador executadas direto na UI thread (drawer in-game)

- **Detectado em:** 2026-07-18 → 2026-08-01 17:05 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`NativeApp.java::NativeApp.setSetting` / `.renderGpu` /
  `.setEnableCheats` / `.saveStateToSlot`)
- **Errors (serviço):** **26 ocorrências**
  - `setSetting` (11): 746, 687, 683, 622, 621, 620, 619, 617, 584, 582, 571
  - `renderGpu` (12): 792, 813, 739, 728, 684, 681, 680, 645, 591, 539, 515, 514
  - `setEnableCheats` (2): 757, 735
  - `saveStateToSlot` (1): 731
- **Classe:** fail (ANR — `main thread unresponsive >5000ms`)
- **Reincidência:** recorrente; Android 11/14/15/16, `app_version 1.0.8` em todas as amostras.
  Maior grupo de ANR da telemetria aberta (26 de 48 na triagem de 2026-08-05)

## 🔁 Recorrência — triagem de 2026-08-05

**2 IDs novos**, mesmo padrão (`renderGpu` chamado direto na UI thread pelo toggle de renderer do
drawer in-game): **792** (2026-08-03 17:52), **813** (2026-08-04 10:09).

## 🔁 Recorrência — triagem de 2026-08-19

- **Janela agora:** telemetria completa dos "Aberto" em 2026-08-19.
- **Volume legado (1.0.8):** 52 ocorrências abertas (`setSetting`: 1418, 1340, 1127–1119, 1039, 1031, 1028, 1022, 1021, 993–989, 964–961, 948, 942, 940, 935, 932, 911, 906; `renderGpu`: 1439, 1416, 1407, 1118–1113, 1068, 1029, 1026, 1020, 1003, 967, 966, 947–945, 922, 891; `setAspectRatio`: 933).
- **Validação em produção (1.0.10):** **ZERO ocorrências** no build 1.0.10 na telemetria. O marshaling para `Host::RunOnCPUThread` eliminou por completo os travamentos de UI causados por reload de settings/renderer em produção.


## Sintoma

`java.lang.Throwable: ANR: main thread unresponsive >5000ms`, sempre com o frame de topo sendo um
método nativo de `NativeApp` chamado direto de um listener de UI do drawer in-game:

```
at kr.co.iefriends.pcsx2.NativeApp.setSetting(Native Method)
at kr.co.iefriends.pcsx2.activities.MainActivity$8.onItemSelected(MainActivity.java:2827)
at android.widget.AdapterView.fireOnSelected(AdapterView.java:1414)
...
at android.os.Looper.loop(Looper.java:342)
```

Pontos de entrada confirmados nas amostras (todos em `MainActivity`, todos na UI thread):

| Linha | Widget | JNI chamado |
|---|---|---|
| [2738](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L2738) | `MaterialButtonToggleGroup` (renderer) | `renderGpu` |
| [2769](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L2769) | Spinner aspect ratio | `setSetting` |
| [2804](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L2804) | Spinner resolution scale | `setSetting` |
| [2827](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L2827) | Spinner blending accuracy | `setSetting` |
| [2975](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L2975) | Switch enable cheats | `setEnableCheats` |
| [3007](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L3007) | Switch async textures | `setSetting` |
| [3104](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L3104) | Diálogo save state | `saveStateToSlot` |

## Causa raiz (CONFIRMADA no código)

Os listeners chamam o JNI de forma **síncrona**, e do outro lado o JNI faz trabalho pesado no
**thread do chamador** (a UI thread), não no thread do emulador:

**`Java_..._NativeApp_setSetting`** — [main.cpp:1141-1184](../../../app/src/main/cpp/main.cpp#L1179):
```cpp
VMManager::ApplySettings();          // reload completo de settings da VM
if (MTGS::IsOpen())
    MTGS::ApplySettings();           // posta no ring do GS e ESPERA a GS thread
si.Save();                           // escrita do INI em disco
```

**`Java_..._NativeApp_renderGpu`** — [main.cpp:1058-1071](../../../app/src/main/cpp/main.cpp#L1063):
mesma sequência, e ainda **troca o renderer**, o que destrói e recria o device gráfico inteiro.

**`Java_..._NativeApp_setEnableCheats`** — [main.cpp:867-882](../../../app/src/main/cpp/main.cpp#L879):
mesma sequência (`Save()` + `ApplySettings()` + `MTGS::ApplySettings()`).

**`Java_..._NativeApp_saveStateToSlot`** — [main.cpp:1472-1500](../../../app/src/main/cpp/main.cpp#L1499):
o pior caso, porque o bloqueio é **por construção**:
```cpp
std::future<bool> ret = std::async([p_slot] {
    ...
    for (int i = 0; i < 5; ++i) {    // laço de até 5 iterações
        if (s_execute_exit) { ... break; }
        sleep(1);                     // 1 segundo cada
    }
    return false;
});
return ret.get();                     // <-- bloqueia o chamador em até ~5 s
```
Chamado da UI thread em `showGameStateDialog`, `ret.get()` segura o main looper por até 5 s — no
limite exato do watchdog de ANR. `loadStateFromSlot` (main.cpp:1504+) tem o mesmo padrão.

Ou seja: o ANR não é um "device lento" — é o design do binding. `MTGS::ApplySettings()` depende da
GS thread responder, e a GS thread pode estar ocupada num frame pesado; `si.Save()` é I/O
síncrono; `saveStateToSlot` dorme explicitamente.

## Como reproduzir

1. Iniciar um jogo pesado (GS thread saturada, ~100% de uso).
2. Abrir o drawer in-game e trocar o **renderer** (ou o aspect ratio / blending accuracy).
3. Sem soltar, trocar de novo algumas vezes seguidas.
4. A UI congela; em >5 s o sistema levanta o ANR e o report `armsx2/anr` é enviado.

Para o caso determinístico: abrir o diálogo de save state e salvar no slot 1 com a VM rodando —
`ret.get()` mantém a UI travada até o laço de 5 s terminar.

## Próximos passos

1. ~~**Tirar todo JNI de emulador da UI thread.**~~ Feito para o caso de `ApplySettings` — ver
   "Fix aplicado" abaixo. `renderGpu`/`setSetting`/etc. continuam chamados sincronamente da UI
   thread, mas o trabalho pesado (`VMManager::ApplySettings()`/`MTGS::ApplySettings()`) não roda
   mais nela.
2. **`saveStateToSlot`/`loadStateFromSlot`:** a violação de thread-ownership (a chamada real a
   `VMManager::SaveStateToSlot`/`LoadStateFromSlot` rodando fora da CPU thread) está corrigida —
   ver "Fix aplicado". O `ret.get()` síncrono **continua** bloqueando a UI thread até 5s no pior
   caso; trocar por API assíncrona com callback segue em aberto.
3. **`si.Save()`**: não persistir o INI a cada tick de spinner — fazer debounce/coalescing das
   escritas (várias amostras são de usuário arrastando o spinner).
4. Reavaliar se `VMManager::ApplySettings()` + `MTGS::ApplySettings()` precisam ser síncronos para
   mudanças que não exigem confirmação visual imediata.

## Fix aplicado (2026-08-05)

Portado de upstream ARMSX2/ARMSX2 (não está na `master`, ainda são branches de tópico abertas —
achado ao checar todos os branches/tags do upstream nesta mesma triagem):

- [`jni-thread-ownership`](https://github.com/ARMSX2/ARMSX2/commit/36e4ade4d0) — "Android/iOS:
  marshal EmuConfig and MTGS-ring work onto the CPU thread" (25/07/2026) + o assert de dev
  companheiro [`600b4f0588`](https://github.com/ARMSX2/ARMSX2/commit/600b4f0588). Mesmo
  diagnóstico do bug: `VMManager::ApplySettings()` reconstrói o `EmuConfig` inteiro (janela
  move-from) e `MTGS::ApplySettings()` empurra pro ring do GS, que é single-producer e pertence à
  CPU thread — um segundo produtor derruba pacote e pode fazer a GS thread interpretar payload
  como comando.
- [`android-pad-modals`](https://github.com/ARMSX2/ARMSX2/commit/9f73c77d59) — "Android: run save
  and load state on the CPU thread" (03/08/2026). JNI de nome idêntico ao nosso
  (`Java_kr_co_iefriends_pcsx2_NativeApp_saveStateToSlot`/`loadStateFromSlot` — o bridge nativo do
  upstream manteve o pacote antigo mesmo após a reescrita da UI em Kotlin/Compose).

Não deu pra fazer cherry-pick direto: o `Host::RunOnCPUThread` do nosso fork era um stub
(`pxFailRel("Not implemented")`, [main.cpp:1935](../../../app/src/main/cpp/main.cpp#L1935) antes do
fix), e nosso `saveStateToSlot`/`loadStateFromSlot` usa um `std::async` + busy-poll em
`s_execute_exit` que não existe no upstream atual. Portado o mecanismo, não o patch literal:

| Arquivo | Mudança |
|---|---|
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `Host::RunOnCPUThread` implementado de verdade: fila (`s_cpu_thread_queue`) + mutex + modo bloqueante (`condition_variable`) e não-bloqueante, igual ao padrão do `native-lib.cpp` do upstream. Roda inline quando não há VM ativa ou quando já se está na CPU thread. |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `ClaimCPUThreadIdentity()`/`ReleaseCPUThreadIdentity()` — marcam a thread de `runVMThread` como dona da fila pro tempo de vida da sessão; chamadas nos dois pontos de saída da função (falha em `CPUThreadInitialize` e o fim normal). |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | Loop de `runVMThread`: `PumpCPUThreadQueue()` a cada retorno de `VMManager::Execute()` (estado `Running`) e a cada tick do estado `Paused` (que também ganhou `usleep(16000)` no lugar do `usleep(250000)` genérico, pra fila drenar rápido mesmo pausado). |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `setEnableCheats`, `setAspectRatio`, `speedhackEecyclerate`, `speedhackEecycleskip`, `renderUpscalemultiplier`, `renderMipmap`, `renderHalfpixeloffset`, `renderPreloading`, `renderGpu`, `setCustomDriverPath`, `setSetting` — os 10 pontos de entrada JNI que chamavam `VMManager::ApplySettings()`/`MTGS::ApplySettings()` direto agora passam por `Host::RunOnCPUThread([]() {...})` (não bloqueante). |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | ~~`saveStateToSlot`/`loadStateFromSlot`: chamada real via `Host::RunOnCPUThread(..., block=true)`~~ — **revertido em 2026-08-05** depois de reproduzir um ANR/trava completa em device com jogo real (ver "Validação em device" abaixo). Voltou pra chamada direta original (`VMManager::SaveStateToSlot`/`LoadStateFromSlot` direto na worker thread do `std::async`). O bug de thread-ownership/ring do GS que essa mudança tentava corrigir **continua aberto**. |

**O que este fix NÃO resolve:** `ret.get()` em `saveStateToSlot`/`loadStateFromSlot` continua
bloqueando quem chamou (a UI thread) por até 5s no pior caso — ver item 2 de "Próximos passos"
acima. Pior ainda: a validação em device encontrou um ANR real e reproduzível nesse caminho,
independente do fix de thread-ownership (ver seção "Validação em device" abaixo) — a tentativa de
marshaling não resolveu isso e foi revertida.

**Status da validação:** compila e linka limpo (`bin/libemucore.so`, ninja, NDK 29, arm64-v8a).
**Ainda não commitado.**

## 🔁 Validação em device — 2026-08-05 (primeira rodada, sem disco PS2 real)

Build instalado via `gradlew installUnrestrictedDebug` no Moto G86 5G (Android 16). Sem ROM PS2
válida disponível no device/host nessa primeira rodada, usado `dcgames/soulreaver.chd` (jogo PS1 —
a VM sobe e roda o BIOS normalmente, o que já é suficiente pra exercitar a fila da CPU thread, mas
nunca chega a ter disco reconhecido).

Confirmado que a parte de settings (`setEnableCheats`, `renderGpu`) não trava nem crasha sob 12 taps
consecutivos no renderizador + 6 toggles de cheats. A parte de save/load state não pôde ser testada
de verdade (guard `GetDiscCRC() != 0` nunca satisfeito com esse disco).

## 🔁 Validação em device — 2026-08-05 (segunda rodada, com jogo PS2 real via catálogo)

O projeto tem um catálogo de ROMs embutido (`catalog_manifest_ps2.txt`, ~1868 jogos, download via
Hugging Face) com BIOS auto-extraída — usado para baixar **Crash Bandicoot - The Wrath of Cortex
(USA) (v1.00)** (324 MB) direto pelo app (aba Catálogo → busca → toque → download automático) e
testar o caminho completo com CRC de disco válido e gameplay real.

**Confirmado de novo, agora com disco real — settings/renderer:** 12 taps consecutivos no
renderizador (AT/GL/SW/VK) + 6 toggles de cheats, com o jogo rodando de verdade (cutscene/gameplay
renderizando, não só a BIOS) — **zero ANR, zero crash**, mesmo PID antes/depois, drawer e jogo
continuaram respondendo e renderizando (confirmado visualmente, frames diferentes entre screenshots
consecutivos). Esta parte do fix está **validada**.

**🔴 Achado importante — `saveStateToSlot` trava o app inteiro (ANR real, reproduzido 3x):**
Tocar "Estado do Jogo" → "Salvar estado (slot 1)" com o jogo rodando **travou o app por completo**
(não só a UI thread — duas capturas de tela consecutivas, ~3s de intervalo, saíram **byte-a-byte
idênticas**, ou seja nem a GS thread seguiu renderizando). O Android acabou levantando o diálogo
"RetroSystem PS2 não está respondendo" (`Input dispatching timed out ... Waited 10000ms`).

Investigado se era regressão do `Host::RunOnCPUThread(..., block=true)` que eu tinha acabado de
aplicar em `saveStateToSlot`/`loadStateFromSlot` (ver "Fix aplicado" acima) — reproduzi a trava
**duas vezes** com esse wrap (com e sem troca de renderer antes, pra descartar interação com o
teste de renderer). Revertido o wrap (voltou pra chamada direta `VMManager::SaveStateToSlot(...)`
que já existia, sem `Host::RunOnCPUThread`), rebuild, reinstall, testei de novo com o mesmo jogo,
save state do zero — **a trava reproduziu igual, com o código original.** Ou seja:

> **A trava em `saveStateToSlot` é um bug pré-existente, não relacionado ao fix de hoje.** Já batia
> com a única ocorrência de `saveStateToSlot` na telemetria original (error 731). O `Host::RunOnCPUThread`
> não causa nem piora — só não resolve.

Hipótese de causa raiz (não confirmada, precisa de instrumentação): o busy-poll em `s_execute_exit`
(`saveStateToSlot`/`loadStateFromSlot`, main.cpp) assume que `VMManager::SetPaused(true)`, chamado de
uma thread `std::async` arbitrária (nem UI nem CPU thread), faz `Cpu->Execute()` retornar em poucos
ciclos — mas isso pode não valer com gameplay real em andamento (só foi observado funcionando com a
VM parada na tela de idioma da BIOS, sem jogo de verdade rodando). Se `Execute()` nunca retorna, o
laço de 5 tentativas (`sleep(1)` × 5) deveria pelo menos desistir e devolver `false` em ~5s — mas a
trava observada passou de 10s (watchdog do Android) sem sinal de recuperação, e as telas idênticas
sugerem que **nem a GS thread está avançando**, o que não bate com "só a UI thread esperando". Não
deu para isolar mais fundo sem instrumentação nova (log nativo fica desabilitado no perfil de
performance) — registrado para investigação futura dedicada.

**Revertido nesta triagem:** `Host::RunOnCPUThread(..., block=true)` em `saveStateToSlot`/
`loadStateFromSlot` (main.cpp) — voltou para a chamada direta original. O `Host::RunOnCPUThread`
não-bloqueante nos 10 pontos de settings **continua em uso** (validado, funcionando).

**Não testado:** o cenário exato de "trocar de jogo em <2s" do bug `vmmanager-abort-pxassertrel`
(sem tempo/sessão hábil após a investigação da trava de save state).

**Nenhum SIGABRT/`pxAssertRel` observado em nenhum dos testes de hoje**, incluindo múltiplos
boots/force-stops e o boot que falhou por permissão de arquivo negada (`runVMThread` retornou
`false` graciosamente, sem abortar).

## Conclusão e status

- **Settings/renderer (`setSetting`, `renderGpu`, `setEnableCheats`, etc.):** fix validado em device com jogo real.
- **`saveStateToSlot`/`loadStateFromSlot`:** resolvido em 2026-08-18 com a implementação do `ScopedVMPause` (chamando `Cpu->ExitExecution()` ao pausar), `recSafeExitExecution()` atômico e marshaling das operações para a CPU thread via `Host::RunOnCPUThread(..., block=true)`.
- **Status:** Todas as frentes corrigidas. Movido para `docs/bugs/retest`.

## 🔁 Recorrência — triagem de 2026-08-22

Dois subgrupos foram separados na janela completa dos erros abertos:

- **1473**, app 1.0.8: repetição literal do ANR já corrigido, com
  `MainActivity.lambda$setupRendererToggleGroup$56` chamando `NativeApp.renderGpu` na UI thread.
  É somente telemetria legada; não indica regressão nas versões atuais.
- **1469, 1470, 1471, 1506, 1554, 1624 e 1658**, apps 1.0.16–1.0.19: lacuna real no fix. Todos
  abortam no caminho `MainActivity.setFastForwardEnabled` → `NativeApp.speedhackLimitermode`.

O build-id `5a8cfc8c2a1e52338584768525c23fb9a9ac1f9f` dos IDs 1624/1658 é o mesmo do binário Release
local e permitiu simbolização exata:

```text
abort
pxOnAssertFail
MTGS::SetVSyncMode                         MTGS.cpp:980
MTGS::UpdateVSyncMode
VMManager::UpdateTargetSpeed              VMManager.cpp:2175 (linha do build)
NativeApp.speedhackLimitermode
```

O assert é `pxAssertRel(IsOpen(), "MTGS is running")`. No código atual,
[`speedhackLimitermode`](../../../app/src/main/cpp/main.cpp#L1114) ainda chama
`VMManager::SetLimiterMode()` diretamente no thread JNI/UI, inclusive durante `stopEmuThread`,
quando o MTGS pode já estar fechado. Esse entrypoint não entrou no conjunto marshaled por
`Host::RunOnCPUThread`; portanto o status “todas as frentes corrigidas” não cobre fast-forward.

**Correção de 2026-08-22:** `speedhackLimitermode` agora é enfileirado por `Host::RunOnCPUThread` e
revalida `HasValidVM()` e `MTGS::IsOpen()` já na CPU thread antes de chamar `SetLimiterMode`. Um tap
atrasado durante shutdown vira no-op e não alcança mais `MTGS::SetVSyncMode`. Build completo passou.
Esta recorrência não tem relação com a tela vermelha; era ciclo de vida/thread ownership.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
