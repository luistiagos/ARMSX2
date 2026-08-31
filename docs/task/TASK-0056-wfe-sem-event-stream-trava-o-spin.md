# TASK-0056: não estacionar num `WFE` que ninguém promete acordar

- **Status:** revertida
- **Criada em:** 2026-08-30
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm](../bugs/done/mtvu-thread-gira-a-100-por-cento-apos-fim-da-vm_2026-08-28T15-24.md)
- **Backlog:** item 1 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md) — e o achado de lado registrado em [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0056:` no assunto)
- **Revertida por:** `d71a0631d9`
- **Publicado em:** —


> ## 🔴 REVERTIDA em 2026-08-30 — o diagnóstico abaixo está errado
>
> O primeiro passo do "Como validar" desta própria task era:
>
> ```bash
> adb shell "grep -o 'evtstrm' /proc/cpuinfo | head -1"
> ```
>
> Rodado no Galaxy A12, ele responde **`evtstrm`**. O aparelho **tem** o event stream, `HWCAP_EVTSTRM`
> está setado, `HasEventStream()` devolve `true`, e a guarda que esta task escreveu é um **no-op
> exatamente no aparelho onde o defeito foi medido**.
>
> A causa real está na [TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md): `CNTFRQ_EL0`
> lê **0** neste SoC, então `ShortSpinOn()` devolve `(elapsed * 1e9) / 0 == 0` — `udiv` por zero no
> AArch64 não tem exceção, devolve zero — e o orçamento `waited` de `WaitForWorkWithSpin()` nunca
> alcança `SPIN_TIME_NS`. Não era o `WFE` não acordar; era o relógio não contar.
>
> **O que esta task acertou:** que a thread estava presa *dentro* de uma chamada a
> `WaitForWorkWithSpin()`, e que o teto de 50 µs só é conferido *entre* chamadas. Esse raciocínio
> está certo e é o que levou à sonda que achou a resposta. O que estava errado foi o motivo de a
> chamada não retornar.
>
> **Por que a guarda não ficou "por segurança":** é código no core do upstream, para um aparelho
> hipotético, que não pode ser testado, escrito a partir de uma inferência que o único aparelho
> disponível derrubou. O risco que ela descrevia ficou anotado no comentário de `MonitoredWait`.
>
> O texto original segue abaixo, inteiro.

## Contexto

Esta task responde a pergunta que a
[TASK-0046](TASK-0046-encerrar-thread-mtvu-no-shutdown.md) deixou explicitamente em aberto.

A [TASK-0046](TASK-0046-encerrar-thread-mtvu-no-shutdown.md) fechou o sintoma no shutdown e
registrou a causa como **não determinada**:

> `SPIN_TIME_NS` é 50 µs e `ShortSpinOn()` cobra no mínimo 1 tick justamente para a contagem nunca
> travar, então `WaitForWorkWithSpin()` deveria cair em `m_sema.Wait()` e dormir.

Está determinada agora, e o raciocínio acima tem um furo: o teto de 50 µs só é conferido **entre**
chamadas a `ShortSpinOn()`. Se uma chamada não retorna, `waited` não avança e o `m_sema.Wait()`
nunca é alcançado.

`ShortSpinOn()` no ARM64 (`common/HostSys.cpp:147`) é exatamente isso:

```cpp
static void MonitoredWait(const std::atomic<s32>& word, s32 expected)
{
	__asm__ __volatile__(
		"sevl\n"
		"wfe\n"
		"ldaxr %w0, [%1]\n"
		"cmp   %w0, %w2\n"
		"b.ne  1f\n"
		"wfe\n"          // <-- sem despertador, isto não volta
		"1:\n"
		...
```

O segundo `WFE` só acorda por duas coisas: uma escrita em `word` que limpe o monitor exclusivo, ou
o **event stream** do timer arquitetural. O comentário da própria função conta com o segundo:

> A monitor that never fires is a latency cost, not a hang: WFE also wakes on the periodic event
> stream, every ~33µs **on this host**.

*On this host.* O event stream é opcional — é o `CONFIG_ARM_ARCH_TIMER_EVTSTREAM` do kernel, e o
Linux publica se está ligado em `AT_HWCAP`, bit `HWCAP_EVTSTRM`. Onde ele não está ligado, e
ninguém vai escrever em `word` (VM pausada, ring buffer vazio, telefone bloqueado), o `WFE` fica
parado **para sempre**.

E `WFE` não é `yield`: a thread continua *runnable*. O escalonador a contabiliza rodando e o
`/proc` acumula tempo de CPU. É por isso que a medição do A12 encontra a `MTVU` em estado **`R`**
consumindo 724 ticks em ~7 s — ~100% de um núcleo — com a VM pausada. Se ela tivesse chegado ao
`m_sema.Wait()`, estaria em `S`.

Isso fecha o caso: **estado `R` + tempo de CPU integral + nada para acordar = parada dentro do
`WFE`**, não giro em falso do laço externo.

## Objetivo

Nunca usar o `WFE` como espera quando o aparelho não garante despertador periódico — sem tirar o
ganho dele onde garante.

## Escopo

**Entra:**

- `common/HostSys.cpp` — `ShortSpinOn()` consulta uma vez (`getauxval(AT_HWCAP) & HWCAP_EVTSTRM`)
  se o event stream existe. Existindo, nada muda. Não existindo, cai no `ShortSpin()` de `isb`,
  que é limitado por construção (conta `PAUSE_TIME` até 500 ns e volta), então `waited` avança,
  `SPIN_TIME_NS` é alcançado e a thread dorme no semáforo.

**NÃO entra:**

- **Trocar a `MTVU` para `WaitForWork()`** (opção (a) do backlog). Trataria uma chamadora de um
  defeito que é de todas: `WaitForWorkWithSpin`, `WaitForEmptyWithSpin` e
  `UserspaceSemaphore::WaitWithSpin` usam o mesmo `ShortSpinOn`, e o MTGS passa pelas mesmas
  linhas. Consertar o `WFE` conserta as quatro; trocar a MTVU deixa três armadas.
- **Encurtar `SPIN_TIME_NS` no ARM64** (opção (c) do backlog). Não resolveria nada: o teto não é
  o problema, é o fato de ele nunca ser conferido. Com 5 µs em vez de 50 µs a thread continua
  parada no mesmo `WFE`.
- **Medir se o giro custa fps num jogo VU-pesado.** Onde o event stream existe o comportamento
  fica idêntico ao de hoje, então não há o que medir; onde não existe, o que havia era uma thread
  presa, não um giro produtivo.
- **A pergunta de se o A12 tem ou não `HWCAP_EVTSTRM`.** A correção é a mesma nos dois casos, e a
  linha de log desta task responde na próxima execução.

## O que a correção custa, onde ela age

No aparelho sem event stream, uma espera que hoje é `WFE` (baixo consumo, e infinita) passa a ser
o giro de `isb` por até `SPIN_TIME_NS` = 50 µs, e só então o semáforo. Isso é CPU real queimada,
não simulada — mas é **limitada**, e é exatamente o que o caminho x86 sempre pagou.

Quanto pesa depende de com que frequência a espera acontece **sem trabalho chegando**. Durante o
jogo a MTVU quase sempre encontra o estado em `RUNNING_N` e nem entra no laço de giro; o giro é o
caso ocioso, que hoje custa um núcleo inteiro para sempre e passará a custar 50 µs uma vez.

Onde o event stream existe, nada muda: mesma instrução, mesmo caminho.

## Como validar

1. **A pergunta direta**, no aparelho, sem precisar do app:

   ```bash
   adb shell "grep -o 'evtstrm' /proc/cpuinfo | head -1"
   ```

   Presente = event stream ligado (o comportamento não muda). Ausente = era esta a causa.

2. **O sintoma**, que é o critério que fecha: abrir um jogo com `mtvu=1`, **pausar** a VM, bloquear
   a tela e amostrar:

   ```bash
   PID=$(adb shell pidof come.nanodata.armsx2)
   adb shell "for t in /proc/$PID/task/*; do \
     echo \"\$(cat \$t/comm) \$(awk '{print \$3, \$14+\$15}' \$t/stat)\"; done" | grep MTVU
   ```

   Duas amostras com ~10 s entre elas. Critério: o estado é `S` e o contador **não cresce**. Antes
   da correção o estado é `R` e ele cresce ~100 ticks/s.

3. O `emulog.txt` traz uma vez `HostSys: ARM64 event stream absent, WFE spin disabled` no aparelho
   em que o caminho mudou — e nada no aparelho em que não mudou.

## Nota de procedência

`ShortSpinOn`/`MonitoredWait` **são do upstream**, não nossos: estão em `662b114168`, a base do
fork. Pela regra do `CLAUDE.md` esta correção nasce como contribuição — o patch é uma guarda de
seis linhas num arquivo só, escrito de propósito para caber num PR deles sem arrastar nada nosso.
