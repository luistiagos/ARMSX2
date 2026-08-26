# Bug: SIGABRT no boot do jogo — `pxAssertRel(state == Shutdown)` em `VMManager::Initialize` aborta o app

- **Detectado em:** 2026-07-12 → 2026-08-02 19:19 (telemetria de produção)
- **Origem:** telemetria `armsx2/native` (`native::signal 6`) + tombstones textuais recuperados dos
  logcats anexados aos reports `armsx2/anr`
- **Errors (serviço):** assinatura recuperada e simbolizada nos errors **574, 570, 645, 739, 488,
  514, 656, 757** (logcat com tombstone legível). É a assinatura dominante — **19 de 21** tombstones
  recuperáveis de toda a telemetria aberta. Os **230 reports `armsx2/native` com `signal 6`**
  (SIGABRT) não são atribuíveis individualmente porque chegam binários — ver
  [`native-crash-reporter-tombstone-binario-nao-decodificado_2026-07-16T02-49.md`](./native-crash-reporter-tombstone-binario-nao-decodificado_2026-07-16T02-49.md)
- **Classe:** crash
- **Reincidência:** primeira vez que a causa raiz é isolada. Recorrente em campo desde
  2026-07-12, em Android 11–16, múltiplos fabricantes, `app_version 1.0.8` em todas as amostras

> **Este é o achado que destravou a triagem dos SIGABRT.** O reporter nativo continua quebrado
> (bug acima), mas o logcat anexado aos reports de **ANR** capturou o texto `F DEBUG` do tombstone
> de crashes nativos anteriores no mesmo device — foi daí que a assinatura foi recuperada.

## Sintoma

Crash nativo `signal 6 (SIGABRT), code -1 (SI_QUEUE)` na thread **`CPU Thread`**, com
`Abort message` ausente na maioria das amostras. Backtrace (idêntico em todas as amostras, com
variação só nos frames de trampolim do ART):

```
#00 pc 0x94184   libc.so       abort+164
#01 pc 0xa374f8  libemucore.so AbortWithMessage(char const*)+8
#02 pc 0xa1dee4  libemucore.so pxOnAssertFail(char const*, int, char const*, char const*)+132
#03 pc 0x59cf8c  libemucore.so VMManager::Initialize(VMBootParameters)+4552
#04 pc 0x8bed24  libemucore.so Java_kr_co_iefriends_pcsx2_NativeApp_runVMThread+524
#05              art_jni_trampoline
#07              MainActivity.lambda$startEmuThread$105
...              java.lang.Thread.run
```

Só 19 frames no total — o abort acontece **4 frames abaixo do entrypoint JNI**, ou seja, ainda
dentro da inicialização da VM, **antes de o jogo começar a rodar**.

## Causa raiz (CONFIRMADA — simbolização exata contra o binário de produção)

O build local [`app/.cxx/Release/a3y4u491/arm64-v8a/bin/libemucore.so`] tem
**build-id `cae2d88186f6a3c046d36751b59d67adcf6699fc`**, idêntico ao do `libemucore.so` do 1.0.8 em
produção nos tombstones. `llvm-symbolizer` sobre esse binário resolve os frames para:

| pc | símbolo | fonte |
|---|---|---|
| `0xa374f8` | `AbortWithMessage(char const*)` | [common/HostSys.cpp:136](../../../app/src/main/cpp/common/HostSys.cpp#L136) |
| `0xa1dee4` | `pxOnAssertFail(...)` | [common/Assertions.cpp:116](../../../app/src/main/cpp/common/Assertions.cpp#L116) |
| `0x59cf8c` | `VMManager::Initialize(VMBootParameters)` | **[pcsx2/VMManager.cpp:1279](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L1279)** |
| `0x8bed24` | `Java_..._NativeApp_runVMThread` | [main.cpp:1405](../../../app/src/main/cpp/main.cpp#L1405) |

A linha que aborta é a **primeira instrução real** de `VMManager::Initialize`:

```cpp
// app/src/main/cpp/pcsx2/VMManager.cpp:1279
pxAssertRel(s_state.load(std::memory_order_acquire) == VMState::Shutdown, "VM is shutdown");
```

`pxAssertRel` é assertion de **release** (não é compilada fora em release) → `pxOnAssertFail` →
`AbortWithMessage` → `abort()`. Ou seja: **iniciar a VM quando o estado não é exatamente
`VMState::Shutdown` mata o processo, por design do core.** Qualquer estado residual
(`Running`, `Stopping`, `Closing`, `Paused`) aborta.

### Quem viola a pré-condição

[`MainActivity.startEmuThread()`](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L4196)
detecta a condição insegura, registra um aviso e **prossegue mesmo assim**:

```java
// MainActivity.java:4213-4243
stopEmuThread(false);
for (int attempts = 0; attempts < 40 && NativeApp.hasValidVm(); attempts++) {
    SystemClock.sleep(50);                      // espera no máx. 2 s
}
if (NativeApp.hasValidVm()) {
    NativeApp.shutdown();
    SystemClock.sleep(100);
    if (NativeApp.hasValidVm()) {
        DebugLog.w("VM", "VM still reporting active after shutdown; proceeding with clean boot");
    }                                            // <-- detecta e NÃO aborta o boot
}
...
mEmulationThread = new Thread(() -> { ... NativeApp.runVMThread(m_szGamefile); });
mEmulationThread.start();                        // -> Initialize() -> pxAssertRel -> abort()
```

O caminho `runVMThread` (main.cpp:1398-1405) também não checa o estado antes de chamar
`Initialize`: só faz `CPUThreadInitialize()` + `ApplySettings()` e entra direto.

Quando o shutdown anterior não converge dentro dos ~2,1 s de janela (disco lento, savestate grande,
GS thread travada, jogo pesado), o boot seguinte cai direto no `abort()`. Isso explica por que o
crash é **no boot**, aparece em devices variados e não se correlaciona com um jogo específico.

**Ligação com os ANRs:** o mesmo shutdown que não converge é o que trava a UI thread em
[`stopemuthread-join`](./mainactivity-anr-stopemuthread-join-sem-timeout_2026-08-01T21-26.md) —
os dois bugs são as duas pontas do mesmo ciclo de vida mal fechado.

## Assinaturas secundárias (mesmo `abort`, entrada diferente)

Recuperadas junto, com 1 ocorrência cada — provavelmente o mesmo assert por outro caminho:

- `abort ← libemucore ×3 ← Java_..._NativeApp_reloadDataRoot+976 ← NativeApp.reinitializeDataRoot`
  — troca de data root com VM viva.
- `abort ← libsigchain SignalChain::Handler ← __kernel_rt_sigreturn ← ANativeWindow_setBuffersGeometry+20 ← libemucore ×3`
  — fault na GS thread ao reconfigurar a surface (provável use-after-free de `ANativeWindow` em
  destruição de surface concorrente).
- Único `Abort message` capturado em toda a telemetria, na thread **GS**:
  `Unhandled page fault: sig=11 pc=0x7a21d6a498 addr=0x98 write=0` — deref de ponteiro nulo
  (`addr=0x98` = campo em offset 0x98 de objeto nulo), caindo no handler de
  [`LnxHostSys.cpp:414`](../../../app/src/main/cpp/common/Linux/LnxHostSys.cpp#L414) que loga e aborta.
  **Não** é fastmem/JIT (endereço baixo demais).

## Como reproduzir

Não há repro determinístico de campo, mas a pré-condição é forçável:

1. Iniciar um jogo pesado (boot longo / savestate grande).
2. Voltar para a Home (back) e **imediatamente** (< 2 s) abrir outro jogo — ou usar
   "reiniciar emulação" em sequência rápida.
3. Observar `logcat -s DEBUG NDK_LOG` — quando o warning
   `VM still reporting active after shutdown; proceeding with clean boot` aparecer, o
   `SIGABRT` em `VMManager::Initialize` vem logo em seguida.

Alternativa determinística para validar o diagnóstico: forçar `s_state != Shutdown` antes de
`runVMThread` (ex.: chamar `startEmuThread()` sem deixar o `stopEmuThread` completar) e confirmar
o abort com o mesmo backtrace.

## Correção do diagnóstico (2026-08-03)

A causa raiz acima está certa quanto ao ponto de falha, mas **errada quanto ao mecanismo**: não é um
shutdown lento que ocasionalmente não converge em 2,1 s. São dois defeitos determinísticos.

### 1. `hasValidVm()` é falso durante *todo* o teardown, não depois dele

```cpp
enum class VMState { Shutdown, Initializing, Running, Paused, Resetting, Stopping };

bool VMManager::HasValidVM()   // VMManager.cpp:311
{
    const VMState state = s_state.load(std::memory_order_acquire);
    return (state >= VMState::Running && state <= VMState::Resetting);   // {Running, Paused, Resetting}
}
```

`Stopping` está **fora** desse intervalo. E `VMManager::Shutdown()` (VMManager.cpp:1603) começa
gravando `Stopping`, faz o teardown inteiro — `MTGS::WaitGS()`, flush de savestate, `SPU2::Close()`,
`FileMcd_EmuClose()`, … — e só grava `Shutdown` no fim (linha 1677).

Ou seja: **`hasValidVm()` vira false no primeiro instante do shutdown.** O portão do
`startEmuThread()` abria imediatamente, no meio do teardown, e o boot seguinte batia no
`pxAssertRel`. Não era uma janela de 2,1 s estourada — era a janela inteira, sempre. Por isso o
warning `VM still reporting active after shutdown` quase nunca aparecia junto do crash: ele só
cobria `Running`/`Paused`/`Resetting`, e o estado real no momento do abort era `Stopping`.

### 2. `NativeApp.shutdown()` sem VM envenenava o estado permanentemente

```cpp
// main.cpp, antes
Java_..._NativeApp_shutdown(...) {
    std::thread([] { VMManager::SetState(VMState::Stopping); }).detach();
}
```

Incondicional. `stopEmuThread(true)` (MainActivity:4258-4260) chama isso mesmo com
`mEmulationThread == null`, ou seja, sem VM nenhuma. Isso levava `Shutdown` → `Stopping` sem
ninguém para completar o teardown e voltar para `Shutdown`. **A partir daí, todo boot abortava**,
100% das vezes, até o processo morrer. `Host::RequestVMShutdown()` (main.cpp:1975) tinha o mesmo
problema.

Isso explica o que o relatório não explicava: crash no boot, sem correlação com jogo, em devices
variados, e sem o warning no log.

> Nota: `FullscreenUI::DoStartPath/DoStartBIOS/DoStartDisc` usam o mesmo predicado furado
> (`if (VMManager::HasValidVM()) return;`), mas são inalcançáveis nesta porta — passam por
> `Host::RunOnCPUThread()`, que é `pxFailRel("Not implemented")` em main.cpp:1937. `runVMThread` é
> o único caminho que chega em `Initialize` no Android.

## Fix aplicado (2026-08-03)

Serialização explícita do ciclo de vida da VM em `main.cpp` (`s_vm_lifecycle_mutex` +
`s_vm_thread_active`), que é o que garante a pré-condição do `pxAssertRel`:

| Arquivo | Mudança |
|---|---|
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `AcquireVMThreadSlot()` / `ReleaseVMThreadSlot()` — só uma thread de emulação por vez; o boot **espera** (até 15 s, na thread de emulação, nunca na UI) a anterior soltar o slot |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `runVMThread`: com o slot na mão, exige `GetState() == Shutdown`; senão recupera um `Stopping` órfão ou **retorna `false`** — nunca chega no `pxAssertRel` |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `runVMThread` passa a retornar o resultado real de `Initialize()` (antes era `true` fixo) e falha o boot se `CPUThreadInitialize()` falhar, em vez de seguir em frente |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `RequestVMStop()` — pedido de parada vira no-op quando não há thread de emulação viva; usado por `NativeApp.shutdown()` e `Host::RequestVMShutdown()`. Mata o envenenamento do item 2 |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `NativeApp.canBootVm()` (JNI novo) — `!s_vm_thread_active && state == Shutdown` |
| [main.cpp](../../../app/src/main/cpp/main.cpp) | `reloadDataRoot` recusa troca de data root com VM ativa (assinatura secundária) |
| [VMManager.h](../../../app/src/main/cpp/pcsx2/VMManager.h) / [VMManager.cpp](../../../app/src/main/cpp/pcsx2/VMManager.cpp) | `Internal::ClearOrphanedStoppingState()` — CAS `Stopping` → `Shutdown` para destravar estado órfão |
| [MainActivity.java](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java) | `startEmuThread()` usa `canBootVm()` em vez de `hasValidVm()`; espera na UI thread cai de 2,1 s para 300 ms (a espera longa foi para a thread de emulação) |
| [MainActivity.java](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java) | `onEmuBootFailed()` — boot recusado/falho agora mostra erro e volta pra Home, em vez de tela preta ou `abort()` |
| `res/values*/strings.xml` | `home_launch_failed` (en, pt-BR, ar) |

Sobre o item 3 do plano original (“fazer o shutdown convergir”): o shutdown **já convergia** — o
problema era o predicado errado e o envenenamento. Com o `RequestVMStop()` o estado não trava mais
em `Stopping`; se travar por outra causa, `ClearOrphanedStoppingState()` recupera e o boot segue.

**Status da validação:** compila e linka limpo (`libemucore.so`, NDK 29, arm64-v8a) e o Java compila
(`compileUnrestrictedDebugJavaWithJavac`).

## 🔁 Validação em device — 2026-08-05 (parcial)

Build instalado via `gradlew installUnrestrictedDebug` no Moto G86 5G (Android 16), com este fix +
o de [`mainactivity-anr-jni-emulador-ui-thread`](./mainactivity-anr-jni-emulador-ui-thread_2026-08-01T17-05.md)
juntos (mesmo checkout). **Zero SIGABRT/`pxAssertRel`** em toda a sessão de testes — inclusive num
boot que falhou por permissão de arquivo negada (`file://` fora do escopo do SAF), onde `runVMThread`
voltou `false` de forma limpa em vez de abortar, e em vários ciclos de boot/força-parada/reboot ao
longo da sessão.

Não deu pra validar o repro específico documentado acima (troca de jogo em <2s dentro da mesma
sessão / "abrir e fechar a Home várias vezes sem rodar jogo, depois bootar"): sem uma ROM PS2 real
reconhecida, não foi possível navegar a UI de forma limpa o bastante pra forçar essa janela exata.
Uma tentativa de 4 ciclos rápidos de botão-voltar + `am start` via ADB (sem esperar cada ciclo
assentar) **não** produziu SIGABRT — produziu um ANR diferente, já documentado e ainda aberto em
[`mainactivity-anr-stopemuthread-join-sem-timeout`](./mainactivity-anr-stopemuthread-join-sem-timeout_2026-08-01T21-26.md)
(a GS thread seguiu rodando a 60fps o tempo todo, confirmando que não foi o core que travou). Isso é
evidência indireta a favor do fix (o cenário que antes abortava o processo agora, na pior hipótese,
vira um ANR diferente em vez de matar o app), mas não é a mesma coisa que confirmar a race exata do
diagnóstico.

## 🔁 Validação em device — 2026-08-05 (segunda rodada, com jogo PS2 real)

Baixado um jogo PS2 real pelo catálogo embutido do app (Crash Bandicoot - The Wrath of Cortex,
324 MB — ver detalhes em
[`mainactivity-anr-jni-emulador-ui-thread`](./mainactivity-anr-jni-emulador-ui-thread_2026-08-01T17-05.md#-validação-em-device--2026-08-05-segunda-rodada-com-jogo-ps2-real-via-catálogo)),
com CRC de disco válido. Múltiplos boots/reboots/force-stops ao longo da investigação de um ANR
não relacionado (trava em `saveStateToSlot`, ver o outro doc) — **zero SIGABRT, zero `pxAssertRel`**
em toda essa sessão adicional, incluindo os force-stops manuais seguidos de relançamento (que são
exatamente o tipo de "processo morto no meio da sessão + boot seguinte" que o fix deveria cobrir).

Ainda não isolei o repro exato de "trocar de jogo em <2s" via navegação de UI real (não tive sessão
disponível depois de investigar a trava de save state a fundo). O sinal é positivo mas continua
sendo evidência circunstancial, não confirmação direta do diagnóstico original.

**Item 2 dos "Próximos passos" (abaixo) confirmado:** `runVMThread: cleared an orphaned
VMState::Stopping` não apareceu em nenhum momento da sessão de testes de hoje.

**Conclusão:** validação parcial — forte evidência de que o `pxAssertRel` não dispara mais nos
caminhos exercitados, mas o repro exato da race de shutdown não foi isolado. Não movido para
`docs/bugs/done` por causa disso; recomendo uma sessão com ROM PS2 real e navegação manual (não
script ADB às cegas) antes de fechar.

## 🔁 Recorrência — triagem de 2026-08-05 (fix ainda não builda/distribui)

O fix de 2026-08-03 segue **local, não commitado** (confirmado pelo `git status` desta triagem —
`main.cpp`, `VMManager.cpp`/`.h`, `NativeApp.java`, `MainActivity.java` como *modified* sem commit)
e, portanto, **não chegou a nenhum device em produção**. A assinatura continua reproduzindo
exatamente igual: os logcats anexados aos ANRs novos 780, 792, 813, 826 (2026-08-03 → 2026-08-04)
trazem o mesmo backtrace (`abort → AbortWithMessage → pxOnAssertFail → VMManager::Initialize+4552
→ Java_..._NativeApp_runVMThread+524`, `libemucore.so` BuildId
`cae2d88186f6a3c046d36751b59d67adcf6699fc`, idêntico ao 1.0.8 em produção) — inclusive **3
recorrências no mesmo device em ~8h** no logcat do error 780. Nenhuma ação de código adicional
necessária aqui; registrado para não reabrir investigação à toa na próxima triagem. Ver também a
recorrência espelhada em
[`native-crash-reporter-tombstone-binario-nao-decodificado`](./native-crash-reporter-tombstone-binario-nao-decodificado_2026-07-16T02-49.md#-recorrência--triagem-de-2026-08-05).

## Próximos passos

1. Validar em device com o repro da seção “Como reproduzir” (troca rápida de jogo < 2 s) e com o
   caso do envenenamento: abrir e fechar a Home várias vezes sem rodar jogo nenhum, depois bootar —
   antes isso abortava, agora tem que bootar normal.
2. Confirmar no logcat que `runVMThread: cleared an orphaned VMState::Stopping` **não** aparece em
   uso normal (se aparecer, sobrou algum caminho pedindo stop sem VM).
3. Re-triar os `armsx2/native` novos para medir a queda no volume de SIGABRT (hoje 230 dos 236
   abertos) — e conferir o que sobra, que aí deve ser causa diferente.
4. As assinaturas secundárias da GS thread (`ANativeWindow_setBuffersGeometry`, page fault em
   `addr=0x98`) **não** são cobertas por este fix — continuam abertas.

## 🔁 Recorrência — triagem de 2026-08-22

### Inicialização de VM — somente build legado

Os tombstones protobuf dos IDs **1475, 1479, 1586 e 1591** (app 1.0.8) ainda preservam strings
suficientes para identificar exatamente:

```text
Java_kr_co_iefriends_pcsx2_NativeApp_runVMThread
VMManager.cpp:1279: assertion failed ... VM is shutdown
```

São recorrências legadas do bug principal, não regressão das versões atuais.

### `reloadDataRoot` — fix anterior incompleto e recorrente até 1.0.19

Os IDs **1557, 1605, 1623, 1627, 1654 e 1656** são a assinatura secundária já mencionada neste
documento, agora decodificada e recorrendo em app 1.0.16, 1.0.17, 1.0.18 e 1.0.19. O build-id
`5a8cfc8c2a1e52338584768525c23fb9a9ac1f9f` permite resolver:

```text
abort
pxOnAssertFail
Host::Internal::SetBaseSettingsLayer       Host.cpp:404
Java_..._NativeApp_reloadDataRoot+1140     main.cpp:799
```

A guarda atual de [`reloadDataRoot`](../../../app/src/main/cpp/main.cpp#L772) só recusa a operação se
uma VM estiver ativa. Sem VM, ela salva e destrói `s_settings_interface`, mas não remove o ponteiro
antigo de `LayeredSettingsInterface::LAYER_BASE`; em seguida chama
[`SetBaseSettingsLayer`](../../../app/src/main/cpp/pcsx2/Host.cpp#L402), cujo contrato exige que a
camada seja `nullptr`, e aborta em `pxAssertRel`.

**Correção de 2026-08-22:** foi adicionada `ReplaceBaseSettingsLayer(expected, replacement)`, sob o
mutex de settings. `reloadDataRoot` constrói e carrega toda a nova interface primeiro, troca o
ponteiro da layer atomicamente e só então destrói a interface antiga. Não existe mais janela com
ponteiro pendente nem nova chamada ao setter de inicialização. Build completo passou. Esta
recorrência era de migração de pasta de dados e não se relacionava à corrupção vermelha.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
