# Bug: ANR — `stopEmuThread()` faz `Thread.join()` sem timeout na UI thread (saída do jogo)

- **Detectado em:** 2026-07-16 → 2026-08-01 21:26 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`Object.java::java.lang.Object.wait`)
- **Errors (serviço):** 758, 656, 607, 605, 549, 541, 540, 538, 501, 488 (**10 ocorrências**) +
  **780, 783, 826, 827, 828** (recorrência 2026-08-05, ver seção abaixo) — **15 ocorrências no
  total**
- **Classe:** fail (ANR — `main thread unresponsive >5000ms`)
- **Reincidência:** recorrente; Android 14/15/16, `app_version 1.0.8` em todas as amostras

## 🔁 Recorrência — triagem de 2026-08-05

Ainda não corrigido (`join()` em `MainActivity.stopEmuThread` continua sem timeout). **5 IDs
novos**, mesmos dois caminhos de entrada já documentados:

- **Botão voltar / tecla** — 783 (`onKeyUp` → `shutdownVmToHome` → `stopEmuThread` @ MainActivity.java:4253)
- **Destruição da Activity** — 780, 826, 827 (`onDestroy` @ MainActivity.java:3975 → `stopEmuThread`)
- **828** — mesmo `file`/`method` (`Object.java::java.lang.Object.wait`) mas **sem log anexado**
  (`logs_count=0`); classificado por padrão de campo, confiança menor que os demais.

O logcat anexado ao error 780 (`seq=1`) captura **3 ocorrências do SIGABRT em
`VMManager::Initialize`** no mesmo device dentro de ~8h — reforça o vínculo com
[`vmmanager-abort-pxassertrel-vm-nao-shutdown`](./vmmanager-abort-pxassertrel-vm-nao-shutdown_2026-08-02T19-19.md)
já registrado abaixo: o mesmo ciclo de vida mal fechado continua ativo em produção (o fix está só
local/uncommitted).

## 🔁 Recorrência — triagem de 2026-08-19

- **Janela agora:** telemetria completa dos "Aberto" em 2026-08-19 → **16 novos IDs**:
  - Em 1.0.8 (7 ocorrências): 1088, 1000, 875, 874, 873, 867, 854
  - Em 1.0.10 (9 ocorrências): 1419, 1417, 1379, 1336, 1313, 1306, 1195, 1134, 1133
- **Causa da persistência:** o fix de timeout delimitado `mEmulationThread.join(1500)` foi validado e aplicado no código local em 2026-08-18/19, e entrará em distribuição na próxima versão (1.0.14+).


## Sintoma

ANR com a main thread parada dentro de `Thread.join()`, esperando o thread de emulação terminar.
Dois caminhos de entrada, ambos na UI thread:

**(a) Botão voltar** (errors 656, 605, …):
```
at java.lang.Object.wait(Native Method)
at java.lang.Thread.join(Thread.java:2227)
at kr.co.iefriends.pcsx2.activities.MainActivity.stopEmuThread(MainActivity.java:4253)
at kr.co.iefriends.pcsx2.activities.MainActivity.shutdownVmToHome(MainActivity.java:5539)
at kr.co.iefriends.pcsx2.activities.MainActivity$1.handleOnBackPressed(MainActivity.java:260)
at androidx.activity.OnBackPressedDispatcher.onBackPressed(OnBackPressedDispatcher.kt:279)
```

**(b) Destruição da Activity** (error 758, …):
```
at java.lang.Thread.join(Thread.java:2227)
at kr.co.iefriends.pcsx2.activities.MainActivity.stopEmuThread(MainActivity.java:4253)
at kr.co.iefriends.pcsx2.activities.MainActivity.onDestroy(MainActivity.java:3975)
at android.app.Activity.performDestroy(Activity.java:10101)
```

## Causa raiz (CONFIRMADA no código)

[`MainActivity.stopEmuThread(boolean)`](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L4249):

```java
private synchronized void stopEmuThread(boolean forceShutdown) {
    if (mEmulationThread != null) {
        NativeApp.shutdown();
        try {
            mEmulationThread.join();          // MainActivity.java:4253 — SEM timeout
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        mEmulationThread = null;
    }
    ...
}
```

`join()` sem argumento espera **indefinidamente**. O thread de emulação só termina quando o laço de
[`runVMThread`](../../../app/src/main/cpp/main.cpp#L1410) sai (estado `Stopping`/`Shutdown`) e
`VMManager::Shutdown(false)` retorna — o que inclui parar a GS thread e liberar recursos. Se isso
passar de 5 s (savestate grande, GS travada, storage lento), a main thread já está em ANR.

O método é `synchronized` na Activity, então o bloqueio ainda serializa qualquer outro caminho que
toque o ciclo de vida da emulação.

**Ligação com o crash de boot:** quando esse shutdown não converge a tempo, o boot seguinte encontra
a VM em estado sujo e o core aborta em `pxAssertRel` — ver
[`vmmanager-abort-pxassertrel-vm-nao-shutdown_2026-08-02T19-19.md`](./vmmanager-abort-pxassertrel-vm-nao-shutdown_2026-08-02T19-19.md).
São as duas pontas do mesmo ciclo de vida mal fechado: aqui a UI espera demais, lá ela desiste de
esperar e boota mesmo assim.

## Como reproduzir

1. Rodar um jogo pesado por alguns minutos (para o estado da VM ficar grande).
2. Apertar voltar para sair para a Home (ou girar/matar a Activity para disparar `onDestroy`).
3. Em device com storage lento, a tela congela; passando de 5 s, ANR.

## Resolução (CONFIRMADA e corrigida — 2026-08-18)

1. **Timeout delimitado em `mEmulationThread.join(1500)` (`MainActivity.java:4287`):**
   A chamada de `mEmulationThread.join()` sem argumento na UI thread foi substituída por `mEmulationThread.join(1500)`. Esse limite de 1,5 segundo impede que qualquer atraso no teardown da thread de emulação (savestates pesados, flush de disco) ultrapasse o limite de 5s do watchdog de ANR do Android.
2. **Serialização nativa via `AcquireVMThreadSlot` (`main.cpp`):**
   Como o ciclo de vida nativo da VM é serializado por `AcquireVMThreadSlot` e `s_vm_lifecycle_mutex`, se a thread de emulação anterior levar alguns milissegundos extras para completar o `VMManager::Shutdown(false)`, a próxima tentativa de boot aguarda com segurança na thread de background sem travar a interface e sem disparar assertions.

Status: **Corrigido no código local (2026-08-18).**

## 🔁 Recorrência — triagem de 2026-08-22

Os errors **1620** (destruição/recriação da Activity) e **1694** (reinício ao abrir outro jogo)
repetem exatamente `stopEmuThread` → `Thread.join()` sem timeout. Ambos são **app 1.0.8**, portanto
são telemetria do binário legado; não contradizem o `join(1500)` presente no código atual e não têm
relação com a tela vermelha.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
