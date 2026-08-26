# Bug: reporter de crash nativo envia tombstone protobuf cru — telemetria de 52 SIGABRT ilegível

- **Detectado em:** 2026-06-12 → 2026-07-16 (telemetria de produção; janela completa dos "Aberto")
- **Origem:** telemetria `armsx2/native` (`native::signal 6`)
- **Errors (serviço):** 483, 482, 481, 477, 476, 474, 473, 472, 471, 469, 468, 462, 461, 460,
  450, 449, 448, 447, 446, 445, 444, 443, 442, 441, 440, 439, 438, 437, 436, 435, 434, 433, 432,
  431, 430, 429, 428, 427, 426, 425, 424, 423, 422, 421, 420, 419, 418, 417, 416, 415, 413, 406
  (52 ocorrências — maior grupo de causa raiz de toda a telemetria aberta)
- **Classe:** inconclusive (a instrumentação quebrada impede diagnosticar a causa real dos crashes)
- **Reincidência:** recorrente, dezenas de devices/versões de Android distintos (Android 14/15/16,
  Xiaomi/Samsung/etc.), app_version 1.0.8 em todas as amostras

> **➡️ CORREÇÃO DISPONÍVEL:** guia de implementação passo-a-passo (com o código Java completo) em
> [`GUIA-fix-tombstone-decoder.md`](./GUIA-fix-tombstone-decoder.md). Este bug documenta o
> **diagnóstico**; o guia documenta o **fix**.

---

## 🔁 Recorrência — triagem de 2026-08-02

**O fix não foi aplicado e o volume quadruplicou.** Não existe `TombstoneParser.java` em
`app/src/main/java/kr/co/iefriends/pcsx2/utils/`, e `CrashReporter.readTrace` continua idêntico.

- **Janela agora:** 2026-07-12 → **2026-08-02 19:19**
- **Volume:** **236** erros `armsx2/native` abertos (eram 52) — **184 IDs novos** além dos já
  listados acima. Distribuição por `method`: 230 × `signal 6` (SIGABRT), 5 × `signal 0`,
  1 × `signal 4`.
- **IDs novos mais recentes:** 767, 766, 765, 764, 762, 761, 760, 759, 756, 755, 754, 753, 752,
  751, 750, 749, 748, 747, 745, 744, 743, 742, 741, 740, 738, 734, 733, 732, 730, 729, 727, 726,
  725, 721, 720, 719, 718, 717, 716, 715, 714, 713, 712, … (todos os `armsx2/native` abertos com
  `id > 483`)
- Todas as amostras continuam em `app_version 1.0.8`.

### Dois agravantes novos, confirmados no código

**1. A corrupção é irreversível — nem um decoder no servidor recupera.**
[`CrashReporter.java:198`](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/CrashReporter.java#L198)
faz `bos.toString("UTF-8")` sobre bytes de protobuf. Todo byte que não forma sequência UTF-8 válida
vira **U+FFFD** — informação destruída no device, antes do envio. O diagnóstico original
("não decodificado") era otimista: o payload que chega no painel **não é decodificável**, só
descartável. O fix tem obrigatoriamente que rodar **no cliente, antes da conversão para String**.

**2. O truncamento corta exatamente a parte útil.**
[`TelemetryReporter.capTail`](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/TelemetryReporter.java#L242)
mantém a **cauda** do log (`"...[truncated N bytes]...\n"` + últimos 256 KB) — o que é correto para
logcat, mas errado para um `Tombstone` protobuf, onde `signal_info`, `abort_message` e os
`threads`/backtrace ficam no **começo** da mensagem. Nas amostras 756, 692, 492 e 592 o que sobra é
só a cauda de `memory_mappings`, dominada por milhares de entradas repetidas
`/dev/ashmem/pcsx2 (deleted)`. Ou seja: mesmo que os bytes fossem preservados, o corte já teria
removido o cabeçalho diagnóstico.

### ✅ Workaround que destravou a triagem (e a causa raiz encontrada)

Os reports de **ANR** (`armsx2/anr`) anexam o **logcat** em `seq=1`, e o logcat captura o texto
`F DEBUG` que o `debuggerd` imprime para crashes nativos do mesmo device. Varrendo os 41 ANRs
abertos, **18 continham tombstones textuais completos** — 21 blocos, **todos `signal 6 (SIGABRT)`**,
18 deles na thread `CPU Thread`.

Isso permitiu simbolizar a assinatura dominante (19 de 21) contra o `libemucore.so` local, cujo
build-id `cae2d88186f6a3c046d36751b59d67adcf6699fc` **bate com o binário 1.0.8 em produção**, e
chegar na causa raiz real dos SIGABRT:

➡️ **[`vmmanager-abort-pxassertrel-vm-nao-shutdown_2026-08-02T19-19.md`](./vmmanager-abort-pxassertrel-vm-nao-shutdown_2026-08-02T19-19.md)**
— `pxAssertRel(state == VMState::Shutdown)` em `VMManager.cpp:1279` abortando no boot do jogo.

O reporter continua quebrado, mas a triagem dos crashes **não está mais bloqueada por ele**.

## 🔁 Recorrência — triagem de 2026-08-05

**Ainda não corrigido — nem commitado.** `TombstoneParser.java` continua inexistente no checkout;
o fix descrito no [GUIA](./GUIA-fix-tombstone-decoder.md) não foi aplicado (o `git status` desta
triagem mostra mudanças locais não commitadas em `main.cpp`/`VMManager.cpp`/`.h`/`NativeApp.java`,
mas nenhuma toca `CrashReporter.java` — essas mudanças são do fix de
[`vmmanager-abort-pxassertrel-vm-nao-shutdown`](./vmmanager-abort-pxassertrel-vm-nao-shutdown_2026-08-02T19-19.md),
não deste bug).

- **Janela agora:** telemetria completa dos "Aberto" em 2026-08-05 → **304** erros `armsx2/native`
  (eram 236) — **68 IDs novos**: 769–850 (com gaps normais de paginação), todos `app_version 1.0.8`.
  Distribuição por `method`: 293 × `signal 6` (SIGABRT), 10 × `signal 0`, 1 × `signal 4`.
- Amostras verificadas do lote novo (850, 847, 797, 592/id interno 457) — todas continuam com o
  mesmo padrão: `message`/`logs[0]` cheios de bytes binários (`U+FFFD`, paths `/system/lib64/*.so`,
  `[anon:scudo:*]`, `x0..x29`/`lr`/`sp`/`pc`) ou, quando o regex `signal ` não casa em lixo binário,
  o fallback genérico `"Native crash: crash"` (mesma causa raiz — `firstMatch` não achou a
  substring por acaso, não porque o payload virou legível).
- **Não crie um bug novo para este lote** — é o mesmo defeito de instrumentação, apenas mais
  volume. IDs novos anexados aqui para rastreabilidade: 769, 770, 771, 772, 773, 774, 775, 776,
  777, 778, 779, 781, 782, 784, 785, 786, 787, 788, 789, 790, 791, 793, 794, 795, 796, 797, 798,
  799, 800, 801, 802, 803, 804, 805, 806, 807, 808, 809, 812, 814, 815, 816, 817, 818, 819, 820,
  821, 822, 829, 830, 832, 833, 834, 835, 836, 837, 839, 840, 841, 842, 843, 844, 845, 846, 847,
  848, 849, 850.
- **Achado colateral útil:** os logcats anexados (`seq=1`) dos ANRs novos (780, 792, 813, 826)
  seguem trazendo o mesmo backtrace textual do `SIGABRT` em `VMManager::Initialize`
  (`#01 …a374f8… #02 …a1dee4… #03 …59cf8c… #04 …8bed24… Java_..._NativeApp_runVMThread+524`,
  `libemucore.so` BuildId `cae2d88186f6a3c046d36751b59d67adcf6699fc` — idêntico ao build 1.0.8 em
## 🔁 Recorrência — triagem de 2026-08-19

- **Janela agora:** telemetria completa dos "Aberto" em 2026-08-19 → **287 erros `armsx2/native`** (201 em 1.0.8, 86 em 1.0.10).
- **Status do Fix:** A implementação do `TombstoneParser.java` (decodificador protobuf sem dependências externas) foi integrada ao `CrashReporter.java` no código local em 2026-08-19. Os novos builds (1.0.14+) enviarão tombstones decodificados em texto legível para o `/logErr`.
- Todos os 287 erros abertos foram classificados como reincidências de builds anteriores (1.0.8/1.0.10) e consolidados para fechamento na telemetria.

## Sintoma


Todo erro `armsx2/native` com `method = "signal 6"` (SIGABRT) chega na telemetria com a
`message` e o log anexado (`seq=0`) preenchidos com **bytes binários crus**, não texto:

```
Native crash — 0://system/lib64/android.hardware.media.omx@1.0.soB 5b9da7e5c4ef266b0c73f46bbc1aeb45[...]
```

O conteúdo é claramente um blob **protobuf serializado** (tags/varints binários intercalados com
strings de paths de bibliotecas do sistema Android — `/system/lib64/*.so`, `/apex/.../libc++.so`,
`/dev/__properties__/...`, `anon:dalvik-*`, `x0`..`x29`/`lr`/`sp`/`pc` como nomes de campo de
registradores ARM64), não um stacktrace de texto. O payload chega a **100-300 KB por evento**
(3 amostras verificadas: 483, 469, 406 — 118 KB, 297 KB e 157 KB respectivamente). Nenhuma das 52
ocorrências tem uma linha `#00 pc ... (symbol)` legível — impossível dizer qual biblioteca/função
efetivamente crashou.

## Causa raiz (CONFIRMADA no código)

> **Nota sobre a triagem original:** o relatório da triagem marcou isto como "hipótese não
> confirmada" porque procurou o reporter no checkout **`E:\projects\ARMSX2`** (linhagem upstream mais
> recente, que **não tem telemetria alguma**). O reporter vive no **outro** checkout,
> **`E:\projects\play2\ARMSX2`** — a fonte do build 1.0.8 em produção (commit `1d2379bf
> "telimetry and fixes"`). Ao inspecionar o checkout certo, a hipótese foi **confirmada**.

O reporter é [`CrashReporter.java`](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/CrashReporter.java).
No método `readTrace(ApplicationExitInfo)`, ele lê `info.getTraceInputStream()` e faz
`bos.toString("UTF-8")` — **tratando o stream como texto**. Para `REASON_CRASH_NATIVE` em
**Android 12+**, esse stream NÃO é texto: é a mensagem **protobuf `Tombstone`** do debuggerd
(schema `android.os.TombstoneProtos` / `system/core/debuggerd/proto/tombstone.proto`), o mesmo
formato interno usado por `tombstoned`/`debuggerd` desde Android 12.

Consequência em `reportOneNativeExit(...)` (mesmo arquivo):
1. O regex `TOMBSTONE_FRAME` nunca casa (não há texto `#00 pc ...`), então `file`/`method` ficam nos
   defaults `native`/`signal <status>`.
2. `firstMatch(trace, "signal ")` casa um trecho binário qualquer que contenha a substring "signal "
   → `message` vira "Native crash — <lixo binário>".
3. `logs[0] = trace` = o blob binário inteiro (até 512 KB lido, capado a 256 KB por
   `TelemetryReporter.capTail`) → é isso que chega no painel.

O `x0`..`x29`/`lr`/`sp`/`pc` observados no meio do blob são exatamente os nomes de campo do proto
`Tombstone.Thread.Registers` — evidência direta de que é o protobuf, não texto.

**Amostra de controle:** o doc `docs/features/telemetria.md` já assumia (erroneamente) que
`getTraceInputStream()` "traz o backtrace nativo simbolizado". Essa suposição é a origem do bug: em
Android 12+ ele traz o protobuf, que precisa ser decodificado com o schema `tombstone.proto` antes de
virar texto legível.

## Impacto

Bloqueia o diagnóstico dos **52 crashes SIGABRT reais** por trás deste ruído — não dá pra saber se
são OOM do emulador, corrupção de heap do core PS2, ou um crash trivial de UI, porque a mensagem/log
nunca chegam legíveis. Além disso, cada evento carrega ~100-300 KB de lixo binário
(custo de storage/transferência). Qualquer triagem de causa raiz desses 52 eventos fica bloqueada até
o reporter decodificar o protobuf antes de enviar.

## Como reproduzir

Não há repro determinístico (crash real do app em campo). Para validar o diagnóstico:

1. Forçar um `REASON_CRASH_NATIVE` num device de teste Android 12+ (ex.: `kill -6 <pid>` no processo
   do app).
2. Reabrir o app e observar o report `armsx2/native` gerado — a `message`/`logs[0]` chegam binários.
3. (Após o fix) confirmar que passam a chegar com `signal 6 (SIGABRT)` + `backtrace:` legível.

## Próximos passos

Seguir o [`GUIA-fix-tombstone-decoder.md`](./GUIA-fix-tombstone-decoder.md):

- Criar `TombstoneParser.java` (parser protobuf mínimo, sem dependência nova) e decodificar o
  `Tombstone` **antes** de reportar — extrair signal + abort message + backtrace da thread que
  crashou, e enviar isso como `message`/`logs[0]` em vez do blob cru.
- Fallback seguro: se o parse falhar (formato inesperado), enviar um resumo curto
  ("tombstone protobuf não decodificado (N bytes)") — **nunca** o blob binário.
- **Watermark:** os 52 IDs listados **não serão reprocessados** (o `telemetry_last_exit_ts` já
  avançou por eles) — permanecem ilegíveis. Marcar como "instrumentação corrigida — aguardar
  recorrência já legível" e re-triar os crashes novos que chegarem decodificados para achar a causa
  raiz real do SIGABRT.

### Adicionados pela triagem de 2026-08-02

- **Decodificar no cliente, antes de virar String.** Ler o stream como `byte[]` e parsear o
  `Tombstone` ali — `bos.toString("UTF-8")` (CrashReporter.java:198) precisa sair do caminho do
  tombstone. Depois do `toString`, não há o que recuperar.
- **Não usar `capTail` para tombstone.** Depois do parse o payload já é curto; se algum truncamento
  ainda for preciso, tem que preservar o **começo** (signal + abort message + backtrace da thread
  que crashou), não a cauda de `memory_mappings`.
- **Anexar logcat também aos reports nativos.** Foi o logcat dos ANRs que salvou esta triagem;
  reports `armsx2/native` hoje vão só com `logs[0] = trace`. Anexar o logcat como `seq=1` daria
  redundância de graça enquanto o decoder não existe.
- **Prioridade:** o fix continua valendo (custo de storage, cegueira futura), mas **deixou de ser
  bloqueante** — a causa raiz dos SIGABRT já foi isolada pelo caminho alternativo. Priorizar
  abaixo do bug de `pxAssertRel`.

## 🔁 Recorrência — triagem de 2026-08-22: decoder corrigiu o payload, mas ainda perde 30 crashes

Foram encontrados dois lotes entre os 65 errors abertos do ARMSX2:

- **12 tombstones crus de 1.0.8:** 1474, 1475, 1479, 1576, 1586, 1587, 1588, 1589, 1590, 1591,
  1646 e 1659. Quatro ainda preservam a assertion de `VMManager::Initialize` e foram atribuídos ao
  bug de VM; os demais continuam inconclusivos.
- **30 falhas do decoder em 1.0.16–1.0.19:** 1549, 1550, 1551, 1552, 1553, 1560, 1561, 1562,
  1563, 1567, 1568, 1569, 1570, 1578, 1579, 1580, 1581, 1582, 1583, 1584, 1585, 1606, 1608,
  1609, 1610, 1611, 1618, 1619, 1628 e 1655.

O segundo lote não envia mais centenas de KB de lixo — melhoria confirmada —, porém todos chegam
como:

```text
Native crash — tombstone protobuf não decodificado (524288 bytes); crash
logs[0] = "crash"
```

Ou seja, o fix é **parcial**: protege storage e UTF-8, mas o limite exato de 512 KiB ainda entrega
ao parser uma mensagem truncada que ele não consegue interpretar. Isso é especialmente relevante
à investigação Mali: 1606 e 1608–1611 cercam o page fault decodificado 1607 no mesmo Galaxy A17,
mas não podem ser comparados por falta de backtrace.

**Correção de 2026-08-22:** o parser agora preserva signal, abort message e threads já concluídas
quando encontra o corte do buffer. O resultado é marcado como parcial e só é aceito quando contém
dados semanticamente úteis; bytes aleatórios continuam retornando `null`. Assim, um campo posterior
truncado não descarta mais todo o cabeçalho/backtrace já decodificado. Build completo passou.

O teste unitário `TombstoneParserTest.parseRetainsSignalBeforeTruncatedTrailingField` cobre a
regressão: um `SIGSEGV` completo seguido de um campo cortado precisa continuar decodificado e
marcado como parcial. A suíte `testUnrestrictedDebugUnitTest` passou (2/2 testes do parser).

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
