# Bug: Thread pinning nunca é ativado no Android — EE migra para núcleos pequenos

> ## ⚠️ REVISÃO 2026-08-10 — o upstream já resolveu isso, e mediu que pinning ingênuo é PIOR
>
> A auditoria do `ARMSX2/ARMSX2` master mostrou que o diagnóstico está certo mas **a correção
> proposta não é "ligar e pronto"**. Não é um fix de baixo risco.
>
> **Confirmação independente do diagnóstico.** O issue upstream
> [#387](https://github.com/ARMSX2/ARMSX2/issues/387) (FFX travando na abertura de batalha, fechado
> 2026-07-29) descreve a mesma causa, na mesma função:
>
> > *"I think the cause could be `VMManager::SetEmuThreadAffinities()` setting affinity to 0 (no
> > pinning) unconditionally on android, relying entirely on the EAS scheduler to migrate VU1 onto
> > the prime core when load spikes."*
>
> **A medição que refuta o fix simples.** De `PerformanceTab.kt` no master:
>
> > *"Affinity Control Mode — opt-in CPU pinning for the EE/VU/GS threads. Android normally leaves
> > them unpinned **on purpose** (EAS puts the busiest thread on the prime core, and **pinning VU to
> > a mid-tier big core measured ~1.4x slower**), so this is EXPERIMENTAL and default Disabled. It
> > exists because the tradeoff is workload-dependent: GS-bound titles benefited from an explicitly
> > placed GS thread."*
>
> O EAS não é só um estorvo: ele move a thread mais ocupada para o núcleo *prime*, coisa que
> afinidade estática não faz. Fixar a VU num núcleo grande de segunda linha custou 1,4×.
>
> **Como o upstream resolveu:**
> - `si.SetBoolValue("EmuCore", "EnableThreadPinning", true)` no primeiro boot
>   (`platforms/android/app/src/main/cpp/native-lib.cpp:337`) — infra ligada.
> - Mas um `g_android_affinity_mode` novo, lido por `SetEmuThreadAffinities`, com **default 0 =
>   Disabled/scheduler-decides**. Comportamento padrão inalterado.
> - 8 modos expostos ao usuário: `Disabled`, `EE > VU > GS`, `EE > GS > VU`, `VU > EE > GS`,
>   `VU > GS > EE`, `GS > EE > VU`, `GS > VU > EE`, `Performance Cores`. Aplicam no próximo boot.
> - **Abordagem alternativa ao mesmo problema:** ADPF (`PerformanceHintManager`, API 33+) — informa
>   o deadline do frame ao scheduler para o DVFS subir o clock das threads EE/GS/MTVU, em vez de
>   fixar núcleo. Também experimental, default OFF. `NativeApp.setAdpfEnabled(boolean)`.
>
> **O que isso muda para nós:** a "Próximos passos" original (item 1: ligar `EnableThreadPinning`)
> produziria pinning estático incondicional — exatamente a configuração que o upstream mediu como
> mais lenta. Ver os passos reescritos no fim.

- **Detectado em:** 2026-08-10 16:02 (auditoria de código, motivada por relatos de lentidão em Redmi)
- **Origem:** auditoria de `ApplyAndroidPerformanceDefaults` / `VMManager::SetEmuThreadAffinities`
- **Errors (serviço):** nenhum — não é crash, não gera telemetria. Chega como reclamação de usuário
  ("roda bem uns segundos e despenca").
- **Classe:** fail (performance)
- **Reincidência:** sistêmico — afeta 100% dos aparelhos big.LITTLE desde sempre

## Sintoma

Queda de FPS severa e errática em aparelhos com poucos núcleos grandes (Redmi/Poco com 2 big + 6
little, ou 1+3+4). O padrão típico relatado é rodar aceitável por alguns segundos e então cair pela
metade, sem correlação com o que está na tela — assinatura clássica de migração de thread entre
clusters, não de carga gráfica.

## Causa raiz (CONFIRMADA no código)

A infraestrutura de pinning **existe e funcionaria** no Android. `InitializeProcessorList()` compila
no path `__linux__` ([VMManager.cpp:3459](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3459)), usa
`cpuinfo` e ordena os processadores por frequência decrescente:

```cpp
std::sort(processors.begin(), processors.end(),
    [](const cpuinfo_processor* lhs, const cpuinfo_processor* rhs) {
        return (lhs->core->frequency > rhs->core->frequency);
    });
```

E `SetEmuThreadAffinities()` ([VMManager.cpp:3586](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3586))
distribui EE / VU / GS nos primeiros da lista — ou seja, nos núcleos mais rápidos.

**Mas tudo isso é gateado por `EmuConfig.EnableThreadPinning`:**

```cpp
const bool new_pin_enable = (GetState() != VMState::Shutdown && EmuConfig.EnableThreadPinning);
if (s_thread_affinities_set == new_pin_enable)
    return;                                  // VMManager.cpp:3588-3590
```

`EnableThreadPinning` é um bitfield com default `false`
([Config.h:1281](../../../app/src/main/cpp/pcsx2/Config.h#L1281)), e o
`ApplyAndroidPerformanceDefaults()`
([main.cpp:130-163](../../../app/src/main/cpp/main.cpp#L130-L163)) **nunca o define** — nem o
`MigrateAndroidPerformanceDefaults()`. Grep no repo inteiro confirma: só existem leituras.

```
$ grep -rn "EnableThreadPinning" app/src/main/cpp/ app/src/main/java/
Config.h:1281                  # declaração do bitfield
GSRasterizer.cpp:1302,1303     # leitura (SW renderer)
FullscreenUI.cpp:3779          # leitura, default false
Pcsx2Config.cpp:1968           # (de)serialização
VMManager.cpp:3055,3588,3592   # leitura
```

## Contexto: por que big.LITTLE quebra isso

O PCSX2 roda três threads pesadas, correspondendo a três processadores do PS2:

| PS2 | O que faz | Thread no emulador |
|---|---|---|
| **EE** (Emotion Engine) | CPU principal, lógica de jogo | thread da VM — sempre existe |
| **VU1** (Vector Unit 1) | transforma geometria, alimenta o GS | junto com a EE, **ou** separada (= MTVU) |
| **GS** (Graphics Synthesizer) | rasteriza | thread separada (MTGS), sempre |

A EE é a mais crítica: código MIPS recompilado para ARM64, essencialmente serial, impossível de
paralelizar. O FPS do jogo é quase sempre refém dela.

Todo Redmi/Poco é big.LITTLE. Um arranjo típico:

```
2x Cortex-A78  @ 2.4 GHz   <- núcleos grandes
6x Cortex-A55  @ 1.8 GHz   <- núcleos pequenos
```

A diferença não é só clock: o A55 é *in-order* de 2 vias, o A78 é *out-of-order* de 4 vias com
muito mais cache. Para código recompilado com dependências apertadas, como o do EE, a perda real de
throughput é bem maior do que a razão de MHz sugere.

O scheduler do Android decide sozinho onde cada thread roda, e ele otimiza para **bateria**, não
para FPS. Ele migra threads entre clusters continuamente. Quando a thread da EE cai num A55 o FPS
despenca, e volta quando ela sobe — daí o sintoma "roda bem uns segundos e cai" sem relação com o
que está na tela.

> **Sobre a magnitude:** não temos medição própria. A expectativa de perda alta vem da diferença de
> microarquitetura entre A78 e A55, não de benchmark. Qualquer número citado neste bug é estimativa
> até alguém rodar com `adb shell top -H` num Redmi real.

## Acoplamento com MTVU (importante)

O mapeamento de afinidade **depende de MTVU estar ligado ou não**
([VMManager.cpp:3616-3638](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3616-L3638)):

```cpp
const u32 ee_index = s_processor_list[0];
const u32 vu_index = s_processor_list[1];
const u32 gs_index = s_processor_list[mtvu ? 2 : 1];   // "steal vu's thread if mtvu is off"
```

Ou seja:

```
MTVU ligado    -> EE=core0, VU=core1, GS=core2    (precisa de 3 núcleos rápidos)
MTVU desligado -> EE=core0,           GS=core1    (precisa de 2)
```

Num aparelho com **2** núcleos grandes isso decide tudo:

- **MTVU ligado + pinning:** `core2` já é um A55. A VU1 é fixada num núcleo pequeno, fica lenta, e
  a EE bloqueia em `WaitVU()` esperando por ela. Você fixou as threads — mas fixou uma no lugar
  errado. Pode ficar **pior** que sem pinning.
- **MTVU desligado + pinning:** EE e GS ficam cada uma num A78 e a VU1 roda dentro da EE. Duas
  threads, dois núcleos grandes, encaixe perfeito.

O guard de [VMManager.cpp:3603](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3603) tenta cobrir isso
mas conta processadores, não processadores *rápidos*:

```cpp
if (!new_pin_enable || s_processor_list.size() < (mtvu ? 3 : 2))
```

Num Redmi 2+6 isso dá 8 e passa. É a mesma falha da detecção de MTVU — contar núcleos em vez de
contar núcleos **grandes**, nos dois lugares.

**Conclusão: ligar pinning sem corrigir o MTVU pode piorar justamente os aparelhos de 2 núcleos
grandes que motivaram a investigação.** Os dois precisam ir juntos.

## Como reproduzir

1. Rodar qualquer jogo pesado num aparelho big.LITTLE com ≤2 núcleos grandes.
2. `adb shell top -H -p <pid>` e observar em qual núcleo a thread EE está.
3. Ela migra entre clusters; o FPS acompanha.

## Próximos passos (REESCRITOS após a revisão de 2026-08-10)

1. ~~Definir `EnableThreadPinning = true` e pronto.~~ **Não fazer isso isolado** — ver a caixa de
   revisão no topo. Pinning estático incondicional é a configuração que o upstream mediu como
   ~1,4× mais lenta. O port correto é o padrão deles: ligar a infra **e** introduzir um modo com
   default "scheduler decide", expondo os modos explícitos como opt-in experimental.
2. **Avaliar ADPF primeiro.** É mais barato de portar que os 8 modos de afinidade, não tem risco de
   regressão (é hint, não imposição), ataca a mesma causa (DVFS/scheduler subestimando a carga
   bursty da emulação) e o upstream já tem o JNI pronto (`setAdpfEnabled`). Requer API 33+.
   Provavelmente o melhor retorno por esforço de todos os bugs de performance abertos.
3. Portar o `g_android_affinity_mode` + os 8 modos como ajuste avançado, default Disabled. Note que
   a UI deles é Kotlin/Compose (`PerformanceTab.kt`) e a nossa é Java/ViewFlipper — o lado nativo
   é portável, a UI é reimplementação.
4. **Medir no Redmi antes de qualquer coisa.** A medição de 1,4× do upstream é do hardware deles,
   provavelmente Adreno/Snapdragon de topo. Num 2big+6little o balanço pode ser outro. Sem número
   nosso, tanto o bug quanto a refutação são hipótese.
5. ~~Definir `EnableThreadPinning = true` em `ApplyAndroidPerformanceDefaults()`~~ (mantido só como
   referência do que seria necessário se o item 3 avançar: migração em
   `MigrateAndroidPerformanceDefaults()` bumpando `ANDROID_PERFORMANCE_PROFILE_VERSION`, hoje 6).
2. Validar que `s_processor_list` vem populado no Android — o ordenamento por
   `core->frequency` do `cpuinfo` precisa distinguir os clusters corretamente. Conferir o log
   `"Ordered processor list: ..."` ([VMManager.cpp:3497](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3497))
   num Redmi real antes de confiar no ordenamento.
3. **Corrigir o guard de [VMManager.cpp:3603](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L3603)
   junto** — ver seção "Acoplamento com MTVU" acima. Ele precisa contar núcleos grandes, não
   `s_processor_list.size()`. Sem isso, ligar pinning num aparelho 2big+6little com MTVU ativo
   fixa a VU1 num A55 e piora o quadro. Casado com
   [`main-mtvu-forcado-sem-checar-nucleos-grandes`](./main-mtvu-forcado-sem-checar-nucleos-grandes_2026-08-10T16-02.md);
   **não implementar um sem o outro.**
4. **Risco a medir:** pinning rígido em 1 núcleo pode piorar sob throttling térmico, quando o
   kernel quer justamente tirar a carga do núcleo quente. Medir sessão longa (15+ min), não só o
   primeiro minuto.
5. Considerar expor como toggle avançado nas configurações para permitir A/B pelo usuário.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
