# Bug: o app fecha sozinho no Galaxy A03 (Mali-G57) — SIGSEGV dentro do driver Mali, disparado pelo timer query do OpenGL

- **Detectado em:** 2026-09-22 19:14 (chamado #46 do painel, cliente `5577920025239`)
- **Origem:** **delta do fork.** O caminho que estoura é o ramo `__ANDROID__` de
  `PopTimestampQuery`, escrito pela [TASK-0085](../../../task/TASK-0085-tempo-de-gpu-do-gl-usa-os-entry-points-da-extensao.md).
  O `upstream/master` chama `glGetQueryObjectuiv` (core) ali, não `glGetQueryObjectuivEXT`.
- **Errors (serviço):** **13 eventos**, ids `8070, 8073, 8084, 8090, 8091, 8093, 8141, 8143, 8145,
  8216, 8223, 8231, 8232` — projeto `armsx2/native`, todos `status=open`, todos com o **mesmo
  backtrace** (dois MD5 distintos só porque o cliente reinstalou o APK e o caminho
  `/data/app/~~<hash>/` mudou).
- **Classe:** crash (o processo aborta; o Android mostra *"RetroSystem PS2 fechou devido a um bug"*)
- **Complexidade:** baixa para o contorno (desarmar o GPU timing no GLES), média para a correção
  completa (verificar **todo** `glBeginQueryEXT`, não só o primeiro)
- **Reincidência:** 13 crashes em ~21 h no mesmo aparelho. É a assinatura nativa **mais frequente**
  dos últimos 77 eventos de `armsx2/native`.
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0102](../../../task/TASK-0102-timer-query-do-gl-so-quando-o-osd-de-gpu-pede.md)
  — **concluída em 2026-09-22**, cobre **só o item 1** da correção abaixo (a porta que tira o
  caminho do aparelho do cliente). Os itens 2 e 3 seguem **sem task**.
- **Status:** contornado no código, **aguardando prova em produção**. O bug fica **aberto** até a
  consulta do braço 4 da TASK-0102 ficar sem linha nova depois da publicação — e essa prova é
  negativa e pode nunca chegar, porque o cliente desinstalou o app.
- **Relacionado:**
  [gpu-timing do OpenGL no Android nunca produz leitura](../../done/gpu-timing-do-opengl-no-android-nunca-produz-leitura_2026-09-01T10-50.md)
  — é o bug que **introduziu** este caminho;
  [GOS da Samsung limita clock a metade em jogo](gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
  — é o outro defeito do mesmo chamado #46, já com [TASK-0100](../../../task/TASK-0100-assistente-da-rota-nativa-do-game-booster.md)
  e [TASK-0101](../../../task/TASK-0101-rota-longa-para-de-mandar-a-app-pago.md) abertas

## Sintoma

Palavras do cliente, na conversa de WhatsApp do chamado #46:

> *"Já e quando baixava o jogo que iai pra joga ele bugava"* · *"E voltava"* (2026-09-22 15:38 UTC)
>
> *"Travando ainda"* (17:21) · *"Pegou não tbm"* (18:31) · *"Tá indo não"* (18:43)

Às 18:44:45 UTC ele mandou o print do diálogo do sistema: *"o aplicativo RetroSystem PS2 fechou
devido a um bug; tente atualizar o aplicativo depois que o desenvolvedor fornecer um reparo"*.

Ele desinstalou o app às 15:37 UTC e pediu reembolso duas vezes (21/09 21:56 e 22/09 15:15).

## Evidência

### O aparelho e o binário — ambos identificados, sem inferência

```
Build fingerprint: 'samsung/a03ub/a03:13/TP1A.220624.014/A035MUBS9CYG1:user/release-keys'
Cmdline: come.nanodata.armsx2
```

| | |
|---|---|
| Aparelho | Samsung Galaxy A03, `SM-A035M/DS` (print do cliente de *Sobre o telefone*, 22/09 02:07) |
| Android | 13 (sdk 33) |
| GPU | **Mali-G57** — lido na **própria tela Renderizador do app** (print de 18:35), campo *Driver de GPU* |
| Driver GL | `/vendor/lib64/egl/libGLES_mali.so`, BuildId `dce5da26e42176b6` |
| App | `2.0.5` (`versionCode` 2005) |
| `libemucore_4k.so` | BuildId `d49f165fcc01a0c7ffe21c98ccd4c0db48fdae97` |

O BuildId do `.so` do tombstone **bate byte a byte** com o
`lib/arm64-v8a/libemucore_4k.so` de `dist/retrosystem-ps2.apk`
(sha256 `5adc04105355987c3465b9bf45c965e5445e656f8235404d49450ad014b7fdc2`, 46 651 538 B) — que é
exatamente o APK que o cliente baixou (o print da pasta de downloads dele mostra
`retrosystem-ps2.apk`, *46,65 MB*). **O binário que crashou é o que está publicado.**

### O backtrace (idêntico nos 13)

```
signal 6 (SIGABRT), code -1 (SI_QUEUE)
 #00 libc.so (abort+164)
 #01 libemucore_4k.so +0x8c0680                     <- nosso handler de sinal
 #02 libsigchain.so (art::SignalChain::Handler+1152)
 #03 [vdso] (__kernel_rt_sigreturn+0)               <- aqui o sinal foi ENTREGUE
 #04 libGLES_mali.so +0x7a71cc                      <- a falha nasce AQUI
 #05 libGLES_mali.so +0x767560
 #06 libGLES_mali.so +0x768440
 #07 libGLES_mali.so +0x766b88
 #08 libGLES_mali.so +0x766bcc
 #09 libGLES_mali.so (glGetQueryObjectuivEXT+60)
 #10 libemucore_4k.so +0x13ed3dc                    <- nosso call-site
 #11..#16 libemucore_4k.so                          <- MTGS -> VSync -> EndPresent
 #17 libc.so (__pthread_start+204)
 #18 libc.so (__start_thread+64)
```

### Linha do tempo — os crashes acompanham a conversa

`when` é a hora local do aparelho (−0300); a coluna UTC bate com o `dttime` do WhatsApp.

| error id | crash (UTC) | o que o cliente disse por perto |
|---|---|---|
| 8070 | 21/09 21:30 | 21:31 manda o print do diálogo de Memory Card |
| 8073 | 21/09 21:33 | 21:34 *"Isso é não tá abrindo não"* 🤦🏻‍♂️ |
| 8084 | 21/09 21:52 | 21:56 *"Vou desinstalar essa parada pedi meu dinheiro"* |
| 8090/8091/8093 | 21/09 22:15 / 22:16 / 22:20 | 22:23 *"Só amanhã aí né"* |
| 8141 | 21/09 22:28 | — |
| 8143/8145 | 22/09 01:51 / 01:52 | 01:58 o operador entra no atendimento |
| 8216 | 22/09 14:43 | 15:38 *"quando baixava o jogo ... ele bugava e voltava"* |
| 8223 | 22/09 17:21 | 17:21 *"Travando ainda"* |
| 8231 | 22/09 18:28 | 18:31 *"Pegou não tbm"* |
| 8232 | 22/09 18:43 | **18:44:45 o print do diálogo de crash** |

Os ajustes que o operador pediu (perfil de Speedhack `Low-End`, escalador `1x PS2`, troca de API
gráfica Vulkan→OpenGL) **não mexem neste caminho** — por isso nenhum deles ajudou.

### O recorte na telemetria

Consultado o serviço com `SELECT ... FROM errors e JOIN error_logs l ...
WHERE l.content LIKE '%glGetQueryObjectuivEXT%'`: **13 linhas, todas deste aparelho.** Nenhum outro
modelo da base produziu esta assinatura. É um defeito de driver **específico desta família**, não um
crash geral do app.

## Causa raiz

### 1. O call-site, provado por desmontagem do binário publicado

Há **um único** `glGetQueryObjectuivEXT` na árvore:
[`GSDeviceOGL.cpp:1670`](../../../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1670), dentro de
`GSDeviceOGL::PopTimestampQuery()`. Desmontando o `.so` do APK publicado no endereço do quadro #10:

```asm
13ed3b4: ldrb w8,  [x19, #0xc0c]      ; m_read_timestamp_query
13ed3bc: add  x22, x19, #0xbf4        ; &m_timestamp_queries[0]
13ed3cc: ldr  w0,  [x22, x8, lsl #2]  ; m_timestamp_queries[m_read_timestamp_query]
13ed3d4: mov  w1,  #0x8867            ; GL_QUERY_RESULT_AVAILABLE
13ed3d0: add  x2,  sp, #0x30          ; &available
13ed3d8: str  wzr, [sp, #0x30]        ; GLuint available = 0
13ed3dc: blr  x9                      ; <- glGetQueryObjectuivEXT   ** quadro #10 **
13ed3e0: ldr  w8,  [sp, #0x30]
13ed3e4: cbz  w8,  ...                ; if (!available) break;
```

É linha por linha o trecho de `GSDeviceOGL.cpp:1668-1673`. **Não é inferência pelo nome da função:
é o endereço do tombstone batendo com a instrução.** A cadeia dos quadros #11–#16 é
`__pthread_start → MTGS → VSync → EndPresent`, e `EndPresent` chama `PopTimestampQuery` em
[`GSDeviceOGL.cpp:1563`](../../../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1563).

### 2. A falha é do driver, e nós a transformamos em morte do processo

Os quadros #04–#08 estão **dentro** do `libGLES_mali.so`, cinco chamadas abaixo do ponto de entrada.
O kernel entregou um SIGSEGV ali (#03 é o retorno do sinal). Quem recebeu foi
`PageFaultHandler::SignalHandler`, que o PCSX2 instala para o VTLB; ele não reconheceu o endereço,
e repassou:

- [`common/Linux/LnxHostSys.cpp:462`](../../../../common/Linux/LnxHostSys.cpp#L462) — `CrashHandler::CrashSignalHandler(sig, info, ctx);`
- [`common/CrashHandler.cpp:377`](../../../../common/CrashHandler.cpp#L377) — `std::abort();`
  (no Android `HAS_LIBBACKTRACE` não está definido, então vale o stub, não a versão de 325)

Daí o `signal 6 (SIGABRT), code -1 (SI_QUEUE)`: o SIGABRT é **nosso**, levantado pelo `abort()`. O
SIGSEGV original nem aparece no tombstone.

### 3. Por que este código roda num aparelho de quem nunca pediu contador de GPU

[`GS.cpp:185`](../../../../pcsx2/GS/GS.cpp#L185) arma o timer query **incondicionalmente** ao abrir o
GS:

```cpp
const bool gpu_timing = g_gs_device->SetGPUTimingEnabled(true);
```

Não há porta de `GSConfig.OsdShowGPU`. Isso **não é delta nosso** — o upstream tinha a porta
(`5793dbc1ef`, *"Helps prevent a crash in Mesa3D Windows drivers"*) e ele mesmo a **removeu** em
`624717dfad` para consertar o `%` de GPU na barra de status. Nossa TASK-0055 só acrescentou o
`PerformanceMetrics::SetGPUTimingAvailable`.

O resultado prático: **um recurso puramente de diagnóstico chama uma função do driver todo quadro,
na máquina de um cliente que nunca ligou OSD nenhum** — e é essa função que mata o app.

### 4. O estado que provavelmente faz o Mali estourar — hipótese, não medida

[`KickTimestampQuery`](../../../../pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1699) confere o
`glGetError` **só até o primeiro `glBeginQueryEXT` ser aceito**
(`m_timestamp_query_verified`, linhas 1718 e 1742). Depois disso, a linha 1749 marca

```cpp
m_timestamp_query_started = true;
```

**sem conferir nada.** Se um `glBeginQueryEXT` posterior for recusado — e a medição da TASK-0085 no
Galaxy A12 registrou justamente `glBeginQueryEXT → 0x0505 GL_OUT_OF_MEMORY` — o anel avança com um
slot que nunca rodou, `PopTimestampQuery` incrementa `m_waiting_timestamp_queries`, e o quadro
seguinte lê com `glGetQueryObjectuivEXT` **um nome de query que existe mas nunca foi iniciado**.

É a mesma condição que a TASK-0085 corrigiu **para o primeiro begin** e deixou aberta para todos os
outros. No Mali-G52 r38p1 esse estado respondia `0x0502 GL_INVALID_OPERATION`; no driver Mali-G57
deste A03, cinco quadros de profundidade sugerem que ele desreferencia estrutura interna ausente e
cai.

**O que sustenta:** o defeito existe no código e é lido no arquivo. **O que falta:** nenhuma medição
neste aparelho prova que foi por aí — a hipótese concorrente é o driver cair por pressão de memória
(o A03 é aparelho de entrada, e o cliente baixava um `.iso` de 2,3 GB em paralelo), e aí
`glGetQueryObjectuivEXT` seria só onde ele toca a memória liberada primeiro. **As duas hipóteses
levam à mesma correção**, porque nas duas o único jeito de o app não morrer é não chamar a função.

## O que NÃO está provado

- Qual renderizador estava ativo em cada um dos 13 crashes. `libGLES_mali` + `glGetQueryObjectuivEXT`
  provam **OpenGL** no momento da falha; e `GSUtil::GetPreferredRenderer()` resolve `Auto → OpenGL`
  em Mali. Mas o print de 18:35 descreve *Vulkan* selecionado, então houve troca manual em algum
  ponto que a conversa não fixa.
- Quanta RAM o aparelho tem (3 GB ou 4 GB no `SM-A035M`). O cliente informou só os 64 GB de
  armazenamento; o agente **afirmou** 4 GB sem verificar.
- Se outros aparelhos Unisoc/Mali-G57 na base sofrem o mesmo: hoje há um só na telemetria.

## Correção proposta

Em ordem de custo, e as duas primeiras são independentes:

1. **Não armar o timer query quando o OSD de GPU está desligado** — devolver a porta que o upstream
   tinha em `5793dbc1ef`, mas **só no `__ANDROID__`**, para não reabrir o `624717dfad` no desktop.
   Remove o caminho inteiro do aparelho do cliente, sem perda funcional para quem joga.
   → **[TASK-0102](../../../task/TASK-0102-timer-query-do-gl-so-quando-o-osd-de-gpu-pede.md)**,
   **concluída em 2026-09-22**, validada no `SM-A127M` nos dois braços. O custo aceito está lá: com
   o OSD desligado, o `PerfLog` no GL do Android deixa de trazer o campo `GPU` — mas agora com
   interruptor.

   A implementação teve de acrescentar uma segunda mudança que a task não previa: no Android a
   borda `false -> true` de `GS.cpp:1118` é **inalcançável** (o app escreve `GSConfig.OsdShowGPU`
   direto na thread da CPU antes de enfileirar o `GSUpdateConfig`, e o `RenderOverlays` reescreve a
   flag a cada quadro), então sem trocá-la por um teste de nível a porta trancaria o OSD de GPU para
   sempre. Detalhe e prova no doc da task.
2. **Conferir `glGetError` em *todo* `glBeginQueryEXT`, não só no primeiro** (`GSDeviceOGL.cpp:1749`).
   Um begin recusado não pode consumir o slot — que é exatamente o que a TASK-0085 já decidiu, e ela
   só aplicou a decisão ao primeiro.
3. **Desarmar em definitivo após N falhas seguidas** usando o `m_timestamp_query_failures`, que hoje
   é contado e nunca lido para nada.

A correção de motor que valer a pena nasce como contribuição ao upstream (regra do `CLAUDE.md`);
o item 2 é candidato natural, porque o ramo `*EXT` é nosso mas o defeito de não conferir o begin
vale para qualquer GLES.

## O outro defeito do mesmo chamado

O chamado #46 **não nasceu** deste crash. Ele foi aberto em 21/09 21:36 UTC, com
`origem_motivo = agent_handoff_PS2_AGENT` e a mensagem de abertura *"Mais e 15 reais"*: o assistente
de desempenho mandou o cliente instalar o **LADB**, que custa R$ 14,99 na Play, depois de prometer
*"You are going to install a free app"*. Isso já está registrado em
[gos-samsung-limita-clock-a-metade-em-jogo](gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md),
com [TASK-0100](../../../task/TASK-0100-assistente-da-rota-nativa-do-game-booster.md) e
[TASK-0101](../../../task/TASK-0101-rota-longa-para-de-mandar-a-app-pago.md) abertas hoje. O chamado
#46 é a confirmação em campo: foi o pedido de pagamento que produziu o primeiro
*"quero meu dinheiro de volta"*, **antes** de qualquer crash entrar na conversa.
