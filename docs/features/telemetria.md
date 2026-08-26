# Telemetria de erros (RetroSystem PS2 / ARMSX2)

O app reporta erros de produção ao serviço **`/logErr` do DigitalStoreGames**, compartilhado por
vários projetos. Os relatórios ficam visíveis no painel `/admin/errors` e são triados pelo agente
`triagem-bugs-prod`, que grava bugs novos em [`docs/bugs/open/`](../bugs/open/).

> Isto é **independente** do `CrashReporter`, que envia o arquivo de crash completo a um endpoint
> privado (`emuladores.pythonanywhere.com/upload_log`). Os dois coexistem: o `CrashReporter` guarda
> o dump inteiro; o `TelemetryReporter` posta um resumo estruturado e deduplicado para triagem.

## Identificador `project`

Convenção: `<projeto>/<componente>`. Base do projeto = **`armsx2`** (o triador mapeia o primeiro
segmento antes da `/` de volta para este repositório). Componentes emitidos hoje:

| Componente | Origem | Ponto de captura | Quando envia |
|---|---|---|---|
| `armsx2/java` | Exceção Java não tratada | `Thread.setDefaultUncaughtExceptionHandler` (via `CrashReporter`) | na hora (síncrono, terminal) |
| `armsx2/anr`  | ANR (main thread travada > 5 s) | Watchdog do `CrashReporter` | na hora (síncrono) |
| `armsx2/native` | Crash **nativo** (SIGSEGV/SIGABRT no JIT x86→ARM64 — ex.: *Shadow of the Colossus* em `0x12218`) | `ApplicationExitInfo` (API 30+) lido no **próximo boot** | no próximo boot (assíncrono) |

### Como os crashes nativos são recuperados (sem código nativo)

Um SIGSEGV no JIT mata o processo pelo SO — **não** passa pelo `UncaughtExceptionHandler` Java. Em
vez de instalar um signal handler em C++ (arriscado dentro de um processo já corrompido, e exigindo
rebuild nativo), usamos a API oficial **`ActivityManager.getHistoricalProcessExitReasons()`** (API
30+): no boot seguinte, `CrashReporter.reportNativeExits()` lê as saídas anteriores, filtra
`REASON_CRASH_NATIVE`, e para cada uma anexa o **tombstone** (`getTraceInputStream()`, que traz o
backtrace nativo simbolizado) como `logs[]`. Um watermark (`telemetry_last_exit_ts` no
`SharedPreferences("armsx2")`) evita reenviar a mesma saída em boots futuros.

> **Limitação:** só em **Android 11+ (API 30)**. Em API 26–29 crashes nativos não são reportados
> (a API não existe) — degradação silenciosa, sem afetar o resto. O `CrashHandler.cpp` do PCSX2
> continua inerte no Android (segue escrevendo o backtrace no logcat e chamando `abort()`); a
> recuperação é 100% na camada Java.

## Endpoint e payload

- **POST** `https://digitalstoregames.pythonanywhere.com/logErr` · `Content-Type: application/json`
- Resposta sempre `200 "ok"`. Fallback **GET** (mesmos campos na querystring, sem `logs[]`) quando
  o POST falha.

```json
{
  "project":    "armsx2/java",
  "file":       "<arquivo do topo do stack>",
  "method":     "<Classe.metodo do topo do stack>",
  "message":    "<classe da exceção: mensagem>",
  "user_agent": "ARMSX2/<componente> <versao>",
  "platform":   "Android <release> (sdk <n>)",
  "screen":     "",
  "page_url":   "thread=..; pkg=..; ver=..; device=..",
  "logs":       ["<stacktrace completo>", "<tail do logcat>"]
}
```

### Limites impostos pelo cliente

| Campo | Limite |
|---|---|
| `message` | 4.000 caracteres (corta o excedente) |
| cada entrada de `logs[]` | 256 KB (mantém o **fim** do log) |
| nº de entradas em `logs[]` | 20 |

## Princípios de robustez (invioláveis)

A telemetria **nunca** altera o comportamento do app:

- Todo o caminho vive em `try/catch`; falha de rede/serialização/IO é silenciada.
- Timeout curto (**5 s**) em conexão e leitura; offline é tolerado (o relatório é perdido).
- **Dedup in-process:** um mesmo `hash(componente + "|" + mensagem)` não é reenviado na sessão.
- Envio **síncrono** nos caminhos terminais (crash/ANR) — uma thread de background seria morta
  antes de completar.

## Kill-switch e configuração

`SharedPreferences("armsx2")`:

| Chave | Tipo | Default | Efeito |
|---|---|---|---|
| `telemetry_error_reporting` | bool | `true` | `false` desativa todo o envio |
| `telemetry_endpoint` | string | `""` | vazio = endpoint padrão; preencher aponta a outro servidor |

Lido em `TelemetryReporter.init()`, chamado no `App.onCreate()` antes de qualquer report poder
disparar. Opt-out do usuário final: setar `telemetry_error_reporting = false` (via adb ou uma futura
toggle em Configurações).

## Arquivos

- [`app/.../utils/TelemetryReporter.java`](../../app/src/main/java/kr/co/iefriends/pcsx2/utils/TelemetryReporter.java) — cliente `/logErr` (`reportCrash(...)` síncrono/terminal e `report(...)` geral/assíncrono).
- [`app/.../utils/CrashReporter.java`](../../app/src/main/java/kr/co/iefriends/pcsx2/utils/CrashReporter.java) — pontos de captura: Java+ANR chamam `TelemetryReporter.reportCrash(...)`; `reportNativeExits()` recupera crashes nativos via `ApplicationExitInfo`.
- [`app/.../App.java`](../../app/src/main/java/kr/co/iefriends/pcsx2/App.java) — `TelemetryReporter.init(appContext)` + `CrashReporter.reportNativeExitsAsync()` no startup.

## Validação (smoke)

```powershell
$body = @{
    project="armsx2/test"; file="manual"; method="test"
    message="smoke telemetria armsx2"; user_agent="test"
    platform="Windows"; screen=""; page_url="setup-validation"
    logs=@("linha1`nlinha2")
} | ConvertTo-Json
Invoke-RestMethod -Method POST -Uri "https://digitalstoregames.pythonanywhere.com/logErr" -Body $body -ContentType "application/json"
# => ok
```

Lembre de **remover os dados de teste** do painel se poluírem produção.
