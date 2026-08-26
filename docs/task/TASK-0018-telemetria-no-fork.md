# TASK-0018: trazer a telemetria de produção para o fork

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0018:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Devolver ao fork a metade da observabilidade que o upstream não tem: **enviar** o relato de crash,
**recuperar** o crash nativo da sessão anterior e **decodificar** o tombstone. Etapa 4 do
[plano do fork](../plano-fork-sobre-upstream.md).

## O que o upstream já tem, e o que falta

`Pasx2Application.installCrashLogging()` já faz duas coisas: tê stdout/stderr do lado Java para
`<externalFilesDir>/logs/session.log`, e escreve `crash-<time>.txt` num
`UncaughtExceptionHandler`. Isso serve quando alguém tem o aparelho na mão e sabe onde procurar.

O caso comum não é esse: o usuário reporta "trava" e ninguém nunca vê o aparelho. Três peças
faltam, e a busca por conceito confirma que **zero arquivos deles** citam telemetria, crash report
ou `ApplicationExitInfo`:

| Peça | Por que sem ela não há diagnóstico |
|---|---|
| Envio ao `/logErr` | um arquivo local que ninguém busca não é relato |
| `ApplicationExitInfo` | um crash **nativo** mata o processo sem passar pelo handler Java; só é recuperável no boot seguinte |
| Decodificador de tombstone | no Android 12+ o tombstone é protobuf; sem decodificar, o relato chega como blob binário |

## O achado que apagou metade do trabalho previsto

O plano previa portar `Host::ReportGraphicsBootDiagnostics` — o gancho que a linha anterior criou
para emitir a linha `GSBoot` fora do gate de log. **Não é preciso, e não deve ser feito.**

Aquele gancho existia porque na nossa árvore antiga *todos* os sinks de log nasciam em
`LOGLEVEL_NONE`: a informação existia e era descartada antes de chegar ao logcat. Na árvore do
upstream, `initialize` chama `Log::SetConsoleOutputLevel(LOGLEVEL_DEBUG)` e redireciona o stdout
nativo para o logcat. As linhas de perfil de GPU do `GSDeviceOGL`/`GSDeviceVK` — driver, versão,
regras do banco que casaram, bugs, workarounds — **já são impressas e já chegam ao logcat**, que o
`CrashReporter.captureLogcat()` já captura.

Isso importa além da economia: um gancho nosso em `pcsx2/Host.h` e `pcsx2/GS/GS.cpp` seria uma
edição em arquivos **compartilhados com o upstream**, ou seja, conflito garantido em todo merge
futuro — exatamente o que a regra do fork existe para evitar. **O delta no core continua zero.**

## Escopo

**Entra:**
- `com.armsx2.telemetry.{TelemetryReporter,CrashReporter,TombstoneParser}` — 993 linhas trazidas da
  linha anterior. Eram **totalmente autocontidas** (nenhum import do nosso app), então o port foi
  trocar a linha de `package`.
- Gancho em `Pasx2Application.onCreate()`.
- A linha de identidade de GPU alimentada do lado **Java**, no ponto em que o app já sonda as
  strings GL para `setAutoRendererGpuStrings`.

**NÃO entra:**
- Qualquer edição em `pcsx2/` ou `common/`. Ver o achado acima.
- A tela de configuração do kill-switch. A chave existe e é respeitada; expô-la é trabalho de UI.

## Duas decisões sobre SharedPreferences

O arquivo deles chama-se `"ARMSX2"`; o nosso, `"armsx2"`. Nome de SharedPreferences é nome de
**arquivo**, portanto case-sensitive: são dois arquivos distintos e a troca não falha em lugar
nenhum — o valor só aparece como ausente.

- **`CrashReporter`** passou a ler o arquivo do fork, sem migração. A única chave é uma marca
  d'água de "até quando já reportei saídas nativas", e começar do zero custa, no máximo, um relato
  repetido de um crash antigo na primeira execução.
- **`TelemetryReporter`** lê o do fork **mas respeita um opt-out antigo**: se o arquivo novo ainda
  não tem valor explícito e o antigo diz `false`, o `false` vence. Desligar telemetria é escolha de
  privacidade, não preferência de UI, e o default (ligado) a ressuscitaria em silêncio. Só nesse
  sentido — um `true` antigo não é propagado, porque é indistinguível do default e não carrega
  intenção nenhuma.

## Como validar

1. O APK de release compila com R8 ligado. As classes ficam em `com.armsx2.telemetry`, alcançadas só
   de Java/Kotlin, então não precisam de regra de `-keep` — mas isso só se prova compilando **e**
   rodando, porque a falha de R8 aparece em runtime.
2. Provocar um crash e confirmar que o relato chega ao `/logErr` com o tombstone decodificado e o
   logcat anexo.

A validação 2 **precisa de aparelho**, e não há nenhum conectado nesta sessão.

## Resultado

Entregue, e mais barato do que o plano previa: o port foi trocar uma linha de `package` em cada
arquivo, e o gancho no core deixou de ser necessário.

**Verificado no APK de release**, com R8 ligado — que é onde este tipo de mudança falha em silêncio.
As strings distintivas de cada peça estão no DEX empacotado: `logErr` (envio),
`telemetry_error_reporting` (kill-switch), `ARMSX2-AnrWatchdog` (watchdog de ANR), `Native crash`
(recuperação por `ApplicationExitInfo`) e `gl_renderer=` (a identidade de GPU anexada aos relatos).

Também verificado no fonte, e não pelo nome: **os dois handlers de crash encadeiam**. O nosso guarda
`sPrevHandler` e o chama; o `installCrashLogging()` deles guarda `previous` e o chama. Instalando o
nosso depois do deles, o nosso roda primeiro e o arquivo local deles continua sendo escrito. Era uma
afirmação que eu tinha escrito no comentário antes de conferir.

A validação 2 (crash real chegando ao `/logErr`) continua pendente de aparelho.
