# Bug: ANR/trava completa — `saveStateToSlot` nunca converge com um jogo real rodando

- **Detectado em:** 2026-08-05 18:16–18:39 (teste manual em device, não telemetria)
- **Origem:** investigação de device ao validar o fix de
  [`mainactivity-anr-jni-emulador-ui-thread`](./mainactivity-anr-jni-emulador-ui-thread_2026-08-01T17-05.md);
  a única ocorrência de telemetria conhecida desse sintoma é o error **731** (`saveStateToSlot`,
  1 ocorrência), já listado naquele bug
- **Classe:** fail (ANR — `Input dispatching timed out ... Waited 10000ms`) e possivelmente pior:
  telas idênticas byte-a-byte sugerem trava além da UI thread
- **Reincidência:** reproduzido **3 vezes** na mesma sessão (2x com uma tentativa de fix em
  `Host::RunOnCPUThread(block=true)`, 1x com o código original revertido) — determinístico com o
  disco/cenário usado, não uma race rara

## Sintoma

Com um jogo PS2 real rodando (CRC de disco válido, `VMManager::GetDiscCRC() != 0`), tocar
**Estado do Jogo → Salvar estado (slot 1)** trava o app por completo:

- O toast "Saving state to slot 1..." fica preso na tela indefinidamente (observado por >20 min
  numa das tentativas antes de eu desistir e forçar o fechamento).
- Duas capturas de tela consecutivas (`adb shell screencap`, ~3s de intervalo) saem
  **byte-a-byte idênticas** (hash MD5 igual) — nem a GS thread está avançando, não é só a UI
  thread esperando.
- Em ~10s o Android levanta "RetroSystem PS2 não está respondendo" (`Input dispatching timed out`).
  Tocar "Aguardar" não resolve — o diálogo reaparece porque o app continua travado.
- Nenhum arquivo aparece em `files/sstates/` (o save nunca completa).
- **Nenhum SIGABRT/crash** — o processo continua vivo (mesmo PID) e sensível a `pidof`, só não
  responde a input nem redesenha.

## Como reproduzir

1. No app, aba **Catálogo**, baixar qualquer jogo PS2 pequeno (ex.: busque "Crash", baixe
   "Crash Bandicoot - The Wrath of Cortex (USA) (v1.00)", ~324 MB — rápido).
2. Abrir o jogo pela aba **Salvos**, deixar carregar até ter controle real (menu do jogo ou
   gameplay — não precisa ser gameplay ativo, o menu inicial já basta).
3. Abrir o drawer in-game (swipe da borda esquerda), rolar até **Estado do Jogo**.
4. Tocar **Salvar estado (slot 1)**.
5. Observar: toast de "Saving..." nunca some, tela para de atualizar, ANR em ~10s.

Reproduzido tanto **depois** de mexer no renderizador (12 trocas rápidas AT/GL/SW/VK) quanto
**sem** mexer em nada antes — não depende de interação prévia com o renderizador.

## Causa raiz (NÃO isolada — hipótese)

O código de `saveStateToSlot` (main.cpp) roda num `std::async` separado (nem UI thread, nem CPU
thread):

```cpp
if (VMManager::GetDiscCRC() != 0) {
    if (VMManager::GetState() != VMState::Paused)
        VMManager::SetPaused(true);
    for (int i = 0; i < 5; ++i) {
        if (s_execute_exit) {
            VMManager::SaveStateToSlot(p_slot, false);   // só chega aqui se s_execute_exit virar true
            break;
        }
        sleep(1);                                          // até 5 tentativas de 1s
    }
}
```

`s_execute_exit` só vira `true` quando `VMManager::Execute()` (chamado em loop por `runVMThread`,
ver [main.cpp](../../../app/src/main/cpp/main.cpp)) **retorna** — e o comentário no código-fonte diz
que `Execute()` "roda até pedirem para parar" (`Cpu->Execute()`), ou seja: só retorna quando o
estado deixa de ser `Running` (tipicamente porque `SetPaused(true)` fez efeito).

**Hipótese:** `VMManager::SetPaused(true)`, chamado de uma thread que não é a CPU thread, pode não
fazer `Cpu->Execute()` perceber a mudança de estado de forma confiável enquanto há gameplay real em
andamento — diferente do cenário testado antes (VM parada na tela de idioma da BIOS, onde o "save"
nem chegava a rodar por causa do CRC zero, então esse caminho nunca tinha sido exercitado com um
jogo de verdade). Se `Execute()` nunca retorna, o laço de 5 tentativas deveria no mínimo desistir em
~5s e devolver `false` — mas a trava observada passa de 10s sem sinal de recuperação, e a tela para
de atualizar por completo, o que não bate com "só a UI thread esperando um future". Não foi possível
instrumentar mais fundo: o logging nativo (`Console.WriteLn`/`INFO_LOG`) fica desabilitado no
perfil de performance usado em produção, e não há tempo de sessão sobrando para religar isso e
re-testar.

**Confirmado que NÃO é regressão do fix de `mainactivity-anr-jni-emulador-ui-thread`:** tentei
corrigir esse mesmo sintoma envolvendo a chamada real de `SaveStateToSlot` em
`Host::RunOnCPUThread(..., block=true)` (mesma técnica do upstream, ver aquele bug). Reproduziu a
trava **do mesmo jeito**, 2x. Revertido para a chamada direta original, rebuild, reinstall, testei
de novo do zero — **a trava reproduziu igual**, com o código que já existia antes de qualquer
mudança de hoje. Ou seja, isso é um bug pré-existente na base, só nunca tinha sido pego com CRC de
disco válido numa triagem anterior.

## Resolução (CONFIRMADA e corrigida — 2026-08-18)

A auditoria do repositório upstream `ARMSX2/ARMSX2` (commits `9f6288531d` e `8e4aa15918`) confirmou o diagnóstico exato:

1. **Ausência de `Cpu->ExitExecution()` no Pause:**
   `VMManager::SetPaused(true)` apenas alterava a variável de estado atômica, mas não interrompia o laço interno de execução do JIT na EE. Sem sinalização explícita via `Cpu->ExitExecution()`, o `Cpu->Execute()` continuava executando blocos em loop contínuo e nunca retornava para setar `s_execute_exit = true`.
2. **Thread-Safety em `recSafeExitExecution()` (`iR5900.cpp`):**
   A chamada anterior de `recSafeExitExecution()` continha mutações inseguras (`cpuRegs.nextEventCycle = 0` e `psxRegs.iopBreak += psxRegs.iopCycleEE`) que causavam data race com o contador de ciclos relativo (`RECCYCLE`) da thread de emulação. Esse data race retrocedia o relógio da EE e corrompia os contadores de hardware. Corrigido com `eeRecExitRequested.store(true, std::memory_order_release)` atômico.
3. **Padrão `ScopedVMPause` + `Host::RunOnCPUThread` (`main.cpp`):**
   Implementada a classe RAII `ScopedVMPause`, que aciona `VMManager::SetPaused(true)` seguido de `Cpu->ExitExecution()` e aguarda o estacionamento da thread da CPU. Com a CPU estacionada, `VMManager::SaveStateToSlot` e `VMManager::LoadStateFromSlot` são despachados para a CPU thread via `Host::RunOnCPUThread(..., block=true)`, respeitando os anéis de comando MTGS e MTVU.

Status: **Corrigido no código local (2026-08-18).**

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
