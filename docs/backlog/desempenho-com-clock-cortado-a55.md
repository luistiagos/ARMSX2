# Backlog: fazer o emulador caber no clock que o aparelho deixa

**Origem:** investigação do limite de CPU do GOS no Galaxy A12, 2026-08-29/30 — ver
[`bugs/open/gos-samsung-limita-clock-a-metade-em-jogo`](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
**Data da análise:** 2026-08-30
**Prioridade:** Alta — é a única trilha em que o usuário leigo ganha velocidade **sem fazer nada**
**Revisado em:** 2026-08-30 · **Medido no Galaxy A12:** 2026-08-30

| item | task | situação |
|---|---|---|
| 0 — contadores zerados | [TASK-0060](../task/TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) | ✅ **corrigido e medido** — `EE 0%` → `EE 100%` |
| 1 — MTVU queima um núcleo | [TASK-0060](../task/TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) | ✅ **corrigido e medido** — `R`/90% → `S`/0% |
| 2 — biblioteca parada | [TASK-0057](../task/TASK-0057-limitar-a-taxa-do-fundo-2d-da-biblioteca.md) | em andamento — custo por quadro corrigido e medido |
| 3 — release contra debug | [TASK-0058](../task/TASK-0058-medir-release-contra-debug.md) | aberta — é medição |

> ## Os itens 0 e 1 eram o mesmo defeito, e nenhuma das duas hipóteses de escritório acertou
>
> **`CNTFRQ_EL0` lê 0 neste aparelho.** O firmware não programou o registrador, e no AArch64 a
> divisão por zero não levanta exceção — `udiv` devolve `0`, e em `double` a recíproca de `+inf`
> devolve `0.0`. Todo relógio de ticks do emulador divide por ele:
>
> * `PerformanceMetrics::Update` → `100.0 * (1.0 / (x/0.0))` = `0.0` → **`EE 0% GS 0% VU 0%`**
> * `ShortSpinOn` → `(elapsed * 1e9) / 0` = `0` → o orçamento `waited` de `WaitForWorkWithSpin()`
>   nunca alcança `SPIN_TIME_NS`, a thread nunca chega ao `m_sema.Wait()` → **MTVU a 90% de um
>   núcleo, em estado `R`, com a VM pausada**
> * `VMManager::UpdateTargetSpeed` → `s_limiter_ticks_per_frame` = `0` → **o limitador de quadros
>   não limita** (terceiro defeito, que nem estava no backlog)
>
> Registro completo em
> [`cntfrq-el0-lido-como-zero-zera-todo-relogio-de-ticks`](../bugs/open/cntfrq-el0-lido-como-zero-zera-todo-relogio-de-ticks_2026-08-30T21-30.md).
> Correção e medições na [TASK-0060](../task/TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md).
>
> **Duas hipóteses anteriores foram ao aparelho e voltaram erradas**, e as anotações "Revisão
> 2026-08-30" abaixo estão marcadas onde isso aconteceu. Ficam no texto de propósito: as duas eram
> consistentes com todo o código lido e com toda a medição existente, e o que as derrubou foi
> **executar** — uma em trinta segundos de `grep evtstrm /proc/cpuinfo`, a outra no primeiro
> `PerfLog` depois de instalar.

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

> **Revisão 2026-08-30 — três correções no parágrafo acima:**
>
> 1. **A ponte JNI não participa.** A linha de `PerfLog` é impressa dentro do núcleo, em
>    `PerformanceMetrics::Update()` (`pcsx2/PerformanceMetrics.cpp:405`). Os wrappers em
>    `native-lib.cpp:889` foram lidos e estão corretos; olhar para eles é olhar para o lugar
>    errado.
> 2. **Os nomes reais são `GetGSThreadUsage()` e `GetGPUUsage()`** (maiúsculas), não
>    `GetGsThreadUsage`/`GetGpuUsage`.
> 3. **Não é um defeito, são dois.** `EE`/`GS`/`VU` vêm de `ThreadHandle::GetCPUTime()`, cujo
>    caminho POSIX (`common/Linux/LnxThreads.cpp`) devolve **0 em toda falha** — indistinguível de
>    thread ociosa, e o único caminho que zera os três de uma vez enquanto o `fps` da mesma função
>    sai certo. `GPU` é outra coisa: vem de `GSDevice::GetAndResetAccumulatedGPUTime()`, que é
>    `0.0f` fixo quando o backend não tem timestamp query (`GSDeviceVK.cpp:893`) — ausência de
>    medição, não medição de zero.
>
> Tratado na [TASK-0055](../task/TASK-0055-contadores-de-desempenho-que-nao-mentem.md), que faz o
> log dizer `n/a` (ou omitir o campo) em vez de `0%`, seguindo a convenção que a própria função já
> usa para o `GSB`.
>
> **Medido em 2026-08-30 — o ponto (3) acima está errado na metade que importa.** O aviso que a
> TASK-0055 adicionou para a falha do relógio por thread **não disparou**: o relógio lê
> perfeitamente. O zero vem do **divisor**, não do dividendo — `CNTFRQ_EL0` lê 0 e
> `100.0 * (1.0 / (x/0.0))` é `0.0`. Corrigido pela
> [TASK-0060](../task/TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md):
>
> ```
> antes:   PerfLog: 25.6 fps | EE 0%   GS 0%  VU 0% GPU 0% | frame 771
> depois:  PerfLog: 25.0 fps | EE 100% GS 37% VU 0% GPU 0% | frame 758
> ```
>
> **`EE 100%`** — o gargalo deste aparelho é a thread EE, e agora está visível. O `GPU 0%`
> sobreviveu à correção e é outro defeito, agora isolado.

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

> **Revisão 2026-08-30 — a causa raiz está determinada, e nenhuma das três opções era a certa.**
>
> O diagnóstico acima descreve o `WFE` como espera de baixo consumo que não cede o núcleo. É pior
> que isso: `MonitoredWait()` (`common/HostSys.cpp:129`) só sai por uma escrita em `word` ou pelo
> **event stream** do timer arquitetural — que é opção de kernel
> (`CONFIG_ARM_ARCH_TIMER_EVTSTREAM`), não promessa da arquitetura. Com a VM pausada não há quem
> escreva; sem event stream, aquele `WFE` **não volta**.
>
> E o teto de 50 µs não salva: `WaitForWorkWithSpin()` só confere `SPIN_TIME_NS` **entre** chamadas
> a `ShortSpinOn()`. Uma chamada que não retorna nunca é contabilizada, e o `m_sema.Wait()` abaixo
> dela nunca é alcançado. Isso fecha a pergunta que a
> [TASK-0046](../task/TASK-0046-encerrar-thread-mtvu-no-shutdown.md) deixou explicitamente aberta —
> e casa com a medição: estado `R` (não `S`), tempo de CPU integral, para sempre.
>
> Por isso: **(c) não resolveria nada** (o problema não é o tamanho do teto, é ele nunca ser
> conferido) e **(a) trataria uma chamadora de um defeito de quatro** — `WaitForWorkWithSpin`,
> `WaitForEmptyWithSpin` e `UserspaceSemaphore::WaitWithSpin` passam pelo mesmo `ShortSpinOn`, e o
> MTGS passa pelas mesmas linhas. A [TASK-0056](../task/TASK-0056-wfe-sem-event-stream-trava-o-spin.md)
> corrige o `ShortSpinOn`: se `AT_HWCAP` não traz `HWCAP_EVTSTRM`, o caminho `WFE` não é usado.
>
> 🔴 **Medido em 2026-08-30: a TASK-0056 está REVERTIDA — o A12 TEM `evtstrm`.**
>
> ```
> $ adb shell "grep -o 'evtstrm' /proc/cpuinfo | head -1"
> evtstrm
> ```
>
> Era o primeiro passo do "Como validar" daquela própria task, e derrubou-a em trinta segundos. A
> guarda era um no-op exatamente no aparelho onde o defeito foi medido.
>
> **O que o raciocínio acima acertou:** que a thread está presa *dentro* de uma chamada, e que o teto
> de 50 µs só é conferido *entre* chamadas. Isso foi confirmado por uma sonda que lê o estado da
> thread de fora (uma thread presa num laço de userspace não consegue registrar nada sobre si mesma):
>
> ```
> @@MTVU_PROBE@@ open=1 turns=1751 state=-2 done=1     <- 18 s depois, os mesmos 1751
> ```
>
> **O que errou:** o motivo de a chamada não retornar. Não é o `WFE` não acordar — é `ShortSpinOn`
> devolver **0**, porque `CNTFRQ_EL0` lê 0 e `(elapsed * 1e9) / 0` é zero. Somar zero para sempre
> nunca alcança 50 µs. Corrigido pela
> [TASK-0060](../task/TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md):
>
> | VM pausada, tela bloqueada | antes | depois |
> |---|---|---|
> | `State` | `R (running)` | `S (sleeping)` |
> | CPU | 89–91% de um núcleo | 0% |
> | `voluntary_ctxt_switches` | **`0`**, na vida inteira da thread | 4 |
>
> E o ganho é maior do que este item supunha: a MTVU fica em `S` a 0% **durante o jogo** também, o
> que mostra que os ~100% que ela consumia jogando eram o mesmo giro quebrado, e não trabalho de
> VU1. É um núcleo de oito recuperado o tempo todo.

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

> 🔴 **Uma "revisão" de escritório disse aqui que a atribuição deste item estava errada. Estava
> errada a revisão.** Ela procurou "Salvos" em `SaveManagerScreen.kt` — o gerenciador de arquivos de
> save. **"Salvos" é a aba da BIBLIOTECA** (a outra é "Catálogo"), montada por `HomeScreen`, e ela
> desenha o fundo animado. Confirmado por captura de tela do aparelho: ondas e glifos de
> PlayStation, que são o caminho 2D do `LibraryWaveBackground`. O backlog estava certo.
>
> **Medido em 2026-08-30, e o diagnóstico "limitar a taxa" também não era o certo.** A tela já
> desenhava a **exatos 30,0 fps** — não porque algo limitasse, mas porque **cada quadro custava
> 34–46 ms** (`gfxinfo` p50 = 38 ms) e portanto caía em todo segundo vsync. Limitar o que já está
> preso não economiza nada.
>
> O custo era **por quadro**, e o `gfxinfo` dizia onde: `Number Slow issue draw commands` em **100%
> dos quadros**. A causa está no código: os cinco gradientes verticais da cena (o fundo, mais um por
> camada de onda) dependem só da cor e da altura — `baseY`, `amp`, `startY`, `endY` **não dependem
> do relógio** — e eram reconstruídos trinta vezes por segundo, cada reconstrução entregando um
> shader novo ao driver.
>
> A [TASK-0057](../task/TASK-0057-limitar-a-taxa-do-fundo-2d-da-biblioteca.md) cacheia o que não se
> move e limita a taxa a 30 fps com portão de 25 ms (33 ms cairia em cima do terceiro callback de
> 60 Hz e viraria 20 fps no primeiro jitter).
>
> ⚠️ **O ganho de CPU do cache NÃO está medido, e uma versão anterior desta anotação dizia que
> estava.** A "prova" era `Janky frames` 99,70% → 0,16% e `Slow issue draw commands` 100% → 0,16%.
> As duas métricas são relativas ao orçamento de 16,7 ms de um vsync de 60 Hz, então **um app que
> desenha a 30 fps é 100% "janky" por definição** — o que elas mostraram foi o app ter passado a
> 60 fps, não o quadro ter ficado mais barato. Com o limite de volta, as duas voltam a ~100%.
>
> **O A/B rodou** (dois APKs diferindo só no cache, ambos a 30 fps, alternados na mesma sessão com
> o aparelho ocioso) e o ganho é **~3 pontos de um núcleo, todos na main thread** — 25% com cache
> contra 28% sem, repetível em duas rodadas, com a `RenderThread` idêntica a 41% nas quatro
> amostras. O total fica em ~94% de um núcleo.
>
> **Portanto o item 2 continua sem solução.** O desperdício que o cache remove era real e a análise
> estava certa, mas ele é pequeno: o que resta é rasterização e preenchimento das quatro faixas em
> alpha, não criação de objetos. E o limite de taxa não entrega ganho aqui — só evita a regressão de
> a tela passar a desenhar 60 fps depois do cache.
>
> As três alavancas que sobram (menos camadas, cortar a faixa onde o gradiente já é invisível, ou
> não animar sem interação) **mudam o que o usuário vê** e são decisão de produto.

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

> **Revisão 2026-08-30 — o cuidado com o R8 já está coberto.** Os quatro `FindClass` da árvore fora
> de `3rdparty` (`NativeApp`, `HttpClient`, `HttpClient$Response`, `BiosInfo`) têm regra `-keep`, e
> os métodos nativos estão cobertos por `-keepclasseswithmembernames class * { native <methods>; }`.
> A tabela da conferência está na [TASK-0058](../task/TASK-0058-medir-release-contra-debug.md).
> Reflexão em Kotlin e classes citadas só no manifesto continuam sendo risco — o caminho JNI, que
> era o citado, não.
>
> Fazer esta medição **depois** da TASK-0055: sem os contadores de EE/GS/VU funcionando, a tabela
> mostra só `fps`, e um `fps` igual não distingue "não mudou nada" de "mudou o gargalo de lugar".

**Validar:** tabela de `PerfLog` das quatro combinações.

---

## Protocolo de medição usado aqui (para repetir)

```bash
# teto de clock, com o jogo rodando
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq

# velocidade real da emulação
adb shell "grep PerfLog /storage/emulated/0/Android/data/come.nanodata.armsx2/files/logs/emulog.txt | tail -4"

# consumo por thread do app  -- VER AS TRES ARMADILHAS ABAIXO ANTES DE USAR
adb shell "PID=\$(pidof come.nanodata.armsx2); for t in \$(ls /proc/\$PID/task); do \
  echo \"\$t \$(cat /proc/\$PID/task/\$t/comm) \$(awk '{print \$14+\$15}' /proc/\$PID/task/\$t/stat)\"; done"

# quadros REALMENTE desenhados -- e o que decide se um limite de taxa e no-op
adb shell "dumpsys gfxinfo come.nanodata.armsx2 reset"; sleep 15
adb shell "dumpsys gfxinfo come.nanodata.armsx2 | grep -E 'Total frames|Janky|Slow issue|percentile'"

# onde uma thread que gira esta presa, sem precisar de perf
adb shell "grep -E '^(State|voluntary|nonvoluntary)' /proc/<pid>/task/<tid>/status"
```

> ### Tres armadilhas no one-liner acima — cada uma custou uma medicao em 2026-08-30
>
> 1. **`comm` pode ter espaco.** `/proc/<tid>/stat` e `pid (comm) state ...`, e `CPU Thread`
>    tem um espaco dentro dos parenteses, o que desloca **todo** `awk '{print $14+$15}'`.
>    Contar os campos a partir do **ultimo** `)`. Threads sem espaco no nome (`RenderThread`,
>    `MTVU`) saem certas, o que faz a tabela inteira parecer boa.
> 2. **Em `sh`, `$12` nao e o parametro 12** — e `$1` seguido de um `2` literal. Escrever
>    `${12}`. Com o erro a soma da zero e a thread parece ociosa; foi assim que a MTVU
>    "sumiu" de uma amostra.
> 3. **A janela real nao e o `sleep`.** O laco gera ~90 processos por snapshot, entao
>    amostrar com `sleep 10` produz uma janela de ~14 s. Dividir pelo delta de
>    `/proc/uptime`, nunca pelo sleep pedido, senao todo percentual sai ~40% alto.
>
> ### E o que **nao** funciona neste aparelho
>
> **`simpleperf` esta bloqueado**, mesmo com `perf_event_paranoid = -1` e o app
> `profileable android:shell="true"`: `failed to open perf event file for event_type
> cpu-cycles: Permission denied`, e o mesmo com `-e cpu-clock`, que nem usa PMU. E o Knox.
>
> Para achar onde uma thread esta presa sem perf, o que funcionou foi
> **`voluntary_ctxt_switches`** (zero = a thread nunca bloqueou, uma vez sequer) mais uma
> sonda temporaria que registra o estado da thread **de fora**: uma thread presa num laco de
> userspace nao consegue registrar nada sobre si mesma.

Para comparar números com os desta análise, **matar o GOS antes** (`adb shell am force-stop
com.samsung.android.game.gos`) e conferir que o clock subiu — senão a medição mede a Samsung, não o
nosso código.
