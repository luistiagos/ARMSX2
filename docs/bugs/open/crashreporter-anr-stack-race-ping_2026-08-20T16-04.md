# Bug: watchdog de ANR captura a própria sonda e perde o stack do bloqueio real

- **Detectado em:** 2026-08-20 16:04 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`CrashReporter.java::lambda$watchdogLoop$1`)
- **Errors (serviço):** 1507 (1 ocorrência)
- **Classe:** inconclusive (falha de instrumentação de ANR)
- **Reincidência:** primeira vez; Xiaomi `24090RA29G`, app 1.0.10

## Sintoma

O report diz que a main thread ficou mais de 5 segundos sem responder, mas o stack capturado é:

```text
CrashReporter.lambda$watchdogLoop$1(CrashReporter.java:231)
android.os.Handler.handleCallback
android.os.Looper.loopOnce
```

Esse frame é a própria sonda que o watchdog postou na main thread, não a operação que a bloqueou.

## Causa raiz

Em [`CrashReporter.watchdogLoop`](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/CrashReporter.java#L240),
o watchdog mantém `synchronized(lock)` enquanto detecta timeout e chama `captureAnr`. Se a main
thread recuperar exatamente nessa janela, ela começa a executar o Runnable postado e bloqueia ao
tentar entrar no mesmo `synchronized(lock)`. `getStackTrace()` então fotografa a sonda, apagando a
causa original do ANR.

O evento confirma que houve atraso do looper, mas não permite atribuí-lo a `CrashReporter` nem a
qualquer subsistema específico.

## Como reproduzir

Bloquear a main thread por pouco mais de 5 segundos e liberá-la quando o watchdog entra no bloco de
timeout. O Runnable postado aparece como topo do stack sintético.

## Próximos passos

Não manter o monitor da sonda durante `captureAnr`: registrar o timeout, sair do
`synchronized(lock)`, capturar imediatamente o stack e só então aguardar a recuperação. Adicionar
timestamp/latência da sonda para distinguir atraso real da corrida de captura.

## Correção implementada — 2026-08-22

O protocolo `Object + synchronized + boolean[]` foi substituído por `CountDownLatch`. O Runnable da
main thread apenas executa `countDown()`, que nunca espera pelo watchdog. Após timeout, o stack é
capturado imediatamente e o watchdog aguarda o mesmo latch antes de rearmar, mantendo a proteção
contra spam sem transformar a própria sonda no bloqueio observado.

`assembleUnrestrictedDebug` passou. Aguardando confirmação por telemetria.
