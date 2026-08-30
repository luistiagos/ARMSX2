# Backlog: fazer o emulador caber no clock que o aparelho deixa

**Origem:** investigação do limite de CPU do GOS no Galaxy A12, 2026-08-29/30 — ver
[`bugs/open/gos-samsung-limita-clock-a-metade-em-jogo`](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
**Data da análise:** 2026-08-30
**Prioridade:** Alta — é a única trilha em que o usuário leigo ganha velocidade **sem fazer nada**

---

## Por que esta trilha existe

O Game Optimizing Service da Samsung trava os 8 núcleos do Exynos 850 em **1053 MHz de 2002 MHz**
(52,6%) enquanto o nosso jogo está em primeiro plano. Está medido, e o registro do bug tem as seis
rodadas. O resumo de um monitor de 5 min com o aparelho em uso real:

| | amostras em 1053 MHz | amostras em 1742–2002 MHz |
|---|---|---|
| GOS morto | **0** | 36 |
| GOS vivo | **98** | 16 |

**Não há conserto do lado do app**, e o paliativo (forçar a parada do GOS) é auto-derrotante: para
executá-lo o usuário sai do app, e a volta ao app ressuscita o serviço — medido, o teto voltou 2 s
depois da transição de foco.

Portanto: se não dá para levantar o teto, resta **caber embaixo dele**. Tudo aqui é sobre gastar
menos CPU para o mesmo jogo — e vale igualmente no aparelho sem GOS, que só ganha margem.

Referência de velocidade no aparelho de teste (SM-A127M, `10 Pin - Champions Alley`, PAL, alvo
50 fps): **8,5 fps** com o teto, **49,8 fps** sem ele.

---

## 0. Antes de tudo: os contadores de desempenho estão zerados

Toda linha de `PerfLog` colhida nesta investigação sai assim:

```
PerfLog: 18.8 fps | EE 0% GS 0% VU 0% GPU 0% | frame 565
PerfLog: 49.8 fps | EE 0% GS 0% VU 0% GPU 0% | frame 3646
```

**`EE`, `GS`, `VU` e `GPU` reportam 0% mesmo com o jogo a 8 fps e a 50 fps.** Enquanto isso não for
consertado, qualquer otimização abaixo é feita no escuro — não dá para saber se o gargalo é EE, GS
ou GPU, nem provar que uma mudança ajudou.

**Onde olhar:** `PerformanceMetrics::GetCPUThreadUsage()` / `GetGsThreadUsage()` / `GetGpuUsage()`,
expostos por `Java_kr_co_iefriends_pcsx2_NativeApp_getCpuThreadUsage` e vizinhos em
`platforms/android/app/src/main/cpp/native-lib.cpp:889`. Todos devolvem 0 quando
`VMManager::HasValidVM()` é falso — mas aqui a VM é válida, então o zero vem do próprio
`PerformanceMetrics`.

**Validar:** com um jogo rodando, as quatro figuras deixam de ser 0 e somam algo coerente
(EE alto num jogo pesado de CPU, GS alto num pesado de GPU).

**Este item vem primeiro.** Sem ele, os três abaixo são chute com aparência de engenharia.

---

## 1. A thread MTVU queima um núcleo inteiro sem ter trabalho

**Medido:** com a **VM pausada** e o **telefone bloqueado**, a thread `MTVU` consumiu 724 ticks em
~7 s de relógio — ~100% de um núcleo, em estado `R`, indefinidamente.

**Causa, confirmada no código:** o laço de `VU_Thread::ExecuteRingBuffer()` espera em
[`pcsx2/MTVU.cpp:136`](../../pcsx2/MTVU.cpp) com:

```cpp
semaEvent.WaitForWorkWithSpin();
```

Na linha anterior do produto (`feature/handoff-end-to-end`, `app/src/main/cpp/pcsx2/MTVU.cpp:134`)
a mesma linha é `semaEvent.WaitForWork()` — espera bloqueante, sem giro. A diferença entre as duas
está em [`common/Semaphore.cpp:63`](../../common/Semaphore.cpp): a versão com giro chama
`ShortSpinOn` até `SPIN_TIME_NS` (50 µs, `common/HostSys.cpp`) antes de dormir. No ARM64
`ShortSpinOn` usa `WFE` — o núcleo entra em espera de baixo consumo, mas a thread **continua
executável**, então ela nunca cede o núcleo ao escalonador e o DVFS a vê como carga permanente.

**Num octa-A55 sem núcleo grande, isso é 1/8 da CPU e calor constante.** Com o clock cortado pela
metade, pesa o dobro.

**A fazer:** decidir entre (a) voltar a `WaitForWork()` no alvo Android, como a 1.0.23; (b) manter o
giro só enquanto a VM está **executando**, e dormir quando pausada; (c) encurtar `SPIN_TIME_NS` no
ARM64. A (a) é a que já tem histórico de campo.

**Cuidado:** a [TASK-0046](../task/TASK-0046-encerrar-thread-mtvu-no-shutdown.md) já mexeu nesta
área (fechou a thread no shutdown). Ler antes.

**Validar:** VM pausada e tela bloqueada → a thread `MTVU` para de acumular ticks. E medir fps antes
e depois num jogo VU-pesado, porque tirar o giro pode custar latência de sincronização EE↔VU1.

---

## 2. A biblioteca parada consome mais de um núcleo

**Medido** na tela "Salvos", **sem jogo nenhum rodando**, amostragem de 5 s:

| thread | consumo |
|---|---|
| `RenderThread` | 60% de um núcleo |
| main (`nanodata.armsx2`) | 17% |
| `hwuiTask0` / `hwuiTask1` | 13% cada |
| `mali-cmar-backend` | 12% |
| **total** | **~1,15 núcleo, contínuo** |

E o `gfxinfo` do mesmo processo: **126.937 quadros, 99,31% com jank**, 49.450 deles acima de 133 ms.

**Origem provável:** o fundo animado da biblioteca —
[`ui/home/LibraryWaveBackground.kt`](../../platforms/android/app/src/main/java/com/armsx2/ui/home/LibraryWaveBackground.kt),
que redesenha num `Canvas` a cada quadro via `withInfiniteAnimationFrameNanos`, mais o
`LibraryBackground`/`XmbGlView` conforme o aparelho.

**Por que importa mesmo não sendo durante o jogo:** o aparelho chega quente ao boot do jogo, e calor
é o que convida o próprio corte. Também é bateria queimada à toa numa tela onde o usuário só escolhe
um jogo.

**A fazer:** medir o custo isolado de cada camada (onda 2D, onda GL, glifos), e decidir entre
limitar a taxa do fundo (30 fps ou menos), pausá-lo quando não há interação, ou desligá-lo por
padrão em aparelho sem núcleo grande.

**Validar:** mesma amostragem de threads na tela "Salvos" parada — o total deve cair para bem abaixo
de meio núcleo, e o jank do `gfxinfo` deve desabar.

---

## 3. Ninguém mediu release contra debug

O APK usado em todos os testes desta investigação é **`githubDebug`** — confirmado pelo `sha256` do
`base.apk` batendo com `app-github-debug.apk` e pela flag `DEBUGGABLE` no `dumpsys package`.

O núcleo nativo **não** está sem otimização: o `CMakeCache.txt` do build debug traz
`CMAKE_CXX_FLAGS=-O3 -g` com `CMAKE_CXX_FLAGS_DEBUG` vazio. Mas o lado Java/Kotlin roda **sem R8**
(`isMinifyEnabled = true` só no release) e o app é `debuggable`, o que muda o caminho do JIT do ART.

**A fazer:** medir o mesmo jogo, mesmo save, mesmo protocolo, em `githubRelease` e `githubDebug`,
com e sem o teto do GOS. É a incógnita mais barata do time — pode ser 0% e pode ser 20%.

**Cuidado:** o release é ofuscado por R8, e o que o nativo alcança **por nome** precisa de regra em
`app/proguard-rules.pro`. Falha só aparece em runtime.

**Validar:** tabela de `PerfLog` das quatro combinações.

---

## Protocolo de medição usado aqui (para repetir)

```bash
# teto de clock, com o jogo rodando
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq

# velocidade real da emulação
adb shell "grep PerfLog /storage/emulated/0/Android/data/come.nanodata.armsx2/files/logs/emulog.txt | tail -4"

# consumo por thread do app
adb shell "PID=\$(pidof come.nanodata.armsx2); for t in \$(ls /proc/\$PID/task); do \
  echo \"\$t \$(cat /proc/\$PID/task/\$t/comm) \$(awk '{print \$14+\$15}' /proc/\$PID/task/\$t/stat)\"; done"
```

Para comparar números com os desta análise, **matar o GOS antes** (`adb shell am force-stop
com.samsung.android.game.gos`) e conferir que o clock subiu — senão a medição mede a Samsung, não o
nosso código.
