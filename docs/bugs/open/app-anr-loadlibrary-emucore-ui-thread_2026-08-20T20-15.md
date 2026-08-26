# Bug: `System.loadLibrary("emucore")` bloqueia `Application.onCreate` e causa ANR

- **Detectado em:** 2026-08-20 20:15 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`Runtime.java::nativeLoad`)
- **Errors (serviço):** 1522 (1 ocorrência)
- **Classe:** fail (ANR)
- **Reincidência:** primeira vez; OnePlus 8 Pro, app 1.0.16

## Sintoma

```text
java.lang.Runtime.nativeLoad
java.lang.System.loadLibrary
kr.co.iefriends.pcsx2.NativeApp.<clinit>(NativeApp.java:19)
kr.co.iefriends.pcsx2.App.onCreate(App.java:61)
```

A carga do núcleo nativo ultrapassou 5 segundos antes de qualquer Activity abrir.

## Causa raiz

[`App.onCreate`](../../../app/src/main/java/kr/co/iefriends/pcsx2/App.java#L46) acessa
`NativeApp.hasNoNativeBinary`; isso inicializa a classe e executa imediatamente os dois
`System.loadLibrary` de [`NativeApp.java:17-31`](../../../app/src/main/java/kr/co/iefriends/pcsx2/NativeApp.java#L17).
Toda a carga e relocação de `libemucore.so` ocorre na main thread.

## Como reproduzir

Iniciar o processo a frio num aparelho sob pressão de memória/I/O e medir `App.onCreate`. Se a
carga/relocação do `.so` passar de 5 segundos, o watchdog gera o mesmo stack.

## Próximos passos

Mover a inicialização nativa para um executor coordenado por uma tela de splash e impedir acesso a
métodos de `NativeApp` até o future completar. Medir separadamente `emucore` e
`armsx2_native_tools` para confirmar qual biblioteca domina o tempo.

## Correção implementada — 2026-08-22

`Application.onCreate()` não toca mais em `NativeApp` na main thread. O primeiro acesso — e,
portanto, os dois `System.loadLibrary` — ocorre no worker `ARMSX2-NativeInit`. O splash registra um
callback e mantém a navegação bloqueada até a inicialização terminar. Uma abertura explícita da
`MainActivity` também redireciona pelo mesmo gate. `NativeApp.initializeOnce` agora possui guarda
atômica real, evitando reinicialização posterior na UI.

`assembleUnrestrictedDebug` passou. Aguardando validação em cold start e telemetria.
