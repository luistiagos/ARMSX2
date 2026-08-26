# TASK-0012: fazer o portão de boot parar de perder informação

- **Status:** concluída
- **Criada em:** 2026-08-25
- **Concluída em:** 2026-08-25
- **Feature:** nenhuma
- **Bugs que resolve:** [app-falha-de-init-nativa-sem-consumidor](../bugs/done/app-falha-de-init-nativa-sem-consumidor_2026-08-25T23-50.md), [bootsplash-singletop-descarta-intent-novo](../bugs/done/bootsplash-singletop-descarta-intent-novo_2026-08-25T23-50.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0012:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Fechar as duas pontas soltas da §2.3 do handoff. As duas são o mesmo defeito estrutural em dois
lugares: **o portão de boot recebe uma informação e a joga fora**.

- `App` grava se a inicialização nativa deu certo; ninguém lê.
- `BootSplashActivity` é `singleTop` e recebe intents novos em `onNewIntent`; o método não existia,
  então o intent era descartado.

Uma task só para as duas porque são o mesmo par de arquivos e a mesma decisão — "o portão passa a
honrar o que recebe". Reverter uma sem a outra não faz sentido de produto.

## Escopo

**Entra:**
- `App`: registra o **motivo** da falha (`getNativeInitializationFailure()`), distinguindo
  "`libemucore` não carregou" de uma exceção dentro de `initializeOnce`. Antes o `Throwable` ia para
  o `Log.e` e era descartado, o que deixava o relato de campo sem como dizer qual das duas ocorreu.
- `BootSplashActivity`: checa `isNativeInitializationSucceeded()` antes de encaminhar; se falhou,
  emite evento `armsx2/boot` (com os ABIs do aparelho) e mostra um diálogo em vez de abrir uma tela
  que vai morrer no primeiro JNI.
- `BootSplashActivity.onNewIntent`: `setIntent()` para o intent novo, e reabertura do
  encaminhamento no caso raro em que ele já aconteceu.

**NÃO entra:**
- Tornar a `HomeActivity` utilizável sem o núcleo nativo. O aplicativo é um emulador; sem
  `libemucore` não há produto, e fingir que há seria pior que o diálogo.
- Mexer no tempo de carga da `libemucore` — isso é o
  [`app-anr-loadlibrary-emucore-ui-thread`](../bugs/open/app-anr-loadlibrary-emucore-ui-thread_2026-08-20T20-15.md),
  outro defeito, já corrigido por outro caminho.

## Como validar

1. `gradlew compileUnrestrictedDebugJavaWithJavac` — **feito, limpo**.
2. Campo (não executado): instalar um APK sem a `libemucore.so` do ABI do aparelho e confirmar o
   diálogo em vez do crash tardio; e tocar num `.iso` associado enquanto a splash está na tela,
   confirmando que o jogo abre.

## Resultado

Entregue. As duas validações de campo continuam pendentes e estão declaradas como pendentes nos dois
arquivos de bug — nenhuma delas foi exercitada em aparelho, porque a primeira exige um APK
deliberadamente quebrado e a segunda exige uma janela de boot longa o bastante para tocar num
arquivo no meio.
