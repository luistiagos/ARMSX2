# TASK-0046: encerrar a thread da MTVU no shutdown da VM

- **Status:** concluída
- **Criada em:** 2026-08-28
- **Concluída em:** 2026-08-28
- **Feature:** nenhuma
- **Bugs que resolve:** [mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm](../bugs/done/mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm_2026-08-28T15-24.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0046:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

`vu1Thread.Close()` não é chamado em lugar nenhum da árvore — só `Open()`, a partir de `mVUreset`
(`arm64/microVU-arm64.cpp:1934`, `x86/microVU.cpp:318`). Como o laço de
`VU_Thread::ExecuteRingBuffer()` só sai por `m_shutdown_flag`, e esse sinal só é levantado dentro
de `Close()`, e `vu1Thread` é objeto global (`pcsx2/MTVU.cpp:13`), a thread só morre no destrutor
`~VU_Thread()`, no fim do processo.

`VMManager::Internal::CPUThreadShutdown()` já encerra a thread irmã:

```cpp
	MTGS::ShutdownThread();
	GSJoinSnapshotThreads();

	ShutdownCPUProviders();

	SysMemory::Release();
```

A MTVU é a única thread do núcleo sem o par correspondente. Medição no A12, sintoma e evidência
estão no registro do bug: 99,95% de um núcleo, indefinidamente, com a VM já encerrada.

**Este caminho é alcançado no Android.** A última linha do `emulog.txt` depois de parar um jogo é
`Releasing host memory for virtual systems...`, o `Console.WriteLn` de `SysMemory::Release()`
(`pcsx2/Memory.cpp:359`), chamado logo depois de `ShutdownCPUProviders()`. Não é inferência pelo
nome: a linha está no log do aparelho.

## Objetivo

Encerrar a thread da MTVU junto com os outros provedores de CPU, para que ela não sobreviva à VM.

## Onde a chamada entra, e por que ali

Em `VMManager::ShutdownCPUProviders()`, **depois do guarda de inicialização e antes dos releases**:

```cpp
void VMManager::ShutdownCPUProviders()
{
	if (!s_cpu_providers_initialized)
		return;
	// <-- aqui
	if (newVifDynaRec)
	{
		dVifRelease(1);
		dVifRelease(0);
	}

	CpuMicroVU1.Shutdown();
	...
```

Depois do guarda porque o `s_cpu_providers_initialized == false` só acontece em dois casos —
provedores nunca inicializados, ou já derrubados — e nos dois a MTVU não está aberta. A ordem em
relação ao que vem abaixo não é estética: a thread executa código que essas linhas destroem.

| linha existente | o que a MTVU usa dela |
|---|---|
| `dVifRelease(1)` | `MTVU_Unpack()` chama `dVifUnpack<1>()` (`MTVU.cpp`) |
| `CpuMicroVU1.Shutdown()` | o tag `MTVU_VU_EXECUTE` chama `CpuVU1->Execute()` |

Soltar qualquer um dos dois com a thread viva é use-after-free.

**`Close()` sozinho, sem `WaitVU()` antes.** `WaitVU()` → `semaEvent.WaitForEmpty()` é espera
bloqueante, e nesse ponto o MTGS já foi derrubado por `MTGS::ShutdownThread()` — superfície de
deadlock em troca de nada, porque a VM está sendo destruída e não há motivo para drenar o ring
buffer. `Close()` já é suficiente e tem guarda própria:

```cpp
void VU_Thread::Close()
{
	if (!IsOpen())          // no-op se a MTVU nunca foi aberta
		return;
	m_shutdown_flag.store(true, std::memory_order_release);
	semaEvent.NotifyOfWork();
	m_thread.Join();
}
```

Verificado nesta árvore antes de escrever: `pcsx2/VMManager.cpp:26` já tem `#include "MTVU.h"`;
`pcsx2/MTVU.h:123` declara `extern VU_Thread vu1Thread;` (objeto, não referência);
`Open()`/`Close()`/`IsOpen()` são públicos (`public:` na linha 33, `private:` só na 98).

## Escopo

**Entra:**

- `pcsx2/VMManager.cpp` — uma chamada a `vu1Thread.Close()` em `ShutdownCPUProviders()`, com o
  comentário que explica a ordem.

**NÃO entra:**

- **Descobrir por que o spin não termina.** `SPIN_TIME_NS` é 50 µs e `ShortSpinOn()` cobra no
  mínimo 1 tick para a contagem não travar, então `WaitForWorkWithSpin()` deveria dormir. Encerrar
  a thread remove o sintoma qualquer que seja o gatilho; a investigação do `WorkSema` é outra task.
- **O portão de MTVU por núcleos grandes.** `big_cores >= 3`, com "grande" medido por frequência
  **relativa**: num octa-A55 homogêneo como o Exynos 850 os 8 núcleos reportam 2.002 MHz e todos
  contam como grandes, então a MTVU liga num aparelho sem nenhum núcleo grande. Defeito separado —
  ver [`main-mtvu-forcado-sem-checar-nucleos-grandes`](../bugs/done/main-mtvu-forcado-sem-checar-nucleos-grandes_2026-08-10T16-02.md),
  que tratou de outro aspecto do mesmo portão.
- **A perda de desempenho do 007 no A12.** ~29 fps de 59,94 num Exynos 850 é piso de hardware;
  esta task devolve um núcleo de oito, não conserta a emulação.

## Como validar

Construir e instalar, abrir um jogo com `mtvu=1`, jogar, **parar** o jogo pela UI e, com o app
ainda no menu:

```bash
PID=$(adb shell pidof come.nanodata.armsx2)
adb shell "for t in /proc/$PID/task/*; do cat \$t/comm; done" \
  | grep -E '^(MTVU|GS|CPU Thread)$'
```

Critério: **nenhuma linha `MTVU`**, do mesmo jeito que `CPU Thread` já não aparece. Antes da
correção a linha existe, em `estado=R` e consumindo ~100% de um núcleo.

Depois, abrir um segundo jogo na mesma sessão do app e conferir que ele roda — é o que prova que
o `Open()` em `mVUreset` recria a thread e que o encerramento não deixou o ring buffer num estado
que trave o boot seguinte. Esse é o risco real da mudança, e é o teste que o pega.

## Procedência

Portada da `feature/handoff-end-to-end`, onde a mesma correção foi escrita, compilada e validada
no A12 em 2026-08-28 (TASK-0020 **daquele ramo** — número que aqui significa outra coisa, ver
[colisão de números](../bugs/open/numeros-de-task-colidem-entre-ramos_2026-08-28T10-40.md)). Lá o
laço usa `WaitForWork()` bloqueante e a thread vazada dorme; aqui usa `WaitForWorkWithSpin()` e
gira a 100%. O `ShutdownCPUProviders()` também difere — esta árvore tem o guarda
`s_cpu_providers_initialized` e o `dVifRelease` dentro de `if (newVifDynaRec)` —, então a chamada
foi reposicionada em vez de copiada.
