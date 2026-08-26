# TASK-0017: dar ao fork a identidade do RetroSystem PS2

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-26
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

## Achado que muda a etapa 5 — o arquivo de SharedPreferences difere só na CAIXA

O nosso app grava em `getSharedPreferences("armsx2", ...)`. **O deles grava em `"ARMSX2"`**
(`BootSplashActivity.kt:33`; o `BackupManager.kt` até comenta que o nome não é o
`<package>_preferences` usual).

Nomes de SharedPreferences são **nomes de arquivo**, e portanto **case-sensitive** no Android.
`armsx2.xml` e `ARMSX2.xml` são dois arquivos distintos. Ou seja: ao atualizar da 1.0.23 para o
fork, tudo que o usuário tinha em `armsx2.xml` fica invisível — não some do disco, mas o app novo
olha para o outro arquivo e vê tudo vazio.

Seis pontos do nosso app usam esse arquivo:

| Onde | O que guarda |
|---|---|
| `DataDirectoryManager` | **a raiz de dados escolhida** — o mais grave: o app novo procuraria jogos e saves no lugar padrão |
| `AppUpdateManager` | o `versionCode` que o usuário mandou pular |
| `AppIconManager` | o ícone alternativo escolhido |
| `GraphicsHealthMonitor` | as decisões persistidas (a TASK-0005 já as apaga por bump de schema) |
| `MainActivity`, `SettingsActivity` | alguns toggles de UI |

A maior parte das configurações do usuário **não** está aqui — vai por `NativeApp.setSetting()` para
o `PCSX2-Android.ini`, que tem o mesmo nome nos dois lados e sobrevive. Mas a raiz de dados está, e
ela sozinha justifica uma migração explícita.

Fica registrado aqui e é trabalho da etapa em que o módulo correspondente for reimplementado — não
desta task, que é identidade. O plano do fork foi atualizado.

## Resultado

Entregue o que a task se propôs.

| Item | Estado |
|---|---|
| `gradle.properties` com os três defaults | feito |
| `app_name` = RetroSystem PS2 | feito |
| Ícone do launcher | feito — ver a nota abaixo |
| Nome do produto no i18n | feito: **17** linhas no `I18n.kt` e **126** em 19 arquivos de tradução |
| Chaves preservadas de propósito | **29** ocorrências, todas das 3 chaves excluídas |

**Sobre o ícone, e por que não bastava copiar os PNGs.** A árvore deles define o launcher como
*adaptive icon* em `mipmap-anydpi-v26/` **e** `mipmap-anydpi/`. Qualificador `anydpi` vence PNG por
densidade na resolução de recursos — em qualquer API, não só 26+. Copiar os nossos PNGs e parar aí
teria deixado o ícone **deles** na tela, e o erro só apareceria olhando o launcher.

Os dois diretórios foram removidos, então `@mipmap/ic_launcher` resolve para os nossos PNGs, que é
exatamente o que a 1.0.23 faz hoje. Promover o nosso ícone a adaptativo de verdade não é copiar-e-
colar: o nosso é *legacy* (quadrado arredondado com borda neon já embutida, com padding), e usá-lo
como `foreground` seria recortado pela máscara. Fica como melhoria própria, se alguém quiser.

Os `drawable/ic_launcher_{background,foreground}.xml` **deles** ficam órfãos na árvore. Deliberado:
apagá-los só criaria conflito no próximo merge com o upstream, e o custo é alguns KB que o
encolhedor de recursos provavelmente remove.

**Validação 2 (instalar por cima de uma 1.0.23) continua pendente** — não há aparelho conectado
nesta sessão.
