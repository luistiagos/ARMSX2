# Bug: `CNTFRQ_EL0` lê 0 no Exynos 850, e todo relógio de ticks do emulador vai junto

- **Detectado em:** 2026-08-30 (medição dirigida no Galaxy A12 `SM-A127M`, build `githubDebug`)
- **Origem:** `GetTickFrequency()` / `GetCPUTicks()` (`common/Linux/LnxMisc.cpp`)
- **Errors (serviço):** nenhum — não gera crash, ANR nem log. É silencioso por construção.
- **Classe:** correção / performance
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0060](../../task/TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md)

## Sintoma

Três defeitos que pareciam separados, e são um só:

1. **`PerfLog` reporta `EE 0% GS 0% VU 0%`** com o jogo a 8 fps e a 50 fps — item 0 do backlog
   [`desempenho-com-clock-cortado-a55`](../../backlog/desempenho-com-clock-cortado-a55.md).
2. **A thread `MTVU` queima ~90% de um núcleo** em estado `R`, com a VM **pausada** e nada a
   fazer — item 1 do mesmo backlog, e a pergunta que a
   [TASK-0046](../../task/TASK-0046-encerrar-thread-mtvu-no-shutdown.md) deixou em aberto.
3. **O limitador de quadros não limita nada** (`VMManager::Throttle`).

## Causa

`CNTFRQ_EL0` **lê 0** neste aparelho. Confirmado pelo próprio app:

```
HostSys: CNTFRQ_EL0 reads 0 (firmware did not program it); using CLOCK_MONOTONIC for GetCPUTicks()
```

Programar esse registrador é trabalho do firmware, e a arquitetura não obriga ninguém a fazê-lo. O
Linux não se importa: ele pega a frequência do device tree, e o vDSO dele nunca divide pelo
registrador de EL0. **O nosso divide, em todo lugar** — e no AArch64 `UDIV`/`SDIV` por zero **não
tem exceção: devolve 0 em silêncio.** Não quebra, mente.

Cada sintoma acima é uma divisão por essa zero:

| onde | expressão | com freq = 0 |
|---|---|---|
| `ShortSpinOn` (`common/HostSys.cpp:147`) | `(elapsed * 1e9) / GetTickFrequency()` | `0` — inteiro, `udiv` |
| `PerformanceMetrics::Update` (`pcsx2/PerformanceMetrics.cpp:362`) | `100.0 * (1.0 / ((ticks*1e6) / freq))` | `100 * (1/+inf)` = `0.0` — **double**, sem trap |
| `VMManager::UpdateTargetSpeed` (`pcsx2/VMManager.cpp:2439`) | `s64(double(freq) / fps)` | `0` ticks por quadro |

E o de `ShortSpinOn` é o que produz o item 2 do sintoma: `WaitForWorkWithSpin()`
(`common/Semaphore.cpp:63`) acumula `waited += ShortSpinOn(...)` até `SPIN_TIME_NS` antes de dormir
no semáforo. Somar zero para sempre nunca chega a 50 µs, então a thread **nunca alcança o
`m_sema.Wait()`** e gira indefinidamente. O `std::max(elapsed, 1)` que existe justamente para a
contagem não travar não ajuda: ele garante o numerador, e quem zera é o denominador.

Por que o `fps` do mesmo `PerfLog` sai certo: `Common::Timer` usa `CLOCK_MONOTONIC`, não este
relógio. Só as figuras que dividem por `GetTickFrequency()` morrem.

## Evidência medida

Galaxy A12 (`SM-A127M`, Exynos 850, 8× Cortex-A55, Mali-G52, Android 13).

**A prova direta de onde a MTVU estava**, com uma sonda temporária lendo o estado de outra thread
(uma thread presa num laço de userspace não consegue registrar nada sobre si mesma):

```
@@MTVU_PROBE@@ open=1 turns=1751 state=-2 done=1     <- 18 s depois, os mesmos 1751
@@MTVU_PROBE@@ open=1 turns=1751 state=-2 done=1
```

`turns` **congelado** ⇒ presa **dentro de uma única** chamada a `WaitForWorkWithSpin()`, não
girando no laço externo. `state=-2` é `STATE_SPINNING`. `done=1` é o ring buffer vazio.

E o `/proc` fecha o caso:

| | antes | depois |
|---|---|---|
| `State` (VM pausada) | `R (running)` | `S (sleeping)` |
| CPU | 89–91% de um núcleo | 0% |
| `voluntary_ctxt_switches` | **`0`** — na vida inteira da thread | 4 |
| `nonvoluntary_ctxt_switches` | 1246 → 1309 em 10 s | 3 |

`voluntary_ctxt_switches == 0` é o número que decide: a thread **nunca bloqueou, uma vez sequer**.

E os contadores de desempenho, mesmo jogo, mesmo save:

```
antes:   PerfLog: 25.6 fps | EE 0%   GS 0%  VU 0% GPU 0% | frame 771
depois:  PerfLog: 25.0 fps | EE 100% GS 37% VU 0% GPU 0% | frame 758
```

`EE 100%` — a emulação neste aparelho é limitada pela thread EE. É a primeira vez que essa
informação existe.

`VU 0%` está **certo**: com o giro corrigido, a MTVU fica em `S` a 0% também **durante o jogo**, o
que mostra que os ~100% que ela consumia jogando eram o mesmo giro quebrado, e não trabalho de VU1.
É um núcleo de oito recuperado o tempo todo, não só na pausa.

`GPU 0%` continua e **não é este defeito** — é o backend não produzir tempo de GPU. Fica em aberto.

## Como reproduzir

```bash
# 1. o registrador, pelo log do proprio app
adb shell "grep -E 'CNTFRQ_EL0|architected timer' \
  /storage/emulated/0/Android/data/come.nanodata.armsx2/files/logs/emulog.txt"

# 2. o sintoma da MTVU: abrir um jogo com mtvu=1, PAUSAR a VM, bloquear a tela
PID=$(adb shell pidof come.nanodata.armsx2)
adb shell "for t in /proc/$PID/task/*; do [ \"\$(cat \$t/comm)\" = MTVU ] && \
  grep -E '^(State|voluntary)' \$t/status; done"
```

Antes: `State: R (running)` com `voluntary_ctxt_switches: 0`. Depois: `S (sleeping)`, e o contador
cresce.

**Atenção ao amostrar `/proc/<tid>/stat`:** o campo `comm` vem entre parênteses e **pode conter
espaços** (`CPU Thread`), o que desloca todo `awk '{print $14+$15}'`. Contar os campos a partir do
**último** `)`. E, em `sh`, `${12}` — `$12` é `$1` seguido de um `2` literal, o que faz a soma dar
zero e a thread parecer ociosa. As duas armadilhas custaram uma medição cada nesta investigação.

## Por que passou despercebido

Não há sinal de erro em lugar nenhum: divisão inteira por zero no AArch64 devolve 0, divisão de
ponto flutuante devolve `+inf`, e ambas produzem números que **parecem** medições. Um `0%` num
contador de CPU lê-se como "thread ociosa"; um limitador de quadros que não dorme lê-se como
"aparelho lento". Nenhum desktop tem o problema — lá o firmware programa o registrador —, então o
upstream nunca o veria.

## Alcance

Todo alvo AArch64 cujo firmware não programe `CNTFRQ_EL0`. Não é exclusivo do Exynos 850, e não é
detectável a não ser lendo o registrador — que é o que a correção passa a fazer.
