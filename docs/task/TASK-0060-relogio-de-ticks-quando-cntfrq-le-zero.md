# TASK-0060: relógio de ticks que sobrevive a um `CNTFRQ_EL0` não programado

- **Status:** concluída
- **Criada em:** 2026-08-30
- **Concluída em:** 2026-08-31 (validada no A12: registrador, contadores e MTVU)
- **Feature:** nenhuma
- **Bugs que resolve:** [cntfrq-el0-lido-como-zero-zera-todo-relogio-de-ticks](../bugs/open/cntfrq-el0-lido-como-zero-zera-todo-relogio-de-ticks_2026-08-30T21-30.md)
- **Backlog:** itens 0 e 1 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md) — **os dois, pela mesma causa**
- **Commit:** — (o vínculo é o prefixo `TASK-0060:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

`CNTFRQ_EL0` lê **0** no Exynos 850. Programá-lo é trabalho do firmware, e nada na arquitetura
obriga o firmware a fazê-lo; o Linux não se importa porque tira a frequência do device tree e o
vDSO dele nunca divide pelo registrador de EL0. O nosso divide, em todo lugar, e no AArch64
`UDIV`/`SDIV` por zero **não levanta exceção — devolve 0**. Em `double`, a mesma divisão dá `+inf`
e a recíproca vira `0.0`.

O registro completo, com a medição, está no
[bug](../bugs/open/cntfrq-el0-lido-como-zero-zera-todo-relogio-de-ticks_2026-08-30T21-30.md). O
resumo é que **um registrador explica três defeitos**: os contadores de desempenho zerados, a
thread MTVU girando a 90% de um núcleo com a VM pausada, e o limitador de quadros que não limita.
Os dois primeiros estão **medidos** antes e depois; o terceiro é **derivado** do código — a tabela
"O que é medido e o que é derivado" no registro do bug separa um do outro.

## Objetivo

Que o par `GetCPUTicks()` / `GetTickFrequency()` continue coerente consigo mesmo num aparelho cujo
firmware não programou o contador — sem custar nada onde ele foi programado.

## A correção

`common/Linux/LnxMisc.cpp`. Uma leitura de `CNTFRQ_EL0`, resolvida uma vez e memorizada:

- **≠ 0** (todo desktop, e todo telefone cujo firmware faz o seu trabalho): nada muda. Mesmo `mrs`,
  mesmo caminho rápido, mesmo valor.
- **= 0**: `GetCPUTicks()` passa a devolver `CLOCK_MONOTONIC` em nanossegundos e
  `GetTickFrequency()` devolve `1e9` — exatamente o par que o build **não**-ARM64 já usa. Os dois
  mudam juntos, que é a única coisa que importa: quem consome o contador só sabe interpretá-lo
  através da frequência.

Resolvido no **primeiro uso**, não na inicialização estática, porque o diagnóstico precisa chegar
ao `emulog.txt` e no static-init o arquivo de log ainda não está aberto — o mesmo motivo, e a mesma
forma, que o `PAUSE_TIME` de `HostSys.cpp` já usa.

## Escopo

**Entra:**

- `common/Linux/LnxMisc.cpp` — a detecção, a queda para `CLOCK_MONOTONIC`, e a linha de log que
  transforma "eu acho" em "o aparelho disse".

**NÃO entra:**

- **Corrigir cada divisão.** São cinco encontradas (`ShortSpinOn`, `PerformanceMetrics::Update`,
  `VMManager::UpdateTargetSpeed`, `Threading::SleepUntil`, `GSDevice::ThrottlePresentation`) — e as
  duas últimas só apareceram numa revisão posterior, que é exatamente o argumento: blindar uma a uma
  deixaria a sexta. O contrato é "ticks e frequência combinam"; consertar o contrato conserta todas.
  Vale também porque dividir por zero é **UB em C++**: hoje o compilador emite a instrução crua
  porque não consegue provar nada sobre o divisor, mas isso não é uma garantia entre builds.
- **`GPU 0%` no `PerfLog`.** Sobreviveu a esta correção, e eu cheguei a registrar isso como "outro
  defeito: o backend não produz tempo de GPU". **Não se sustenta:** num build posterior o mesmo
  jogo marca `GPU 15%` sem que nada meu tenha mudado nesse caminho — mas esse build carrega
  edições não commitadas de outra sessão em `GSUtil.cpp` / `GSGPUDriverProfile.cpp`. Pergunta em
  aberto, a remedir em árvore limpa.
- **A thread MTVU ligar sem núcleo grande.** Portão separado, já registrado.

## Como validar

Tudo abaixo foi executado no Galaxy A12 (`SM-A127M`) em 2026-08-30, com o mesmo jogo
(`10 Pin - Champions Alley`, PAL) e o GOS morto.

**1. O registrador, dito pelo próprio app** — é o que prova a causa em vez de inferi-la:

```
HostSys: CNTFRQ_EL0 reads 0 (firmware did not program it); using CLOCK_MONOTONIC for GetCPUTicks()
```

**2. Os contadores de desempenho** (item 0 do backlog):

```
antes:   PerfLog: 25.6 fps | EE 0%   GS 0%  VU 0% GPU 0% | frame 771
depois:  PerfLog: 25.0 fps | EE 100% GS 37% VU 0% GPU 0% | frame 758
```

**3. A thread MTVU com a VM pausada e a tela bloqueada** (item 1):

| | antes | depois |
|---|---|---|
| `State` | `R (running)` | `S (sleeping)` |
| CPU | 89–91% de um núcleo | 0% |
| `voluntary_ctxt_switches` | `0`, na vida inteira da thread | 4 |

O `voluntary_ctxt_switches == 0` é o critério que fecha: significa que a thread nunca bloqueou uma
única vez. Depois da correção ela bloqueia.

## O que a medição corrigiu no diagnóstico anterior

Duas hipóteses foram ao aparelho e voltaram erradas. Ficam registradas porque o erro é instrutivo:

- **[TASK-0056](TASK-0056-wfe-sem-event-stream-trava-o-spin.md)** atribuiu o giro da MTVU à
  ausência do event stream do timer (`HWCAP_EVTSTRM`), que faria o `WFE` de `MonitoredWait` nunca
  retornar. O A12 **tem** `evtstrm` — está no `Features` do `/proc/cpuinfo`. A guarda que aquela
  task escreveu é um no-op neste aparelho e foi revertida.
- **[TASK-0055](TASK-0055-contadores-de-desempenho-que-nao-mentem.md)** atribuiu os `0%` à leitura
  do relógio por thread falhando em silêncio. O aviso que ela adicionou para esse caso **não
  disparou** — e foi justamente esse silêncio que apontou para o divisor. A task não estava certa,
  mas o instrumento que ela deixou é o que encontrou a resposta.

A lição, para o próximo: as duas hipóteses eram consistentes com todo o código lido e com toda a
medição anterior. O que as derrubou foi **executar no aparelho** — a primeira em trinta segundos
(`grep evtstrm /proc/cpuinfo`), a segunda no primeiro `PerfLog` depois de instalar.
