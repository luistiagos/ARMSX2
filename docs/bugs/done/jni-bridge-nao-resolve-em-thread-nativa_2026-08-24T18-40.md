# Bug: a ponte JNI para `NativeApp` não resolve quando a primeira chamada vem de thread nativa

- **Detectado em:** 2026-08-24 18:40 (teste dirigido no Galaxy A12)
- **Origem:** `main.cpp::EnsureNativeAppMethods`
- **Errors (serviço):** nenhum — o defeito é justamente a ausência silenciosa de eventos
- **Classe:** fail
- **Reincidência:** pré-existente; só ficou visível ao instrumentar a falha
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0006](../../task/TASK-0006-diagnostico-boot-gs.md)

## Sintoma

`Host::ReportGraphicsBootDiagnostics` escrevia a linha no logcat mas nunca chegava ao Java: o evento
de telemetria simplesmente não saía, sem erro. Com um aviso instrumentado no ramo de falha:

```
W NDK_LOG : GSBoot: NativeApp.onGraphicsBootDiagnostics nao resolvido; evento nao enviado a telemetria.
```

Descartado antes de chegar à causa: o método existe no fonte, na classe compilada e **no dex do APK
instalado** (`classes7.dex`), e a assinatura `(Ljava/lang/String;)V` está correta.

## Causa raiz

`EnsureNativeAppMethods` resolve `kr/co/iefriends/pcsx2/NativeApp` com `env->FindClass()`, de forma
preguiçosa, na primeira vez que alguém precisa da ponte — e guarda um global ref.

`FindClass` usa o class loader **associado à thread corrente**. Numa thread criada pelo Java o loader
é o do app e a busca funciona. Numa thread nativa anexada por `AttachCurrentThread` — que é o caso
das threads de emulação e do MTGS — o loader é o do **sistema**, que não enxerga classes do app.

`OpenGSDevice` roda na thread do MTGS. Sendo ela a primeira a precisar da ponte, `FindClass` falhava,
o global ref nunca era preenchido e todas as chamadas seguintes falhavam junto.

**O mesmo valia, em silêncio, para `Host::ReportErrorAsync`.** O diálogo de erro entregue em 1.0.20
para acabar com "toda falha vira tela preta muda" só funcionava se alguma thread Java tivesse usado a
ponte antes — o que não é garantido justamente no caso que importa, uma falha logo no boot do GS.

## Como reproduzir

Chamar qualquer função que use `EnsureNativeAppMethods` a partir da thread do GS antes de qualquer
chamada vinda de thread Java. O lookup falha e o `ClearJNIExceptions` no fim da função apaga o
`NoSuchMethodError`/`ClassNotFoundException`, deixando o defeito sem rastro.

## Correção aplicada

`Java_kr_co_iefriends_pcsx2_NativeApp_initialize` — que roda na thread Java criada por
`App.onCreate()` — passou a chamar `EnsureNativeAppMethods(env)` logo no início. O global ref fica
resolvido pelo class loader certo antes de qualquer thread nativa precisar dele.

Além disso, o ramo de falha deixou de ser silencioso: agora distingue "sem JNIEnv nesta thread" de
"método não resolvido" no logcat.

## Validação

Galaxy A12 (`SM-A127M`, Android 13, Mali-G52), com o log desligado. Antes: só a linha `NDK_LOG` e o
aviso de falha. Depois: `NDK_LOG` **e** `ARMSX2-GSBoot` com o mesmo conteúdo, sem aviso.
