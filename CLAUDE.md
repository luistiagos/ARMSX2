# CLAUDE.md — RetroSystem PS2, fork da árvore Android do ARMSX2

## O que é esta branch

**Esta é a árvore do `ARMSX2/ARMSX2` com um delta nosso por cima.** Não é o nosso app antigo com o
core deles enxertado — é o contrário, e a inversão é o ponto.

A branch nasceu de `662b114168` (upstream/master, 25/08/2026). Não há merge-base com a nossa linha
anterior: as duas histórias são independentes de propósito. Atualizar deixa de ser "portar commits"
e passa a ser `git merge upstream/master` com um delta conhecido.

O plano completo, com a lista do que reimplementar, está em
[`docs/plano-fork-sobre-upstream.md`](docs/plano-fork-sobre-upstream.md). **Leia antes de escrever
código.**

> **A linha anterior do produto continua existindo**, nas branches `main` e
> `feature/handoff-end-to-end`. Se o fork não vingar, ela é o produto. Nada foi apagado.

## Estrutura

```
pcsx2/  common/  3rdparty/       # o core compartilhado — deles, e é para continuar deles
platforms/android/               # o app Android
  app/build.gradle.kts           #   Kotlin DSL, com propriedades -Parmsx2.*
  app/src/main/java/com/armsx2/  #   app em Kotlin/Compose (ui/, config/, runtime/, input/, i18n/)
  app/src/main/cpp/              #   native-lib.cpp (a ponte JNI) + CMakeLists.txt + 3rdparty
docs/                            # nosso: processo, tasks, bugs, planos
scripts/                         # nosso: check_traceability.py, compare_jni_surface.py
```

`AGENTS.md` na raiz é deles e descreve o core. Este arquivo governa o que é **nosso**.

## A regra que sustenta o fork

> **Correção de motor nasce como contribuição ao upstream, não como edição local.**

Medido na linha anterior: em duas semanas, 22 arquivos compartilhados do core editados por nós. É
isso que faz a divergência voltar. O fork dá um ponto de partida limpo; sem esta regra ele suja de
novo na mesma velocidade.

Antes de corrigir qualquer coisa no core, **verifique se o upstream já resolveu**. Na linha
anterior, três dos cinco defeitos investigados já tinham resposta lá.

## Restrições de identidade — quebrar qualquer uma destas quebra instalação existente

| Item | Valor | Como |
|---|---|---|
| `applicationId` | `come.nanodata.armsx2` | `-Parmsx2.applicationId` (o default deles é `com.armsx2`). O `come` é **grafia herdada, não é typo a corrigir** — corrigir quebra toda instalação. |
| `versionCode` | continua a série **37, 38, 39…** | `-Parmsx2.versionCode`. ⚠️ O default deles é **1088**. Publicar 1088 por engano torna impossível voltar para a nossa série: o Android recusa instalar versionCode menor sobre maior. |
| `versionName` | continua `1.0.x` | `-Parmsx2.versionName` (default deles: `2.6.1`) |
| `PCSX2-Android.ini` | mesmo nome nos dois lados | nada a fazer — verificado |
| Namespace JNI | `Java_kr_co_iefriends_pcsx2_*` | preservado no upstream apesar do `applicationId` deles |
| SharedPreferences | arquivo `armsx2` | migrar as chaves usadas pelos módulos que reimplementarmos |

## Build

### Nativo (não precisa de Gradle nem do SDK)

```bash
cmake -G Ninja \
  -DCMAKE_MAKE_PROGRAM=D:/DevCaches/Android/Sdk/cmake/3.31.6/bin/ninja.exe \
  -DCMAKE_TOOLCHAIN_FILE=D:/DevCaches/Android/Sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID=true -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DLTO_PCSX2_CORE=OFF \
  -DARMSX2_EMUCORE_LIBRARY_NAME=emucore_4k \
  -DARMSX2_ANDROID_HOST_PAGE_SIZE=0x1000 \
  -DCMAKE_C_FLAGS="-O3 -g" -DCMAKE_CXX_FLAGS="-O3 -g" \
  -S platforms/android/app/src/main/cpp -B <build>
ninja -j 4 emucore_4k
```

Medido em 2026-08-26: configuração 120,7 s, build 825 s (13 min 45 s), 1.704 alvos, exit 0.

- **`-j 4` é obrigatório.** Com o paralelismo padrão do ninja o clang morre com `LLVM ERROR: out of
  memory` em arquivos aleatórios e não relacionados à mudança. É falta de RAM, não erro de código.
- **CMake 3.31.6, não 3.22.1.** O 3.22.1 (default do AGP) é o piso exato do `cmake_minimum_required`
  das dependências do shaderc e **falha** ao configurar spirv-tools.
- **`LTO_PCSX2_CORE=OFF`** encurta o link e não muda "compila?". O release deles liga LTO; um build
  com LTO **ainda não foi medido**.

### Pré-requisitos que não são óbvios

| Item | Situação |
|---|---|
| Dependências do shaderc | **não são vendoradas nem submódulos, e são `gitignore`d.** Isso significa que elas **não acompanham um `git worktree` novo** — cada árvore precisa das suas. Rodar `python platforms/android/app/src/main/cpp/3rdparty/shaderc/utils/git-sync-deps`, ou copiar as sete pastas (264 MB) de uma árvore que já as tenha. Esquecer isto falha com `SPIRV-Tools was not found` só quando o CMake configura, ou seja, minutos depois. |
| Rust / cargo | **não é preciso.** Sem cargo, o librashader se desliga sozinho (`RetroArch shader support DISABLED`) e o build segue. |
| NDK `28.2.13676358` | instalado |
| **JDK 21** | **obrigatório** para o Gradle 9.4.1 deles — o `gradle/gradle-daemon-jvm.properties` exige Java 21, e o JDK 17 nem inicia o daemon. Instalado em `D:/DevCaches/jdk-21`. O build nativo por CMake continua funcionando sem ele. |
| SDK platform 37 | **`platforms;android-37` não existe.** O que existe, e só no canal de preview (`--channel=3`), é **`platforms;android-37.0`** — o Android passou a usar versões menores (37.0, 37.1, 37.2-beta*). Instalado. |
| `cmdline-tools` | o `latest` (rev 20.0) entende SDK XML até v3 e o repositório está em v4. Instalado o `latest-2` (rev 23.0). ⚠️ **O `sdkmanager.bat` do 23.0 quebra o argumento no `;`** (`platforms;android-37.0` vira dois pacotes "não encontrados"). Contornar com `--package_file=<arquivo>`, uma linha por pacote. |

### APK

```powershell
cd platforms/android
./gradlew.bat :app:assembleGithubRelease `
  -Parmsx2.applicationId=come.nanodata.armsx2 `
  -Parmsx2.versionCode=<n> -Parmsx2.versionName=<x.y.z>
```

Dois flavors: `github` (sideload, tem `MANAGE_EXTERNAL_STORAGE`) e `play` (scoped storage / SAF).
O nosso canal é sideload → **`github`**.

Com `armsx2.applicationId` / `armsx2.versionCode` / `armsx2.versionName` no `gradle.properties`
(TASK-0017), as flags acima ficam desnecessárias e o default deixa de ser o perigoso.

> 🔴 **O release deles assina com a keystore de DEBUG** quando `armsx2_keystore.properties` não
> existe, e **não falha** ao fazê-lo — o comentário no `build.gradle.kts` diz *"NOT for
> distribution"*. Um APK com assinatura diferente quebra a atualização de todos os instalados, e a
> recuperação é desinstalar, perdendo saves. É o mesmo defeito que a linha anterior já tinha
> registrado no `deploy_release.ps1`. **A etapa de publicação precisa de um guard que aborte se o
> APK não estiver assinado com a chave oficial.**

> ⚠️ **R8 está ligado no release deles** (`isMinifyEnabled = true`); o nosso app antigo tinha
> `minifyEnabled false`. Tudo que o nativo alcança **por nome** precisa de regra em
> `app/proguard-rules.pro`. A ponte JNI já está coberta por `-keep class kr.co.iefriends.pcsx2.**`,
> mas qualquer classe nossa fora desse pacote resolvida por nome precisa da sua própria regra — e a
> falha aparece só em runtime, num build de release.

## Rastreabilidade: nada é commitado sem uma task

**Regra dura, e ela não muda por causa do fork.** Spec completa em [`docs/README.md`](docs/README.md).

> **Nenhum commit em `platforms/android/app/src/`, `pcsx2/`, `common/`, `scripts/` ou arquivos de
> build sem uma task em `docs/task/` que o descreva. O agente é quem commita.**

Por que existe: 1.0.20, 1.0.21 e 1.0.22 foram construídas, assinadas e distribuídas a clientes a
partir de 41 arquivos que nunca entraram em nenhum commit.

Uma task **cabe**, de preferência, em um commit. Isso deixou de ser regra verificada na
[TASK-0042](docs/task/TASK-0042-remover-regra-um-commit-por-task.md): voltar a uma task já commitada
é normal, e obrigá-la a `--amend` só para não ganhar um segundo commit reescrevia o histórico — o
mesmo estrago que o validador tem uma checagem inteira para detectar. O índice guarda todos os
hashes da task.

1. Escrever a task **antes** do código — número novo, escopo explícito (o que entra e o que
   deliberadamente **não** entra), e como será validada.
2. Commitar com a task no assunto: **`TASK-0016: <resumo no imperativo>`**. Esse prefixo é o vínculo
   autoritativo — nunca um hash escrito à mão.
3. Atualizar o outro lado de cada link (bug, feature) no mesmo commit.
4. `python scripts/check_traceability.py`, e só então push.

**Exceção `chore:`** — só para o que **não roda no aplicativo**: README, docs, formatação. Não cobre
`app/src/`, `scripts/` nem arquivos de build.

> ⚠️ O `--fix` do validador **não insere** no índice a linha de uma task nova, e reintroduz um espaço
> duplo antes do hash. Falhou 3× do mesmo jeito na sessão anterior. Conferir `docs/task/README.md` à
> mão depois de rodá-lo. Bug registrado; conserto na
> [TASK-0010](docs/task/TASK-0010-corrigir-validador-rastreabilidade.md).

## Antes de escrever Java/Kotlin que chame o nativo

A superfície JNI muda. **Remedir**, não confiar no que está escrito:

```bash
python scripts/compare_jni_surface.py <linha-antiga>/app/src/main/cpp/main.cpp \
                                      platforms/android/app/src/main/cpp/native-lib.cpp
```

Em 26/08, contra `662b114168`: 30 assinaturas idênticas, **3 divergentes**, 23 só nossas, 110 só
deles. A divergência que importa: `getGameCRC` retorna `jint` na linha antiga e **`jstring`** aqui.
JNI liga por **nome**, não por assinatura — trocar isso compila, linka, roda e devolve lixo em
silêncio. Ao reimplementar o catálogo, usar a forma **deles**.

## Regra que vale para qualquer código

**Não escreva código sobre símbolo que não abriu.** Nome plausível não é verificação; `grep` que
mostra a linha não é verificação. Abra a função onde o trecho entra (assinatura, tipo de retorno,
variáveis em escopo), todo símbolo que ele referencia, o import, e o comportamento em erro. Vale
para biblioteca de terceiro também.
