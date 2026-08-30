# Bug: a thread da MTVU sobrevive ao fim da VM e gira a 100% de um núcleo

- **Detectado em:** 2026-08-28 (medição dirigida no Galaxy A12 `SM-A127M`, build 1.0.24)
- **Origem:** `VMManager::ShutdownCPUProviders()` / `VU_Thread` (`pcsx2/MTVU.cpp`)
- **Errors (serviço):** nenhum — não gera crash nem ANR, é vazamento silencioso
- **Classe:** vazamento de recurso / performance
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0046](../../task/TASK-0046-encerrar-thread-mtvu-no-shutdown.md) (o sintoma no shutdown), [TASK-0056](../../task/TASK-0056-wfe-sem-event-stream-trava-o-spin.md) (a causa do giro)

## Sintoma

`vu1Thread.Close()` **não é chamado em lugar nenhum da árvore.** Só `Open()`, a partir de
`mVUreset` (`arm64/microVU-arm64.cpp:1934` e `x86/microVU.cpp:318`). O laço da thread só sai por
`m_shutdown_flag`, e esse sinal só é levantado dentro de `Close()`:

```cpp
void VU_Thread::ExecuteRingBuffer()
{
	Threading::SetNameOfCurrentThread("MTVU");
	PerformanceMetrics::AdpfRegisterCallingThread();

	for (;;)
	{
		semaEvent.WaitForWorkWithSpin();
		if (m_shutdown_flag.load(std::memory_order_acquire))
			break;
		...
	}
	semaEvent.Kill();
}
```

`vu1Thread` é objeto global (`pcsx2/MTVU.cpp:13`), então quem encerra a thread é o destrutor
`~VU_Thread()` — **no fim do processo**, não no fim da VM. Ela atravessa o shutdown, o menu, a
navegação no catálogo, os downloads e a abertura do jogo seguinte.

`MTGS::ShutdownThread()` é chamada em `VMManager::Internal::CPUThreadShutdown()`, logo antes de
`ShutdownCPUProviders()`. A MTVU é a única thread do núcleo sem o par correspondente — a
assimetria é o bug.

## Evidência medida

Galaxy A12 (`SM-A127M`, Exynos 850, 8× Cortex-A55 @ 2,0 GHz, Mali-G52, Android 13), rodando
`come.nanodata.armsx2` 1.0.24. A VM já estava encerrada — última linha do `emulog.txt` é
`Releasing host memory for virtual systems...`, o `Console.WriteLn` de `SysMemory::Release()`
(`pcsx2/Memory.cpp:359`), chamado imediatamente depois de `ShutdownCPUProviders()` — e o app
parado na tela de Downloads. Mesmo assim:

```
TID 8727  MTVU  estado=R  cpu_s=1519  idade_s=1520
delta: 2009 jiffies em 20,1 s  =>  99,95% de um núcleo
```

CPU consumida igual à idade inteira da thread. A thread `CPU Thread` já não existia. Rodar e parar
uma segunda sessão de VM **não** criou uma segunda MTVU (o `Open()` retorna cedo se `IsOpen()`),
então não multiplica por sessão: é **um núcleo perdido, de forma permanente, do primeiro jogo até
o processo morrer**.

Num Exynos 850 — sem núcleo grande e sem dissipação — é 1/8 da CPU queimada à toa, mais o calor
correspondente, num aparelho que já roda o 007 Agent Under Fire a ~29 fps de 59,94 (~48%).

## Como reproduzir

Abrir um jogo com `mtvu=1` no `emulog.txt`, jogar, **parar** o jogo pela UI. Com o app ainda no
menu:

```bash
PID=$(adb shell pidof come.nanodata.armsx2)
adb shell "for t in /proc/$PID/task/*; do cat \$t/comm; done" \
  | grep -E '^(MTVU|GS|CPU Thread)$'
```

`CPU Thread` some; `MTVU` continua listada. É esse o defeito.

**Atenção ao nome da thread:** o núcleo emula no `CPU Thread` (`VMManager.cpp`), não numa thread
chamada `EE`. Filtrar por `EE` no `comm` não acha nada e faz o teste parecer que passou — foi o
que estragou a primeira medição de linha de base desta investigação.

## Por que o spin não termina sozinho

**Respondido em 2026-08-30** — ver [TASK-0056](../../task/TASK-0056-wfe-sem-event-stream-trava-o-spin.md).
O texto original desta seção fica abaixo porque o raciocínio que ele faz é justamente o que tinha o
furo, e vale ver onde.

O furo: o teto de `SPIN_TIME_NS` só é conferido **entre** chamadas a `ShortSpinOn()`. Ele não
limita uma chamada que não retorna — e no ARM64 `ShortSpinOn()` é um `WFE`
(`MonitoredWait`, `common/HostSys.cpp:129`), que só acorda por uma escrita na palavra observada ou
pelo event stream do timer arquitetural. Com a VM encerrada não há quem escreva; e o event stream é
opção de kernel (`CONFIG_ARM_ARCH_TIMER_EVTSTREAM`), publicada em `AT_HWCAP` como `HWCAP_EVTSTRM`.
Onde ele está desligado, aquele `WFE` não volta nunca.

Isso também explica por que o "mínimo de 1 tick" não ajudou: ele garante que `waited` avança **a
cada retorno**, e o problema é não haver retorno.

E fecha o formato da evidência: `WFE` não é `yield`, a thread continua *runnable*, e o kernel
segue cobrando tempo de CPU dela. Por isso a medição encontra a thread em estado **`R`** com tempo
de CPU igual à idade — se ela tivesse chegado ao `m_sema.Wait()`, estaria em `S`.

A hipótese registrada abaixo (laço externo girando em falso) está **descartada**: um laço em falso
passaria pelo `m_sema.Wait()` a cada volta e a thread apareceria em `S` em alguma amostra.

> ~~Não foi determinado, e não precisou ser. `SPIN_TIME_NS` é 50 µs (`common/HostSys.cpp:176`) e
> `ShortSpinOn()` (`common/HostSys.cpp:147`) cobra no mínimo 1 tick justamente para a contagem
> nunca travar, então `WaitForWorkWithSpin()` deveria cair em `m_sema.Wait()` e dormir. A hipótese
> é que o laço externo de `ExecuteRingBuffer()` gira em falso — `WaitForWorkWithSpin()` retornando
> na hora com o ring buffer vazio —, mas isso **não está provado**. Encerrar a thread remove o
> sintoma qualquer que seja o gatilho interno. Fica registrado como pergunta em aberto.~~

## Por que passou despercebido

Não gera crash, ANR nem log. No desktop, onde a VM em geral vive tanto quanto o processo, o
vazamento é invisível. Só tem custo onde o app continua aberto depois do jogo — que é exatamente
o fluxo do Android, em que parar o jogo devolve o usuário para a Home.

## Nota de ramo

O mesmo vazamento existe na `feature/handoff-end-to-end`, onde foi corrigido pela TASK-0020
**daquele ramo** (número que aqui significa outra coisa — ver
[colisão de números](../open/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md)). Lá o laço
usa `WaitForWork()` bloqueante, então a thread vazada dorme em vez de girar: mesmo vazamento,
custo diferente. Este registro é o do ramo que publica.
