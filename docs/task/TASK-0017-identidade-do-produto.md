# TASK-0017: dar ao fork a identidade do RetroSystem PS2

- **Status:** em andamento
- **Criada em:** 2026-08-26
- **Concluída em:** —
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0017:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Fazer o app do fork se identificar como **RetroSystem PS2** e, sobretudo, **instalar por cima de uma
1.0.23 existente** sem desinstalar. Etapa 3 do [plano do fork](../plano-fork-sobre-upstream.md).

## O risco que esta task existe para eliminar

O `build.gradle.kts` deles tem `versionCode = ... ?: 1088` e `applicationId ... ?: "com.armsx2"`.
Os dois são propriedades de linha de comando, e é aí que mora o perigo: **basta esquecer uma flag
para publicar um APK errado**, e um dos dois erros é irreversível.

| Erro | Consequência |
|---|---|
| `applicationId` = `com.armsx2` | app novo lado a lado; o usuário perde dados e o updater não alcança |
| `versionCode` = 1088 | **irreversível.** O Android recusa instalar versionCode menor sobre maior; a série 38, 39… fica inalcançável para sempre naquele aparelho |

Por isso a correção não é "lembrar de passar as flags": é **mudar os defaults**, para que um
`./gradlew assembleGithubRelease` sem argumento nenhum já produza a nossa identidade.

## Escopo

**Entra:**
- `platforms/android/gradle.properties`: `armsx2.applicationId`, `armsx2.versionCode` e
  `armsx2.versionName` como defaults do projeto. As propriedades já são lidas por
  `providers.gradleProperty(...)`, então não é preciso tocar no `build.gradle.kts` deles — o que
  também mantém o merge com o upstream limpo.
- `res/values/strings.xml`: `app_name` de `ARMSX2` para `RetroSystem PS2`. O arquivo deles tem
  **exatamente uma string** — todo o resto do texto vive no módulo Kotlin de i18n.
- Ícone do launcher: os nossos `ic_launcher_background/foreground/monochrome` e os PNGs de mipmap.
- Nome do produto no `i18n/I18n.kt`, **apenas onde é o nome do app**.

**NÃO entra:**
- As 471 strings do app antigo. A maioria pertence a telas que não existem mais; o texto do app novo
  mora no i18n em Kotlin.
- Referências a infraestrutura **deles** que por acaso citam "ARMSX2": o updater do GitHub, o app do
  Discord, o exemplo de caminho em `network.hddImage.help`. Não são texto de identidade, são
  decisões funcionais — o updater é a etapa 5, o Discord fica para depois.
- Chave de assinatura. Ver o achado abaixo; é da etapa de publicação.
- `AppIconManager` (ícones alternativos escolhidos pelo usuário). É funcionalidade, não identidade,
  e depende de uma tela em Compose.

## Achados que esta task registrou e não resolve

**1. O release deles assina com a keystore de debug.** O `build.gradle.kts` diz, em comentário:
*"Sign release with the debug keystore so it's installable on-device without a separate signing
config. NOT for distribution."* Se `armsx2_keystore.properties` não existir, o build **não falha** —
produz um APK assinado com a chave errada.

Isto é exatamente o defeito que a linha anterior já registrou em
[`armsx2-release-keystore-fallback-perigoso`](../bugs/): um APK com assinatura diferente quebra a
atualização de **todos** os instalados, e a recuperação é desinstalar (perdendo saves). A etapa de
publicação precisa de um guard que **aborte** se o APK não estiver assinado com a chave oficial — o
`build-and-upload.ps1` antigo tinha um; ele não foi trazido e terá de ser reescrito.

**2. R8 está ligado no release deles** (`isMinifyEnabled = true`), enquanto o nosso antigo tinha
`minifyEnabled false`. Isso muda as regras do jogo para tudo que é alcançado **por nome** a partir do
nativo. A boa notícia: o `proguard-rules.pro` deles já tem

```
-keep class kr.co.iefriends.pcsx2.** { *; }
```

ou seja, a ponte JNI inteira está preservada, incluindo o `NativeApp` onde entram os nossos
callbacks. Mas qualquer classe nossa fora desse pacote que o nativo resolva por nome **precisa da
sua própria regra**, e a falha aparece só em runtime, num build de release.

## Como validar

1. `./gradlew :app:assembleGithubRelease` **sem nenhuma flag** produz um APK com
   `applicationId=come.nanodata.armsx2` e o nosso `versionCode`. Conferir com `aapt dump badging`.
2. Instalar por cima de uma 1.0.23 sem desinstalar, e o `PCSX2-Android.ini` sobreviver.

A validação 2 **precisa de aparelho** e não há nenhum conectado nesta sessão.

## Resultado

—
