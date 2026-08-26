# Spike do transplante sobre o upstream — o que foi medido

- **Data:** 2026-08-26
- **Ref do upstream medido:** `662b114168` (25/08/2026, *"VU: say what the fourth clamp rung carries"*)
- **Ref anterior:** `be72a8e1eb` (18/08/2026)
- **Contexto:** executa a §3 do [`handoff-proxima-sessao.md`](handoff-proxima-sessao.md) e atualiza os
  números de [`avaliacao-rebase-sobre-upstream.md`](avaliacao-rebase-sobre-upstream.md).

> O handoff diz, em letras próprias: **"Não estime em dias. Meça."** Este documento é a medição.
> Onde ele contradiz números anteriores, o número anterior estava certo para o ref de 18/08 e o
> upstream andou — não é correção de erro, é o alvo a mover-se.

---

## 1. O que foi executado, e o que não foi

| Passo do handoff §3 | Estado |
|---|---|
| 1. `git fetch --depth 100 upstream master` | **feito** |
| 2. Branch nova a partir da árvore do upstream | **feito** (worktree, ver §2) |
| 3. Trazer o nosso `app/` para `platforms/android/` | **não feito** — ver §7 |
| 4. Tentar compilar o resultado | **não feito** — depende do 3. Mas a árvore deles **sozinha** foi compilada: §4b |
| 5. Orçar o build em separado | **feito, com número medido** — §5 |

Os números estáticos (JNI, savestates, tamanho das camadas) foram **remedidos** contra o ref novo,
porque foram obtidos originalmente contra `be72a8e1eb`.

---

## 2. Onde está a árvore do upstream

```
D:/projects/play2/ARMSX2-upstream-spike     # git worktree, detached em 662b114168
```

**2,6 GB no total**, 12.600 ficheiros — 700 MB da árvore (incluindo as dependências do shaderc
buscadas pelo `git-sync-deps`) e 1,9 GB do diretório de build da §4b. É um `git worktree` do próprio
repositório, não um clone: partilha o banco de objetos, então custou disco só para os ficheiros.
Para remover:

```powershell
git worktree remove --force D:/projects/play2/ARMSX2-upstream-spike
```

`--force` é preciso porque o `.spike-build` e as dependências do shaderc não estão rastreados.

Fora da árvore, a cadeia de ferramentas instalada para o build ocupa mais **2,2 GB** (NDK
`28.2.13676358`) mais o CMake `3.31.6` no SDK.

**Aviso sobre a distância:** `git fetch --depth 100` traz exatamente 100 commits. `git rev-list
--count be72a8e1eb..662b114168` devolve **100** — que é o tamanho da janela, não a distância real.
Como não há merge-base com o upstream, **continua não existindo contagem exata**, e o "~1.050
commits atrás" continua sendo a ordem de grandeza, não um número.

---

## 3. A superfície JNI, remedida

Comparação automática de nome + tipo de retorno + tipos de parâmetro entre a nossa
[`main.cpp`](../app/src/main/cpp/main.cpp) (2.463 linhas, 56 métodos) e a `native-lib.cpp` deles
(5.138 linhas, 143 métodos).

| | 18/08 (`be72a8e1eb`) | 26/08 (`662b114168`) |
|---|---|---|
| Comuns | 33 | **33** |
| Comuns com assinatura **idêntica** | 31 | **30** |
| Comuns com assinatura **divergente** | 2 | **3** |
| Só nossos (reimplementar) | 23 | **23** |
| Só deles (ganho de graça) | 103 | **110** |

### A divergência nova, e é a pior das três

```cpp
// nosso  — main.cpp:686
JNIEXPORT jint    JNICALL Java_..._NativeApp_getGameCRC(JNIEnv*, jclass);
// deles  — native-lib.cpp:524
JNIEXPORT jstring JNICALL Java_..._NativeApp_getGameCRC(JNIEnv*, jclass);
```

Mesmo nome, **tipo de retorno diferente**. JNI liga por nome: isto **compila, liga e roda**. O
`NativeApp.getGameCRC()` declarado `int` do lado Java receberia os 32 bits baixos de um ponteiro
`jstring`.

Não crasha — e é isso que o torna perigoso. Produz um CRC errado, em silêncio, e o CRC alimenta a
busca de capas, os overrides de GameDB e a chave de decisão do `GraphicsHealthMonitor`. É
exatamente a classe de defeito que este projeto passou o último mês a caçar: comportamento errado
sem sintoma que aponte a causa.

### As outras duas, confirmadas no ref novo

| Método | Nosso | Deles |
|---|---|---|
| `NativeApp_initialize` | `(jstring path, jint apiVer)` | `(jstring path, jstring biosFolder, jint apiVer)` |
| `NativeApp_clearAchievementsHostOverride` | `(jboolean, jboolean)` | `()` — semântica diferente |

O `initialize` continua sendo o que a avaliação chama de **modelo de dados divergente**, não um
parâmetro a mais: eles fixam a BIOS em `externalFilesDir/bios` porque o systemDir escolhido pelo
utilizador não hospeda BIOS sob scoped storage no Android 11+; nós resolvemos o mesmo com
`DataDirectoryManager.getDataRoot()` + `reloadDataRoot`. Duas respostas concorrentes ao mesmo
problema, colidindo na primeira chamada nativa que o app faz.

**Como reproduzir esta medição:**

```bash
python <scratchpad>/jni_compare.py app/src/main/cpp/main.cpp <upstream>/native-lib.cpp
```

---

## 4. A primeira tentativa não configurou — e o motivo é útil

> **Superado no mesmo dia.** A cadeia de ferramentas foi instalada e a árvore do upstream **compila
> limpa**; ver §4b. Esta seção fica porque a lista de pré-requisitos abaixo é o que faz a diferença
> entre "não configura" e "compila", e ela não está documentada em lugar nenhum do lado deles.

Tentativa de configurar o CMake Android deles com o nosso NDK 29 e CMake 3.22.1. A configuração
avança bastante — detecta o compilador, resolve `BuildParameters`, `SearchForStuff`, oboe, shaderc —
e morre em:

```
CMake Error at 3rdparty/shaderc/third_party/CMakeLists.txt:80 (message):
  SPIRV-Tools was not found - required for compilation
```

Não é defeito: o `.github/workflows/build-all.yml` deles diz o que falta.

| Pré-requisito | Tínhamos, naquela tentativa? | Nota |
|---|---|---|
| `python3 .../shaderc/utils/git-sync-deps` | **não executado** | SPIRV-Tools, spirv-headers e glslang **não são vendorados nem submódulos** — são buscados da rede no momento do build. **A nossa árvore vendora `3rdparty/glslang`; a deles não.** |
| CMake **3.31.6** | não — só 3.22.1 | O comentário deles é explícito: *"3.22.1 (AGP's default) is the shaderc deps' exact `cmake_minimum_required` floor and fails to configure spirv-tools"* |
| Rust + target `aarch64-linux-android` | **não** | `librashader` é compilado por cargo. **Opcional na prática:** sem cargo a configuração imprime *"librashader: cargo not found -> RetroArch shader support DISABLED"* e segue. Só o suporte a shaders RetroArch se perde. |
| NDK `28.2.13676358` | não — só o 29.0.14206865 | Sobrepujável por `-Parmsx2.ndkVersion`; que o 29 sirva continua **não verificado** (o build da §4b usou o 28.2) |
| SDK platform 37 (`compileSdk`/`targetSdk`) | não — só até 36 | Só afeta o build Gradle/APK, não o nativo. E `platforms;android-37` **não existe** no repositório que o `cmdline-tools` desta máquina enxerga (ele entende SDK XML até v3 e o repositório está em v4) — atualizar o `cmdline-tools` é pré-requisito para o APK. |

### O achado que muda a leitura de risco

O job Android deles é `continue-on-error: true`, com o comentário *"Non-blocking until the
collapsed-core Android build first goes green."* E o cabeçalho do `CMakeLists.txt` do módulo diz:

> *"This wiring is structurally correct but has **NOT been compiled against a real Android NDK
> yet**."*

Esse cabeçalho está **desatualizado** — o `REFACTOR_STATUS.md` registra, em 2026-07-10, *"Android is
compile-green + runtime-verified (dual-core 4k/16k APK, PGO, running games on device)"*, com God of
War II a 60 fps num RP6 (Adreno 740 / Turnip).

Mas as duas coisas juntas dizem o seguinte, e é o que importa para decidir: **a árvore do upstream
esteve verde no Android há mês e meio, e desde então nada na CI impede que ela deixe de estar.** Não
é motivo para não transplantar; é motivo para o primeiro passo do transplante ser *build limpo da
árvore deles, sem o nosso código dentro*, antes de qualquer porte. Se isso falhar, falhou por conta
deles, e é muito mais barato de diagnosticar do que uma falha depois do nosso módulo estar lá.

---

## 4b. A árvore do upstream compila — medido

Com NDK `28.2.13676358` e CMake `3.31.6` instalados e o `git-sync-deps` do shaderc executado, a
configuração passa e o build **completa limpo**:

| | |
|---|---|
| Configuração | **120,7 s** |
| Build de `emucore_4k` a `-j 4` | **825 s (13 min 45 s)**, 1.704 alvos, exit 0 |
| `libemucore_4k.so` resultante | 324.726.312 bytes (com símbolos de debug; o nosso, comparável, tem 164.504.416) |
| Métodos JNI `Java_kr_co_iefriends_pcsx2_*` exportados | **143** — batendo exatamente com a contagem estática da §3 |
| Avisos | 3, todos de variável não usada em `native-lib.cpp` |

Isto responde à pergunta que a §4 dizia ser a única que não depende de nós: **a árvore deles
compila.** O job Android da CI deles ser `continue-on-error` continua sendo um risco de processo,
mas não é um risco atual — hoje, `662b114168` está verde no nativo.

Comando exato, para quem repetir:

```bash
cmake -G Ninja \
  -DCMAKE_MAKE_PROGRAM=<sdk>/cmake/3.31.6/bin/ninja.exe \
  -DCMAKE_TOOLCHAIN_FILE=<sdk>/ndk/28.2.13676358/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID=true -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DLTO_PCSX2_CORE=OFF \
  -DARMSX2_EMUCORE_LIBRARY_NAME=emucore_4k \
  -DARMSX2_ANDROID_HOST_PAGE_SIZE=0x1000 \
  -DCMAKE_C_FLAGS="-O3 -g" -DCMAKE_CXX_FLAGS="-O3 -g" \
  -S platforms/android/app/src/main/cpp -B <build>
ninja -j 4 emucore_4k
```

`-DLTO_PCSX2_CORE=OFF` foi usado de propósito: o build de release deles liga LTO, que custa muito
mais no link e não muda a resposta de "compila?". Um build com LTO ligado ainda não foi medido.

---

## 5. O orçamento do build — medido, não estimado

O handoff diz *"Um dia é otimista para build **e** porte"*. Para o **build**, isto está medido e é
bem melhor que isso.

Medição na nossa árvore, com o mesmo ninja e o mesmo `-j 4` que o transplante usaria:

| Medida | Valor |
|---|---|
| Apagar os 325 objetos do core `PCSX2` e reconstruir **+ linkar** `libemucore.so` | **333 s (5 min 33 s)** |
| Custo por TU do core, a `-j 4` | ~1,02 s |
| Objetos totais na nossa build a frio | 1.248 (325 core + ~923 3rdparty) |

A extrapolação original dizia "25 a 45 minutos" para a árvore do upstream a frio. **Medido depois:
825 s (13 min 45 s)** para 1.704 alvos — abaixo da faixa estimada. A estimativa errou para cima
porque assumiu o custo por TU do core (C++20 pesado) aplicado também ao 3rdparty, que é muito mais
barato por arquivo.

**Conclusão, agora com os dois lados medidos:** o build **não é o gargalo**. 13 min 45 s para a
árvore inteira do upstream a frio, contra os 5 min 33 s que a nossa leva só para refazer o core. O
gargalo é o porte. Um dia é otimista para o porte sozinho — e o build cabe num intervalo de café.

> `-j 4` é obrigatório, não preferência: com o paralelismo padrão do ninja o clang do NDK 29 morre
> com `LLVM ERROR: out of memory` em ficheiros aleatórios e não relacionados à mudança.

---

## 6. A camada de app: o número que reformula a Opção A

Esta é a medição que mais muda a decisão, e não estava na avaliação anterior.

| | Nós | Upstream |
|---|---|---|
| Linhas de app (Java/Kotlin) | 24.096 (Java) | **63.998** (136 `.kt` + 16 `.java`) |
| XML de recurso | **115** | **20** |
| Stack de UI | Views + Material3 + `ViewFlipper` | **Jetpack Compose** |
| Catálogo de ROMs + fila de download | **sim** | **não existe** |
| Atualização in-app | nosso `version.json` + canal | `UpdaterEntry.kt`, flavors `github`/`play` |

20 ficheiros de XML para 64 mil linhas de app é a assinatura de Compose. Ou seja, a "Opção A — a
nossa camada Android sobre o core deles" não é neutra: significa **descartar 64 mil linhas de app
deles** — assistente de configuração, hub de definições, atualizador, o que houver — para preservar
24 mil das nossas. Isso pode continuar sendo a escolha certa, porque o catálogo e a fila de download
são nossos e não existem lá; mas é uma escolha com um preço que a formulação anterior não mostrava.

E as duas camadas **não se misturam parcialmente**: XML/Views e Compose podem coexistir num app, mas
não numa tela. Portar ecrã a ecrã é possível; herdar metade de cada não é.

### O que não quebra — verificado, não suposto

| Item | Estado |
|---|---|
| `applicationId come.nanodata.armsx2` | **seguro.** É `-Parmsx2.applicationId` no `build.gradle.kts` deles (default `com.armsx2`), propriedade de linha de comando — não precisa tocar em fonte. |
| Nome do `PCSX2-Android.ini` | **idêntico** nos dois lados (`native-lib.cpp:303` vs `main.cpp:718`). Definições sobrevivem. |
| Namespace JNI `Java_kr_co_iefriends_pcsx2_*` | **preservado** no upstream, apesar do `applicationId` `com.armsx2`. |

---

## 7. Savestates: o número confirmado

| | Versão |
|---|---|
| Nosso ([`SaveState.h:28`](../app/src/main/cpp/pcsx2/SaveState.h#L28)) | `0x9A54` |
| Upstream (`pcsx2/SaveState.h:29`) | `0x9A59` |

Cinco versões. **O transplante invalida os savestates de todo utilizador instalado.** Memory cards
sobrevivem.

O argumento do handoff continua de pé e vale repetir porque é contraintuitivo: **a quebra não é
custo do transplante, é custo de convergir.** Qualquer port de commit de core que mexa em savestate
força o mesmo bump. Continuar a portar commit a commit não evita a quebra — dispersa-a por várias
versões, em momentos imprevisíveis. Isso argumenta a favor de fazer a quebra **uma vez, anunciada**.

**Isto continua precisando de decisão do dono do produto, e é a única coisa neste documento que
nenhuma medição resolve.**

---

## 8. Por que os passos 3 e 4 não foram executados

Não por dificuldade técnica escondida, e sim por duas razões nomeáveis:

1. **A cadeia de ferramentas não está instalada** (§4): CMake 3.31.6, Rust com target
   `aarch64-linux-android`, SDK platform 37 e a busca de rede das dependências do shaderc. Instalar
   isso muda o estado da máquina e consome vários GB — é decisão de quem é dono dela, não efeito
   colateral de uma sessão de código.
2. **Mover `app/` para `platforms/android/` não é um spike.** É o projeto. Um spike responde a uma
   pergunta; os passos 3–4, feitos por inteiro, produziriam uma árvore inteira que ninguém pode
   validar sem aparelho e sem a decisão de savestate da §7. Feitos pela metade, produziriam uma
   árvore que não compila e que ninguém saberia dizer se não compila por defeito nosso ou deles.

O que o spike **conseguiu** responder sem eles está nas §3–§6, e responde mais do que o esperado: a
divergência de `getGameCRC` e o custo real da Opção A não sairiam de um build.

### A ordem que a medição sugere

1. ~~Instalar a cadeia de ferramentas e **construir a árvore do upstream limpa**, sem o nosso
   código.~~ **Feito** — §4b. A árvore deles compila.
2. **Decidir Opção A vs B**, agora com o preço da A visível (§6).
3. Reconciliar as 3 divergências de JNI **antes** de qualquer código Java correr, com a de
   `getGameCRC` tratada como bug de corrupção e não como diferença de estilo.
4. Savestates: **preservá-los é viável e barato** — ver
   [`savestates-preservar-no-transplante.md`](savestates-preservar-no-transplante.md). O upstream já
   mantém um leitor de formatos legados (`SaveStateLegacy.cpp`) e o nosso `0x9A54` coincide com a era
   `0x9A34` que ele já lê em três dos quatro blocos de registradores. O aviso no app continua
   necessário, mas passa a ser "seus savestates foram migrados, confira" em vez de "morreram".

---

## 9. Um bônus que continua sendo hipótese

O upstream reescreveu o JIT ARM64 inteiro (`3e077eff9b`, *"Merge yaps2: arm64 JIT transplant"*), e
`pcsx2/arm64` é a área com mais ficheiros tocados. O crash de JIT do Shadow of the Colossus
([bug](bugs/open/sotc-jit-page-fault-addr-12218_2026-08-25T02-18.md)) é candidato a sumir sozinho.

**Hipótese, não promessa.** E agora há uma forma mais barata de a testar antes do transplante: o
detector de valor-veneno da [TASK-0013](task/TASK-0013-detector-valor-veneno-dma.md), que precisa de
dois minutos de jogo e um `adb logcat`.
