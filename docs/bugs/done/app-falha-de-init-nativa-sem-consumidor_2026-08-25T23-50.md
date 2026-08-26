# Bug: falha da inicialização nativa é registrada e depois ignorada

- **Detectado em:** 2026-08-25 23:50 (revisão de código, durante o handoff)
- **Origem:** leitura de [`App.java`](../../../app/src/main/java/kr/co/iefriends/pcsx2/App.java) — não veio de telemetria
- **Errors (serviço):** nenhum. É esse o ponto: o defeito consiste justamente em não produzir evento.
- **Classe:** fail
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0012](../../task/TASK-0012-portao-de-boot-nao-perde-informacao.md)

## Sintoma

Se `System.loadLibrary("emucore")` falhar — ABI incompatível, biblioteca ausente de um APK
recompactado, instalação corrompida —, o aplicativo **abre normalmente** e morre depois, com
`UnsatisfiedLinkError`, na primeira chamada a qualquer método de `NativeApp`. O crash é atribuído à
tela que teve o azar de chamar `NativeApp` primeiro, que muda conforme o caminho do usuário.

## Causa raiz

`App.onCreate` roda a inicialização nativa numa thread e grava o resultado:

```java
sNativeInitSucceeded = succeeded;
sNativeInitComplete = true;
```

`isNativeInitializationComplete()` tem consumidor. `isNativeInitializationSucceeded()` **não tem
nenhum** — `grep` no `app/src/main/java` inteiro devolve só a própria declaração. O portão de boot
(`BootSplashActivity`) espera a inicialização terminar e encaminha para a `HomeActivity` sem olhar
se ela deu certo.

Pior: o `Throwable` capturado era escrito no `Log.e` e descartado, então mesmo quem tivesse o
logcat só teria o stack — não havia como um relato de campo dizer *qual* das duas falhas ocorreu
(biblioteca não carregou vs. exceção dentro de `initializeOnce`).

## Como reproduzir

Instalar um APK sem a `libemucore.so` do ABI do aparelho (ou renomear a biblioteca num build de
teste). O app abre, a Home aparece, e o crash acontece na primeira ação que toque no emulador.

## Correção — 2026-08-25 (TASK-0012)

- `App` passou a guardar **o motivo** da falha (`getNativeInitializationFailure()`), distinguindo
  "libemucore não carregou" de uma exceção dentro de `initializeOnce`.
- `BootSplashActivity` — o portão de boot, o único ponto que sabe que a inicialização terminou —
  passou a checar `isNativeInitializationSucceeded()` antes de encaminhar. Falhou: diálogo
  explicando, evento `armsx2/boot` com ABIs do aparelho e motivo, e encerra em vez de seguir para
  uma tela que vai morrer.

## Validação

`gradlew compileUnrestrictedDebugJavaWithJavac` limpo. A validação de campo exige um APK
deliberadamente quebrado e não foi executada; o caminho é curto e não tem estado, mas isso é uma
afirmação sobre o código, não uma medição.
