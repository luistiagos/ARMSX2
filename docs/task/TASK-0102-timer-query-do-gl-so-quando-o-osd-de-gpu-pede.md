# TASK-0102: no Android, o timer query do GL só é armado quando o OSD de GPU pede

- **Status:** aberta
- **Criada em:** 2026-09-22
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [crash-nativo-no-timer-query-do-gl-em-mali-g57](../bugs/open/armsx2-fork/crash-nativo-no-timer-query-do-gl-em-mali-g57_2026-09-22T19-14.md)
- **Commit:** —
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Tirar o app do cliente do chamado #46 de um crash que ele leva **13 vezes em 21 horas** por causa de
um contador que ele nunca ligou.

O aparelho (Galaxy A03, `SM-A035M`, Mali-G57, Android 13) morre com SIGSEGV **dentro do
`libGLES_mali.so`**, alcançado por `glGetQueryObjectuivEXT`. O call-site está provado por
desmontagem do `libemucore_4k.so` do APK publicado (BuildId `d49f165f…`): o endereço do quadro #10
do tombstone é o `blr` de
[`GSDeviceOGL.cpp:1670`](../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1670), dentro de
`PopTimestampQuery`. O `SIGABRT` que o Android registra é **nosso**: o handler de page fault do
PCSX2 não reconhece o endereço e repassa para
[`LnxHostSys.cpp:462`](../../common/Linux/LnxHostSys.cpp#L462) →
[`CrashHandler.cpp:377`](../../common/CrashHandler.cpp#L377) → `std::abort()`.

Esse caminho existe porque [`GS.cpp:185`](../../pcsx2/GS/GS.cpp#L185) chama
`SetGPUTimingEnabled(true)` **incondicionalmente** ao abrir o GS — sem olhar `GSConfig.OsdShowGPU`,
que nasce `false` ([`Pcsx2Config.cpp:764`](../../pcsx2/Pcsx2Config.cpp#L764)) e é exposto ao usuário
pelo app como `osdShowGpu`, também `false` por padrão
([`Settings.kt:706`](../../platforms/android/app/src/main/java/com/armsx2/config/Settings.kt#L706),
escrito em `EmuCore/GS/OsdShowGPU` na
[linha 1578](../../platforms/android/app/src/main/java/com/armsx2/config/Settings.kt#L1578)).

**Isso não é delta nosso.** O upstream teve a porta (`5793dbc1ef`, *"Helps prevent a crash in Mesa3D
Windows drivers"*) e ele mesmo a removeu em `624717dfad` para consertar o `%` de GPU na barra de
status do desktop. Nossa [TASK-0055](TASK-0055-contadores-de-desempenho-que-nao-mentem.md) só
acrescentou o `PerformanceMetrics::SetGPUTimingAvailable` ao lado. Por isso a porta volta **só sob
`__ANDROID__`**: o desktop não pode regredir para o defeito que o `624717dfad` corrigiu.

## O que esta task assume, e o que ela não assume

Ela **não** depende de saber por que o driver Mali cai. Há duas hipóteses vivas no doc do bug — o
slot morto de um `glBeginQueryEXT` recusado depois do primeiro
([`GSDeviceOGL.cpp:1749`](../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1749) marca
`m_timestamp_query_started = true` sem conferir), e pressão de memória no driver — e **nenhuma das
duas foi medida neste aparelho**, que não temos.

O que as duas têm em comum é o remédio: não chamar a função. É por isso que esta task entra antes de
qualquer investigação do driver, e por isso ela não precisa de aparelho A03 para valer.

## Escopo

**Entra:**

- **Uma porta em `GSDeviceOGL::SetGPUTimingEnabled`**
  ([`GSDeviceOGL.cpp:1752`](../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1752)), dentro do
  `#if defined(__ANDROID__)` que já existe ali (linhas 1757–1766), ao lado da recusa por extensão
  ausente: com `enabled == true` e `GSConfig.OsdShowGPU == false`, devolver `false` sem criar query
  nenhuma. O arquivo já lê `GSConfig` (`GSDeviceOGL.cpp:291`, `:311`) e já usa `Console.Warning`
  nesse mesmo bloco, então não entra `#include` novo.
- **Nada mais.** Os chamadores já tratam o `false`:
  [`GS.cpp:185-188`](../../pcsx2/GS/GS.cpp#L185) repassa para
  `PerformanceMetrics::SetGPUTimingAvailable` ([`PerformanceMetrics.cpp:716`](../../pcsx2/PerformanceMetrics.cpp#L716))
  e zera `GSConfig.OsdShowGPU`; e o `PerfLog` já omite o campo quando
  `s_gpu_timing_available` é falso ([`PerformanceMetrics.cpp:434`](../../pcsx2/PerformanceMetrics.cpp#L434)).

**Ligar o OSD depois, com o jogo rodando, continua funcionando** — e isto foi conferido, não
suposto: `GSUpdateConfig` atribui `GSConfig = new_config` na
[linha 995](../../pcsx2/GS/GS.cpp#L995), **antes** do bloco da
[linha 1118](../../pcsx2/GS/GS.cpp#L1118) que chama `SetGPUTimingEnabled(true)`. A porta lê o valor
**novo** e deixa passar. No boot vale o mesmo: `GSopen` faz `GSConfig = config` antes de
`OpenGSDevice`.

**NÃO entra:**

- **Conferir `glGetError` em todo `glBeginQueryEXT`** (o item 2 do doc do bug). É o defeito de
  verdade se a primeira hipótese estiver certa, é do código que veio do upstream, e é **candidato a
  contribuição** — mas é outra mudança, com outra validação, e não é o que tira o crash do aparelho
  do cliente hoje. Fica para task própria.
- **Usar o `m_timestamp_query_failures`** (item 3), que hoje é contado
  ([`GSDeviceOGL.h:275`](../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.h#L275)) e nunca lido.
- **Vulkan e desktop.** Nenhuma linha fora de `GSDeviceOGL.cpp`, e nenhuma fora do
  `#if defined(__ANDROID__)`. O Vulkan no Android continua medindo GPU como hoje.
- **As *pipeline statistics*.** Já são compiladas fora no Android, e o chamador delas em
  [`GS.cpp:189`](../../pcsx2/GS/GS.cpp#L189) **já** tem a porta de `GSConfig.OsdShowGPUStats`.

## O custo aceito, dito em voz alta

Com a porta, no **Android + OpenGL + OSD de GPU desligado** o `PerfLog` deixa de trazer o campo
`GPU`. É exatamente o sintoma que a
[TASK-0085](TASK-0085-tempo-de-gpu-do-gl-usa-os-entry-points-da-extensao.md) corrigiu, e ele volta —
**com um interruptor**: quem investiga liga `OsdShowGPU` e o campo reaparece. Antes não havia
interruptor nenhum, e o preço era o app fechar na mão de quem pagou.

Consequência prática para quem for repetir a medição da TASK-0085: **ligar o OSD de GPU primeiro**,
senão o campo não aparece e a ausência não significa mais regressão.

## Como validar

Aparelho disponível: Galaxy A12 `SM-A127M` (Mali-G52 r38p1) — **não** temos o A03 do cliente. A
validação local prova os dois braços da porta; a de produção prova o crash.

1. **Braço fechado** — `githubDebug`, jogo em **OpenGL**, `OsdShowGPU` desligado (o padrão):
   `adb logcat` mostra o aviso novo uma vez, o `PerfLog` sai **sem** o campo `GPU`, e nenhum aviso
   de `glBeginQueryEXT` aparece — porque nenhum begin é tentado.
2. **Braço aberto** — mesma sessão, ligar *OSD → GPU* nas configurações **com o jogo rodando**:
   o campo `GPU` volta a sair no `PerfLog` com valor que varia com a carga. Isto prova as duas
   coisas de uma vez: que a porta não quebrou a TASK-0085 e que o caminho de
   `GSUpdateConfig` reabre a porta.
3. **Desktop inalterado** — `git diff` não pode ter uma linha fora do `#if defined(__ANDROID__)`.
4. **Produção**, depois de publicar: a consulta do doc do bug
   (`SELECT ... FROM errors e JOIN error_logs l ... WHERE l.content LIKE '%glGetQueryObjectuivEXT%'`)
   **não pode ganhar linha nova** com `app_version` posterior a 2.0.5. Hoje ela tem 13, todas do
   `SM-A035M` em 2.0.5.

   ⚠️ **Essa prova é negativa e pode nunca chegar:** o cliente desinstalou o app em 22/09 15:37 UTC
   e pediu reembolso. Se ele não voltar, o que resta é a ausência de linha nova de **qualquer**
   aparelho — mais fraco, e é assim que deve ser registrado.

## Rollback

`git revert` do commit. A porta é uma condição isolada num `#if` que já existe; não há migração de
dado, arquivo de configuração novo nem chave de `SharedPreferences`.
