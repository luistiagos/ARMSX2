# Bug: MTVU forçado ON no Android sem checar quantos núcleos *grandes* existem

> ## ℹ️ REVISÃO 2026-08-10 — o upstream implementou um gate, mais fraco que o nosso proposto
>
> A auditoria do `ARMSX2/ARMSX2` master mostrou que o diagnóstico **se sustenta** e o upstream
> chegou à mesma conclusão, com uma heurística diferente. De
> `platforms/android/app/src/main/java/com/armsx2/DeviceTier.kt`:
>
> ```kotlin
> /** MTVU (multi-threaded VU1) is a win only when there are spare cores to run
>  *  VU1 on its own thread. On 4-core / big.LITTLE budget SoCs it can cost more
>  *  than it saves (EE<->VU1 sync + thread hop), so we gate the *default* on a
>  *  6-core minimum. This does NOT change the persisted Settings default (which
>  *  would bleed into existing users' saved configs) — it's applied only in
>  *  first-run wizard defaults and the Low-End preset. */
> fun mtvuDefault(): Boolean = coreCount() >= 6
> ```
>
> Dois pontos importantes:
>
> 1. **O gate deles não resolveria o nosso caso.** `coreCount()` é
>    `Runtime.availableProcessors()` — total de núcleos, não núcleos grandes. Num Redmi 2big+6little
>    dá 8, passa no gate, e o MTVU continua ligado. A heurística de núcleos **grandes** proposta
>    neste bug é mais precisa que a do upstream para o hardware que nos interessa.
> 2. **Eles deliberadamente NÃO mexeram no default persistido**, para não vazar a mudança na
>    config salva de usuários existentes — o gate só age no wizard de primeiro uso e no preset
>    "Low-End". É uma cautela de migração que o nosso plano original (bumpar
>    `ANDROID_PERFORMANCE_PROFILE_VERSION` e remigrar todo mundo) ignorava. Ver passo 6.
>
> O upstream também expõe MTVU como toggle direto (`perf.hack.mtvu`) e tem presets
> Optimal/Fast/Low-End, onde só o Low-End aplica o gate.

- **Detectado em:** 2026-08-10 16:02 (auditoria de código, motivada por relatos de lentidão em Redmi)
- **Origem:** auditoria de `ApplyAndroidPerformanceDefaults` / `VMManager::SetHardwareDependentDefaultSettings`
- **Errors (serviço):** nenhum — não é crash, não gera telemetria.
- **Classe:** fail (performance)
- **Reincidência:** sistêmico — afeta todo aparelho com <3 núcleos grandes

## Sintoma

Em aparelhos com 2 núcleos grandes (grande parte da linha Redmi/Poco), ligar MTVU **piora** o
desempenho em vez de melhorar. O sintoma é stutter rítmico em jogos VU-pesados, pior do que rodaria
com MTVU desligado.

## Contexto: o que MTVU faz

`MTVU` = **M**ulti-**T**hreaded **VU1**. Sem ele, quando o jogo manda um programa para a VU1 (a
unidade vetorial que transforma geometria e alimenta o GS), a thread da EE executa esse programa
ela mesma e só continua depois. Com ele, a EE empurra o programa para uma fila e segue em frente
enquanto uma segunda thread executa a VU1 em paralelo.

O chaveamento é ([Config.h:1415](../../../app/src/main/cpp/pcsx2/Config.h#L1415)):

```cpp
#define THREAD_VU1 (EmuConfig.Cpu.Recompiler.EnableVU1 && EmuConfig.Speedhacks.vuThread)
```

E o ponto de entrada em [VU1micro.cpp:54](../../../app/src/main/cpp/pcsx2/VU1micro.cpp#L54) — repare no
`return` que pula todo o caminho síncrono:

```cpp
void vu1ExecMicro(u32 addr) {
    if (THREAD_VU1) {
        VU0.VI[REG_VPU_STAT].UL &= ~0xFF00;
        vu1Thread.ExecuteVU(addr, vif1Regs.top, vif1Regs.itop, VU0.VI[REG_FBRST].UL);
        return;                    // EE segue em frente
    }
    // ... daqui pra baixo: EE executa a VU1 ela mesma e espera
}
```

**O ganho é real quando existe um núcleo sobrando** — a EE recupera o tempo que gastaria rodando
VU1, o que é significativo em jogos com geometria pesada.

**O custo é a sincronização.** Existe um ring buffer entre as duas threads, semáforos
(`semaEvent`, `semaXGkick`) e pontos onde a EE precisa parar e esperar a VU1 drenar —
`WaitVU()` em [MTVU.cpp:435](../../../app/src/main/cpp/pcsx2/MTVU.cpp#L435). Enquanto as duas threads
estão em núcleos de velocidade parecida esse custo se paga. **Quando não estão, ele vira o
gargalo** — que é exatamente o caso deste bug.

## Causa raiz (CONFIRMADA no código)

`ApplyAndroidPerformanceDefaults()` liga MTVU incondicionalmente:

```cpp
settings.SetBoolValue("EmuCore/Speedhacks", "vuThread", true);   // main.cpp:152
```
([main.cpp:152](../../../app/src/main/cpp/main.cpp#L152))

E a migração reforça isso para quem já tinha o valor antigo
([main.cpp:238](../../../app/src/main/cpp/main.cpp#L238)), assim como o preset de performance da UI
([SettingsActivity.java:336](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java#L336)).

O upstream tem uma heurística para isso, mas a versão que o Android compila está errada. Repare nas
duas implementações de `SetHardwareDependentDefaultSettings`:

**Path `__linux__` / `_WIN32`** — é o que o Android usa
([VMManager.cpp:3513](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3513)):
```cpp
const u32 core_count = cpuinfo_get_cores_count();
if (core_count >= 3)
    si.SetBoolValue("EmuCore/Speedhacks", "vuThread", true);
```

**Path `__APPLE__`** — a heurística correta
([VMManager.cpp:3555](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3555)):
```cpp
if (s_big_cores >= 3)
    si.SetBoolValue("EmuCore/Speedhacks", "vuThread", true);
```

No Android `cpuinfo_get_cores_count()` retorna 8 praticamente sempre — inclusive num aparelho com
2 big + 6 little. A checagem passa trivialmente. A heurística de núcleos **grandes**, que é a que
importa, nunca foi portada para o Android; ela só existe no path do macOS.

Com MTVU ligado são 3 threads pesadas (EE + VU1 + GS) disputando 2 núcleos grandes. Uma delas
sempre cai no cluster pequeno e as outras duas ficam bloqueadas esperando — o custo de
sincronização do MTVU passa a superar o ganho de paralelismo.

Agrava em God of War 2, que via GameDB força `mvuFlag: 0` (VU mais lenta) — ver
[`gamedb-gow2-autoflush-mvuflag-custo-proibitivo-mobile`](./gamedb-gow2-autoflush-mvuflag-custo-proibitivo-mobile_2026-08-10T16-02.md).

## Acoplamento com thread pinning

O mapeamento de afinidade **depende de MTVU**
([VMManager.cpp:3616-3638](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3616-L3638)):

```
MTVU ligado    -> EE=core0, VU=core1, GS=core2    (precisa de 3 núcleos rápidos)
MTVU desligado -> EE=core0,           GS=core1    (precisa de 2)
```

Num aparelho com 2 núcleos grandes, ligar pinning **com** MTVU fixa a VU1 no `core2` — que já é um
A55. A VU1 fica lenta num núcleo pequeno e a EE bloqueia em `WaitVU()` esperando por ela: pior que
sem pinning. Com MTVU desligado, EE e GS ficam cada uma num núcleo grande e a VU1 roda dentro da
EE — encaixe perfeito.

Por isso este bug e
[`vmmanager-thread-pinning-nunca-ativado-android`](./vmmanager-thread-pinning-nunca-ativado-android_2026-08-10T16-02.md)
**não podem ser implementados separadamente**.

## Falso positivo investigado: `vu1Instant` + MTVU **não** é bug

Registrado para ninguém repetir a investigação. Nossos defaults ligam os dois
([main.cpp:152-153](../../../app/src/main/cpp/main.cpp#L152-L153)), e a declaração de `vu1Instant`
sugere que isso está errado ([Config.h:1064](../../../app/src/main/cpp/pcsx2/Config.h#L1064)):

```cpp
vu1Instant : 1; // Enable Instant VU1 (Without MTVU only)
```

Reforçando a suspeita, `vu1Finish()` chama `WaitVU()` quando `INSTANT_VU1` está on
([VU1micro.cpp:27](../../../app/src/main/cpp/pcsx2/VU1micro.cpp#L27)) — o que pareceria bloquear a EE
a cada finish e anular o MTVU:

```cpp
if (THREAD_VU1) {
    if (INSTANT_VU1 || add_cycles)
        vu1Thread.WaitVU();      // EE bloqueia
```

**Não anula.** Rastreando os call sites: com Instant VU1 ligado, `ExecuteVU` deliberadamente *não*
seta o bit de "VU1 ocupada" ([MTVU.cpp:459-463](../../../app/src/main/cpp/pcsx2/MTVU.cpp#L459-L463)):

```cpp
if (!INSTANT_VU1) {
    VU0.VI[REG_VPU_STAT].UL |= 0x100;
    CPU_INT(VU_MTVU_BUSY, cycles);
}
```

E os `vu1Finish` do caminho quente estão todos atrás de um guard `if (VPU_STAT & 0x100)`
([Vif1_Dma.cpp:245-249](../../../app/src/main/cpp/pcsx2/Vif1_Dma.cpp#L245-L249)). Sem o bit setado,
eles não disparam. O `vu1Finish` de [VU1micro.cpp:66](../../../app/src/main/cpp/pcsx2/VU1micro.cpp#L66)
é inalcançável com MTVU on (o `return` acima pula ele).

A combinação é **rápida** — troca precisão por velocidade, que é o que queremos no Android.
**Não mexer em `vu1Instant`.**

## Como reproduzir

1. Aparelho com 2 núcleos grandes (ex.: Redmi com Snapdragon 6xx / Helio G99).
2. Rodar um jogo VU-pesado com MTVU ligado (default), anotar FPS médio.
3. Desligar MTVU em Configurações
   ([SettingsActivity.java:1620](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java#L1620))
   e repetir. Esperado: FPS igual ou melhor com MTVU **desligado**.

## Próximos passos

1. Implementar contagem de núcleos grandes no Android a partir do `cpuinfo` — agrupar por
   `cluster` e contar os clusters de maior frequência, análogo ao que o path Apple faz com
   `DarwinMisc::GetCPUClasses()`.
2. Trocar o `vuThread = true` incondicional de [main.cpp:152](../../../app/src/main/cpp/main.cpp#L152)
   por essa detecção. Bumpar `ANDROID_PERFORMANCE_PROFILE_VERSION` (hoje 6) para que instalações
   existentes sejam remigradas.
3. Corrigir também o preset "Best Performance" da UI
   ([SettingsActivity.java:336](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java#L336)),
   que hoje reescreve `vuThread=true` por cima da detecção.
4. A mesma contagem de núcleos grandes do item 1 precisa substituir o
   `s_processor_list.size() < (mtvu ? 3 : 2)` de
   [VMManager.cpp:3603](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3603) — ver seção
   "Acoplamento com thread pinning" acima. **Implementar junto com**
   [`vmmanager-thread-pinning-nunca-ativado-android`](./vmmanager-thread-pinning-nunca-ativado-android_2026-08-10T16-02.md),
   nunca isolado.
5. Medir antes de assumir: o ponto de corte (`>= 3` grandes) veio do macOS. Confirmar com números
   reais de Android se o corte certo é 3 ou 2.
6. **Não mexer em `vu1Instant`** — ver a seção de falso positivo acima.
7. **Decidir a política de migração** (levantado pela revisão de 2026-08-10). O upstream aplica o
   gate só em primeiro uso e no preset Low-End, para não sobrescrever config salva de quem já usa
   o app. Nosso plano original remigrava todos via `ANDROID_PERFORMANCE_PROFILE_VERSION`. Remigrar
   é mais eficaz — atinge a base instalada, que é onde as reclamações estão — mas sobrescreve
   escolha explícita de usuário. Um meio-termo: só remigrar quem nunca tocou no toggle de MTVU,
   usando um flag `MtvuSetByUser` análogo ao `RendererSetByUser` que já existe em
   [main.cpp:189](../../../app/src/main/cpp/main.cpp#L189).

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
