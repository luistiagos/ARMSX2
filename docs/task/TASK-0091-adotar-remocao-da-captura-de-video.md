# TASK-0091: adotar a remoção de captura de vídeo do upstream e apagar o nosso contorno local

- **Status:** concluída
- **Criada em:** 2026-09-08
- **Concluída em:** 2026-09-11
- **Feature:** [FEAT-0003](../features/FEAT-0003-colheita-upstream-setembro-2026.md)
- **Bugs que resolve:** —
- **Commit:** — (o vínculo é o prefixo `TASK-0091:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

> **Bloco 2 de 5 da FEAT-0003.** Só começar depois que a
> [TASK-0090](TASK-0090-duas-correcoes-de-campo-do-upstream.md) estiver commitada e validada.

## Resumo em uma linha

Esta é a única task da feature que **diminui** o nosso delta em vez de aumentá-lo: o upstream
removeu a captura de vídeo e o ffmpeg de toda a árvore, e nós já tínhamos resolvido o mesmo problema
com um contorno local — que passa a ser desnecessário.

## Por que ela existe

O upstream removeu tudo em **`d04052c442`** — *"GS: remove video capture, and with it the ffmpeg
dependency"*, **188 arquivos, −40.118 linhas**.

O motivo deles: o PCSX2 upstream apagou os headers de ffmpeg da árvore (`cf8d05f4ad`) e subiu o piso
para 7.1 (`d755400af0`). Isso chegou lá pelo merge `48a8f3f0e0` e **quebrou quatro das sete pernas
de CI na configuração**. Só o Android estava protegido — porque só o Android já tinha largado a
funcionalidade.

E o mais relevante: **as outras quatro pernas nunca linkavam ffmpeg de qualquer forma.**
`USE_LINKED_FFMPEG` é OFF por padrão, então o `GSCapture` fazia `dlopen` de `libav*` em tempo de
execução, e o build só queria os headers. Ninguém distribuía as bibliotecas. **A captura já era um
botão morto em tudo que não fosse o Qt desktop.**

### O nosso contorno, que agora sai

Está em [`pcsx2/CMakeLists.txt:638-648`](../../pcsx2/CMakeLists.txt#L638-L648):

```cmake
if(NOT ANDROID)
	list(APPEND pcsx2GSSources GS/GSCapture.cpp)
endif()
```

com stubs inline no `GSCapture.h` para o lado Android. O comentário de lá já antecipava a razão
exata do commit deles: *"Keeping ffmpeg out also stops upstream's 2.9.x header removal from becoming
our problem"*.

Ou seja: **nós e eles chegamos à mesma conclusão, e a deles é a versão que não precisa ser
mantida.** Adotar apaga um pedaço do nosso delta — que é exatamente a regra do `CLAUDE.md`
("correção de motor nasce como contribuição ao upstream, não como edição local") operando a nosso
favor.

## O que o commit deles remove, na íntegra

Vale ler antes de aplicar, porque o alcance é maior que "um arquivo de GS":

- `GSCapture` e seus **~30 call sites**
- o atalho `ToggleVideoCapture`
- o indicador de gravação no OSD **e o contador de desempenho dele**
- o encaminhamento de áudio do **SPU2**
- a ação de menu do Qt e a aba de Media Capture (a aba fica, renomeada, com a metade de screenshot)
- as configurações de captura no `Config`
- a pasta `Videos`
- o build de ffmpeg dos scripts de dependência de macOS, Linux Qt e Windows

**Screenshots não são afetados** — dividiam a aba, mas não o código. **GS dumps também não**: são
gravação de pacotes GIF sob xz/zstd e nunca envolveram ffmpeg.

## Superfície de conflito — medida, não estimada

Dos 188 arquivos do commit, **9 estão no nosso delta**:

| Arquivo | Nosso delta |
|---|---|
| `pcsx2/CMakeLists.txt` | +2 −0 |
| `pcsx2/Config.h` | +8 −0 |
| `pcsx2/GS/GS.cpp` | (no delta) |
| `pcsx2/Pcsx2Config.cpp` | (no delta) |
| `pcsx2/PerformanceMetrics.cpp` | +57 −5 |
| `pcsx2/PerformanceMetrics.h` | +7 −0 |
| `pcsx2/SPU2/spu2.h` | +5 −0 |
| `pcsx2/VMManager.cpp` | +15 −0 |
| `platforms/android/app/src/main/cpp/native-lib.cpp` | (no delta) |

Os deltas nossos são pequenos, mas **`PerformanceMetrics.cpp` é o que mais preocupa**: o commit
deles remove o contador de desempenho da gravação, e nós adicionamos 57 linhas nesse arquivo. **Abra
as duas mudanças antes de resolver o conflito** — não presuma pelo nome que são independentes.

## Escopo

**Entra:**

1. `git cherry-pick d04052c442`, resolvendo os conflitos dos 9 arquivos acima.
2. **Remover o nosso contorno** em `pcsx2/CMakeLists.txt` — o `if(NOT ANDROID)` em volta de
   `GS/GSCapture.cpp` perde o sentido quando o arquivo deixa de existir. Provavelmente o próprio
   cherry-pick já resolve isso ao aplicar o hunk deles; **conferir**, não presumir.
3. Conferir que **nenhum stub órfão** sobra no `GSCapture.h` — o arquivo inteiro sai.

**NÃO entra:**

- **Nada de gravação de tela substituta.** Se alguém quiser gravar, o Android grava melhor do que
  nós gravaríamos daqui dentro — é o argumento deles e vale igual para nós.
- **Mexer nas nossas 57 linhas de `PerformanceMetrics.cpp` além do necessário** para resolver o
  conflito. Se elas precisarem de trabalho próprio, é outra task.
- Os outros blocos da FEAT-0003.

## Como implementar

### 1. Antes de aplicar, abrir o que vai conflitar

Regra do projeto: **não escreva código sobre símbolo que não abriu.** Aqui isso significa, no
mínimo, abrir os nossos hunks nos 9 arquivos:

```bash
MB=$(git merge-base HEAD upstream/master)
git diff $MB..HEAD -- pcsx2/PerformanceMetrics.cpp pcsx2/PerformanceMetrics.h \
    pcsx2/Config.h pcsx2/SPU2/spu2.h pcsx2/VMManager.cpp pcsx2/CMakeLists.txt
git show d04052c442 -- pcsx2/PerformanceMetrics.cpp pcsx2/PerformanceMetrics.h \
    pcsx2/Config.h pcsx2/SPU2/spu2.h pcsx2/VMManager.cpp pcsx2/CMakeLists.txt
```

### 2. Aplicar

```bash
git fetch upstream --prune
git cherry-pick d04052c442
```

Esperar conflito. Resolver **mantendo a remoção deles e preservando as nossas adições** que não
tenham a ver com captura. Se uma adição nossa **depender** de captura, ela sai junto — e isso vai na
seção Resultado.

### 3. Compilar — e aqui o build completo é obrigatório

Diferente da TASK-0090, esta task remove código de **188 arquivos** e mexe em `CMakeLists.txt`.
Compilar objetos isolados não prova nada: o que precisa ser provado é que **o link fecha** sem
`libav*`.

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

Referência medida em 2026-08-26: configuração 120,7 s, build 825 s (13 min 45 s), 1.704 alvos.
**`-j 4` obrigatório.**

> ⚠️ Se este for um worktree novo, as dependências do shaderc **não vêm junto** — são `gitignore`d e
> não são submódulos. Rodar
> `python platforms/android/app/src/main/cpp/3rdparty/shaderc/utils/git-sync-deps` ou copiar as sete
> pastas (264 MB) de uma árvore que já as tenha. Esquecer falha com `SPIRV-Tools was not found`
> minutos depois, na configuração do CMake.

### 4. APK e aparelho

```bash
cd platforms/android
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:installGithubDebug \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

Confirmar que o APK instalado é o novo, comparando o tamanho do `base.apk` do aparelho com o do
arquivo local (ver a TASK-0090, seção 4, para o procedimento).

## Como validar

O risco desta task **não é comportamento novo — é regressão por remoção**. Portanto a validação é
sobre o que **continua** funcionando.

1. **Critério 1 — o build fecha.** `ninja -j 4 emucore_4k` termina com exit 0, e o `emucore_4k.so`
   resultante **não** referencia `libav*`:

```bash
adb shell "grep -c libav /proc/self/maps" # trivialmente 0; o teste de verdade é o abaixo
llvm-readelf --needed-libs <caminho>/libemucore_4k.so | grep -i av
```

Não deve haver nenhuma entrada `libavcodec` / `libavformat` / `libavutil` / `libswscale` /
`libswresample`.

2. **Critério 2 — o app dá boot e roda um jogo.** Um jogo qualquer, 2 minutos, com áudio. O SPU2 foi
   tocado (encaminhamento de áudio da captura removido), então **áudio é parte do teste, não
   detalhe**: confirmar que o som sai e não estala.

3. **Critério 3 — o OSD continua correto.** O indicador de gravação e o contador de desempenho dele
   saíram. Conferir que o OSD com **todos os contadores ligados** desenha sem buraco, sem número
   errado e sem crash. É o ponto onde as nossas 57 linhas de `PerformanceMetrics.cpp` encontram a
   remoção deles.

4. **Critério 4 — screenshots continuam funcionando.** Dividiam a aba de configuração com a captura.
   Tirar uma screenshot pelo app e conferir que o arquivo aparece.

5. **Critério 5 — GS dump continua funcionando.** Nunca envolveu ffmpeg, mas é barato provar:
   disparar um dump e conferir que o arquivo é gerado.

6. **Critério 6 — nenhuma configuração órfã na UI.** Procurar na tela de Configurações por qualquer
   controle de gravação de vídeo que tenha ficado apontando para nada. Se existir um no **nosso**
   lado da UI (Kotlin), ele **também precisa sair** — e isso é parte desta task, não de outra.

```bash
grep -rn "captur\|Captur\|VideoCapture\|videoCapture" platforms/android/app/src/main/java/ | grep -vi screenshot
```

7. **Critério 7 — tamanho do APK.** Registrar o antes e o depois. Não é critério de aprovação, mas é
   o número que torna a task legível depois.

### Testes de regressão

```bash
cd platforms/android
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:testGithubDebugUnitTest \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

E os testes de ctest do core, se estiverem configurados nesta árvore.

## Antes de fechar

```bash
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

Preencher `## Resultado` com: quantos hunks conflitaram e como cada um foi resolvido, a saída do
`readelf`, o tamanho do APK antes/depois, e **o que foi removido do nosso lado da UI**, se algo foi.

## Resultado

> **Os sete critérios passaram, no aparelho, em 2026-09-11.** O cherry-pick aplicou **sem nenhum
> conflito**, o build nativo completo fecha, e o que a remoção poderia ter quebrado — áudio, OSD,
> screenshot, GS dump — continua funcionando e foi medido contra o build anterior, não julgado por
> impressão. **Nada precisou sair da nossa UI Kotlin: o app nunca teve controle de captura de vídeo.**
>
> Uma correção à premissa da task, e ela muda o sentido de "apagar o nosso contorno local": o
> `if(NOT ANDROID)` **não era nosso** — veio de `f06e144f57`, do próprio upstream, já dentro do
> merge-base. O delta que encolheu é o nosso diff contra `upstream/master`: de 773 para **606
> arquivos**, **−40.118 linhas**.

### Veredito por critério (aparelho SM-A127M `RX8R90G1D6E`, 2026-09-11)

| Critério | Veredito | Evidência |
|---|---|---|
| **1** — o build fecha, sem `libav*` | ✅ **passou** | configuração 140 s + `ninja -j 4 emucore_4k` **673,7 s, exit 0**, 1.704 alvos, zero `error:`. `llvm-readelf --needed-libs` só lista bibliotecas do sistema; `--dyn-syms \| grep -icE 'av_\|avcodec\|avformat\|swscale\|swresample'` = **0** |
| **2** — boot e jogo com áudio | ✅ **passou** | *Darwin* rodou **~21 min** no total (boot 16:46:32 → fechamento limpo 17:07:44). A stream do app escreveu **8.893.136 frames** em ~185 s (48 kHz em tempo real) com **XRuns = 0**; nenhum `FATAL`/`SIGSEGV`; `PerfLog` igual ao do build anterior |
| **3** — OSD com todos os contadores | ✅ **passou** | modo **Full** (12 flags + GPU stats): as mesmas linhas do "antes", na mesma ordem, sem buraco e com números plausíveis — em retrato **e** em paisagem |
| **4** — screenshot | ✅ **passou** | `snaps/ARMSX2_1789156819664.png`, **1.160.266 bytes**, PNG válido **960×720**, publicado na galeria; OSD do core: *"Saved screenshot to …"* |
| **5** — GS dump | ✅ **passou** | `The Adventures of Darwin_SLUS-21592_20260911170352.gs.zst`, **393.585 bytes**, mais o PNG companheiro (96.196 bytes); OSD: *"Saved GS dump to …"* |
| **6** — nenhum controle órfão na UI | ✅ **passou** | nada a remover — ver abaixo |
| **7** — tamanho do APK | ✅ **medido** | **93.677.811 → 93.674.068 bytes (−3.743)**; `libemucore_4k.so` empacotada **28.980.712 → 28.980.456 (−256)** |

### Proveniência dos dois APKs

- **"Antes":** o instalado desde o Bloco 1 — 2.0.5 / versionCode 2005, md5
  `31cb514beacc47b771fba32ad8745462`, 93.677.811 bytes, `lastUpdateTime` 15:22:40. Medido **antes**
  de qualquer instalação e puxado do aparelho para servir de par nas comparações de binário.
- **"Depois":** `:app:installGithubDebug` (**BUILD SUCCESSFUL em 2 min 55 s**), md5
  `bd27c7f7bb23a1f4dc417caf671c797a` **idêntico no `outputs/` e no `base.apk` do aparelho**,
  93.674.068 bytes, versionCode 2005, instalado 16:45:17. A única diferença de entrada de build fora
  do commit continua sendo `platforms/android/gradle.properties` (2005 / 2.0.5), que é trabalho do
  usuário e **não foi tocado**.

**O −3.743 bytes é pequeno, e era para ser.** No Android o ffmpeg **nunca** foi linkado:
`USE_LINKED_FFMPEG` é OFF, o `GSCapture` fazia `dlopen` de `libav*` em tempo de execução e o build
só queria os *headers* — que não entram no APK. O que saiu do binário foi o código do `GSCapture` e
seus call sites (o `GSCapture.cpp` nem era compilado aqui desde `f06e144f57`), mais as strings de
configuração. Quem ler "−40.118 linhas" e esperar um APK muito menor está lendo o número errado.

### O cherry-pick: zero conflitos, e por que isso não foi aceito de olhos fechados

`git cherry-pick -x d04052c442` aplicou **sem nenhum conflito** (nenhum hunk a resolver à mão),
sobre o `HEAD 30e9b53d56`. O pai do commit deles é o próprio merge-base (`ce96af5046`), então o
3-way foi exatamente "merge-base × nosso HEAD × pós-commit deles".

A superfície foi **remedida** antes de aplicar, porque a tabela da seção anterior era de 08/09.
Contra o `HEAD` de 11/09 ela tem os mesmos 9 arquivos, mas três deles mudaram de "(no delta)" para
delta nosso:

| Arquivo | Nosso delta (merge-base → HEAD) | Deles | Onde os dois se aproximam |
|---|---|---|---|
| `pcsx2/CMakeLists.txt` | +2 −0 (`GuestPoisonWatch`) | −20 | longe |
| `pcsx2/Config.h` | +8 −0 (`ForcePS2DepthQuantization`) | +1 −25 | mesma lista de bitfields, ~12 linhas acima de `OsdShowVideoCapture` |
| `pcsx2/GS/GS.cpp` | **+15 −2** (`SetGPUTimingAvailable`) | +1 −72 | longe |
| `pcsx2/Pcsx2Config.cpp` | **+4 −0** (`ForcePS2DepthQuantization`) | −46 | longe |
| `pcsx2/PerformanceMetrics.cpp` | +57 −5 | +1 −22 | **adjacente**: o nosso bloco `s_thread_cpu_time_readable` entra logo abaixo da linha `capture_time` que eles apagam, separado dela por uma linha em branco — é por isso que não conflitou |
| `pcsx2/PerformanceMetrics.h` | +7 −0 | −2 | longe |
| `pcsx2/SPU2/spu2.h` | +5 −0 (`SPU2freeze9A54`) | −3 | longe |
| `pcsx2/VMManager.cpp` | +15 −0 (`vu1Thread.Close()`) | −8 | longe |
| `native-lib.cpp` | **+71 −11** | −8 (stubs `Host::OnCapture*`) | ~60 linhas do nosso `OnVMDestroyed` |

Um auto-merge limpo não prova que o resultado está certo, então ele foi conferido de três formas:

1. **Nos 188 arquivos, o `HEAD` difere do pós-commit do upstream apenas nos 9 acima, e exatamente
   pelo tamanho do nosso delta** (+184 −18 = a soma da coluna "Nosso delta"). Nenhuma adição nossa
   se perdeu, nenhuma remoção deles deixou de acontecer.
2. **Em 8 dos 9, o patch aplicado tem o mesmo `git patch-id` do patch do upstream.** O nono,
   `PerformanceMetrics.cpp`, difere só porque as mesmas remoções caíram em linhas deslocadas pelas
   nossas; o diff aplicado foi lido hunk a hunk.
3. **O `PerformanceMetrics::Update()` resultante foi aberto inteiro** (linhas 305-449): o nosso bloco
   lê só `s_cpu_thread_handle` e `cpu_time`, não referencia nada de captura e continua antes do
   cálculo dos deltas. O `#include <utility>` que eles acrescentam é necessário para nós também — o
   arquivo usa `std::exchange`, e o `<utility>` vinha antes por dentro do `GSCapture.h`.

**Nenhuma adição nossa dependia de captura, então nada nosso saiu junto.**

### A premissa que não se confirmou: o "contorno local" era do upstream

O `if(NOT ANDROID)` em volta do `GSCapture.cpp` e os stubs inline do `GSCapture.h` **não eram
nossos**: vieram de `f06e144f57` (jpolo1224, 31/08, *"GS: don't build video capture on Android"*),
que já estava na história do merge-base — nenhum dos dois aparecia no nosso delta. O que a task
queria dizer continua valendo, medido contra `upstream/master`:

| `git diff --shortstat upstream/master HEAD` | Arquivos | Linhas |
|---|---|---|
| antes (`30e9b53d56`) | 773 | +130.985 −31.103 |
| depois (`dcf07cc353`) | **606** | **+90.867** −31.093 |

**167 arquivos e 40.118 linhas a menos de divergência.** O upstream fez em dois passos (primeiro
cercou o Android, depois removeu tudo); nós carregávamos o primeiro passo como se fosse diferença
nossa.

### Quem chamava o que saiu — varredura da árvore inteira, não só dos 9 arquivos

Procurados no `HEAD` todos os símbolos que o commit apaga (`GSCapture`, `GSBeginCapture`,
`GSEndCapture`, `Host::OnCaptureStarted/Stopped`, `OsdShowVideoCapture`, `EnableVideoCapture`,
`CaptureContainer`, `EmuFolders::Videos`, `SPU2::SetAudioCaptureActive`/`IsAudioCaptureActive`,
`GetCaptureThread*`, `GSGetBaseVideoFilename`, `ToggleVideoCapture`, `FFMPEG_INCLUDE_DIRS`,
`USE_LINKED_FFMPEG`, `DeliverAudioPacket`):

- **Lado Kotlin/Java, recursos, assets e manifesto: nenhuma ocorrência.** O `grep` do Critério 6
  devolve 258 linhas, e a única que fala de "captura" fora de screenshot/GS dump é um comentário
  sobre captura de *binding* de controle (`MainActivityRuntime.kt:97`). As 18 chaves `OsdShow*` que
  o `Settings.kt` grava **não** incluem `OsdShowVideoCapture`. **Nada a remover: o critério 6 passa
  por ausência, não por limpeza.**
- **JNI:** os únicos acertos em `native-lib.cpp` eram os stubs vazios `Host::OnCaptureStarted/Stopped`,
  que o próprio commit remove. `NativeApp.java` não declara nada de captura de vídeo; `captureGsDump`
  e `saveScreenshot` são outra coisa — os dois entram por `GSQueueSnapshot`, que o commit não toca.
- **Sobras fora do build Android, que o upstream também tem hoje:** `pcsx2/pcsx2.vcxproj` e
  `.filters` ainda listam `GSCapture.cpp/.h` (projeto MSVC), e
  `platforms/ios/app/src/main/cpp/ARMSX2Bridge.mm:3993` ainda escreve `EmuConfig.GS.OsdShowVideoCapture`
  — **isso não compila no iOS**. Nenhum dos dois é nosso para corrigir (regra do CLAUDE.md); são
  candidatos a patch **no upstream**.
- **`platforms/android/pgo/armsx2.profdata`** contém nomes de função de captura. Só é lido sob
  `USE_PGO_OPTIMIZE`, que já passa `-Wno-error=profile-instr-out-of-date`: função que sumiu apenas
  deixa de casar. Não afeta o build.

### Critério 1 — o build fecha, e o que realmente discrimina

Build nativo completo, **árvore nova** (`D:/DevCaches/armsx2-t91-build`), com a linha exata da seção 3:

| Etapa | Resultado |
|---|---|
| configuração (CMake 3.31.6) | **exit 0, 140 s**, sem erro; o grafo continua com **1.704 alvos** — o `GSCapture.cpp` já não era compilado no Android, então a contagem não muda |
| `ninja -j 4 emucore_4k` | **exit 0, 673,7 s (11 min 14 s)**, zero `error:` |

```
$ llvm-readelf --needed-libs libemucore_4k.so
NeededLibraries [
  libGLESv1_CM.so  libGLESv2.so  libOpenSLES.so  libandroid.so
  libc.so  libdl.so  liblog.so  libm.so  libz.so
]
$ llvm-readelf --dyn-syms libemucore_4k.so | grep -icE 'av_|avcodec|avformat|swscale|swresample'
0
```

**Este teste, sozinho, não discrimina no Android**, e isso precisa ficar escrito: a
`libemucore_4k.so` do APK "antes" dá **a mesma lista** e também 0 símbolos `av_*`. O que separa
antes de depois são as strings de configuração, que o `Pcsx2Config.cpp` e o `GS.cpp` carregavam
mesmo com os stubs:

| String no binário | antes | depois |
|---|---|---|
| `CaptureContainer` | 1 | **0** |
| `EnableVideoCapture` | 2 | **0** |
| `OrganizeVideoCaptureByGame` | 1 | **0** |
| `OsdShowVideoCapture` | 1 | **0** |
| `ToggleVideoCapture` | 1 | **0** |
| `Video Dumping Directory` | 1 | **0** |

Conferido nos dois binários: o de `outputs/` **e** o extraído do APK instalado no aparelho.

Os quatro arquivos que perderam o `#include "GS/GSCapture.h"` — e com ele o `common/Threading.h`
que o stub incluía **só no Android**, dependência transitiva que o CI desktop do upstream nunca
veria — compilaram sem erro: `GS.cpp`, `GSRenderer.cpp`, `ImGuiOverlays.cpp`, `spu2.cpp`.

**ctest: não aplicável nesta árvore.** A opção `ENABLE_TESTS` existe no `BuildParameters.cmake`, mas
o `CMakeLists.txt` do Android não adiciona `tests/`, não há `CTestTestfile.cmake` no build, e os
binários seriam arm64.

### Critério 2 — áudio, e o que "não estala" quer dizer aqui

O SPU2 perdeu o encaminhamento de áudio da captura (`spu2Output` chamava
`GSCapture::DeliverAudioPacket` atrás de `IsAudioCaptureActive()`), então áudio é critério.

| Medida | antes (2.0.5) | depois |
|---|---|---|
| frames escritos pela stream do app | 8.483.536 em ~176,7 s | **8.893.136 em ~185 s** |
| taxa | 48.000 frames/s (tempo real) | **48.000 frames/s** |
| `XRuns` da stream e do endpoint (`dumpsys media.aaudio`) | 0 | **0** |
| underrun/erro de Oboe no logcat | nenhum | **nenhum** |
| `FATAL`/`SIGSEGV` | nenhum | **nenhum** |

**O que não foi feito: ninguém ouviu o som.** A sessão roda por adb; "não estala" foi medido pelos
contadores de underrun do AAudio (0 nos dois lados) e pela taxa de frames entregues, não pelo
ouvido. Se o usuário quiser a confirmação auditiva, ela custa um minuto com o aparelho na mão.

### Critério 3 — o OSD, onde as nossas 57 linhas encontram a remoção deles

Modo **Full** (os 12 flags de `osdApplyFlags` mais `osdShowGpuStats`), ligado pelo seletor
"Exibição na tela" do menu in-game — que aplica ao vivo e **não** mexe nas preferências por-stat.
O OSD desenhou, no build novo, **as mesmas linhas do build anterior, na mesma ordem**: FPS/VPS/
Speed/versão, linha do renderer (`PRIM/DRW/DRWC/BAR/RP/RB/TC/TU`), VRAM/TGT/SRC/HC/PL, QF e
Min/Avg/Max, resolução e modo de vídeo, CPU, GPU, **EE 66,1% (11,03 ms) / GS 26,9% / VU 2,0% /
GPU 9,2%**, VSI/PSI, o gráfico de frame time, a faixa de ajustes e o indicador de inputs.

Sem buraco onde o indicador de gravação e o contador `CAP:` saíram — coerente com o que o código
dizia: os dois eram desenhados sob `GSCapture::IsCapturing()`, que no Android **já** era um stub
`return false`. Repetido em paisagem, com o mesmo resultado.

### Critérios 4 e 5 — screenshot e GS dump

Os dois entram pelo mesmo `GSQueueSnapshot`, que o commit não toca; o que o commit mexeu no
`GSRenderer::VSync` foram as condições `&& !GSCapture::IsCapturingVideo()`, constantes no Android.

- **Screenshot:** `snaps/ARMSX2_1789156819664.png`, 1.160.266 bytes, PNG válido de **960×720** com
  IEND no fim, quadro limpo do emulador (sem OSD e sem overlay de toque, que é o ponto da captura do
  core). Publicado também em `Pictures/ARMSX2/`, mesmo tamanho. Log: *"Saved screenshot to …"*.
- **GS dump:** `The Adventures of Darwin_SLUS-21592_20260911170352.gs.zst`, **393.585 bytes**, mais o
  PNG companheiro de 96.196 bytes. Log: *"Saving single frame GS dump with Zstandard compression"* →
  *"Saved GS dump to …"*.

**Como o botão de screenshot foi alcançado, e o que isso mexeu.** O widget `SHOT` do layout de toque
vem **desligado** por padrão (`TouchControls.kt:1553`, `enabled = false`) e é a única entrada
acionável por adb (as outras são hotkey de pad e tile de segunda tela). Ele foi ligado pelo editor
de layout **em escopo de jogo** — `saveLiveLayoutToActive()` com VM rodando grava **só**
`touch.layout.game.SLUS-21592`, nunca um perfil compartilhado — e depois desfeito com
**Redefinir** (que apaga as duas orientações dessa chave) + **Descartar** (que recarrega o perfil
salvo). Conferido no aparelho: a chave existiu entre 16:59 e 17:06 e **não existe mais**.

### O que mudou no aparelho, e o que voltou

Comparando as preferências do app antes (15:58) e depois (17:08), as 83 chaves batem, com quatro
diferenças:

| Chave | antes | depois | O que é |
|---|---|---|---|
| `playtime.last.SLUS-21592` | 1789151425676 | 1789157165200 | carimbo do próprio app |
| `playtime.secs.SLUS-21592` | 179 | 565 | idem (os ~6 min de teste) |
| `telemetry_last_exit_ts` | 1789151444639 | 1789153430553 | idem |
| `ui.osdMode` | **ausente** | `Custom` | efeito de usar o ciclo do OSD; `Custom` é o valor padrão que "ausente" já significava, então nada mudou de comportamento |

`touch.profiles`, `touch.active` e todo o resto estão **byte a byte iguais**. A rotação do sistema
foi forçada para paisagem durante o teste (o editor de layout não cabe em retrato) e devolvida:
`accelerometer_rotation=1`, `user_rotation=0`, como estava. O jogo foi fechado pelo menu, com
`@@ANDROID_STOP_DONE@@` no log.

**Duas sobras inertes, registradas para não virarem susto depois:**

- O `PCSX2-Android.ini` do aparelho **ainda tem as 18 linhas de captura** (`Videos = videos`,
  `OsdShowVideoCapture`, `CaptureContainer`, `VideoCapture*`, `AudioCapture*`…), inclusive depois de
  o core reescrever o arquivo neste boot (16:46:32). É o comportamento do `SettingsWrapper`: ele
  grava as chaves que conhece e não apaga as que não conhece. Ninguém mais lê nenhuma delas.
- A pasta `files/videos/` continua lá, vazia, de 28/08. O core não a cria mais
  (`EmuFolders::EnsureFoldersExist` perdeu a linha), e apagá-la é decisão de quem cuida do aparelho,
  não desta task.

### Um passo em falso durante a navegação, e por que ele não estragou nada

Um arrasto horizontal na altura da barra de abas (y=94) foi capturado pelo sistema e abriu o
**app de Arquivos** em vez de rolar as abas. Nenhum ajuste foi tocado — foi só troca de app, desfeita
com Voltar. A lição continua sendo a do Bloco 1, agora com um segundo caso: **gesto perto da borda
superior é do sistema, não do app**; rolar a barra de abas exige começar dentro dela (y≈95 funciona
como *swipe* lento) ou, melhor, tocar na aba parcialmente visível.

### Compilação, testes e validador

| Etapa | Resultado |
|---|---|
| build nativo completo (árvore nova) | **exit 0**, 140 s + 673,7 s, 1.704 alvos |
| pré-build da árvore `.cxx/Debug` do AGP (`ninja -j 4`, 78 alvos com saída) | **exit 0, 4 min 45 s** |
| `:app:installGithubDebug` | **BUILD SUCCESSFUL em 2 min 55 s**, zero `Building CXX` (o nativo já estava pronto) |
| `:app:testGithubDebugUnitTest` | **BUILD SUCCESSFUL, 21 s — 8 classes, 42 testes, 0 falhas, 0 erros, 0 ignorados** |
| `check_traceability.py` | `OK -- 96 task(s), 3 feature(s)` |
| `check_traceability.py --commits upstream/master..HEAD` | `OK -- … 167 commit(s)` |

> ⚠️ **Uma armadilha nova, para quem repetir o pré-build da árvore do AGP:** a lista de alvos do
> `android_gradle_build_mini.json` inclui alvos **utilitários** do zstd — `clean-all` e `uninstall`.
> O `clean-all` roda `ninja clean && cmake -E remove_directory <a árvore inteira>`. Pedir a lista
> crua ao ninja dispara isso; aqui ele falhou no primeiro comando do `&&` (não há `build.ninja`
> naquele subdiretório) e **nada foi apagado**, mas a margem era essa. Filtre por alvos que têm
> `output` (78 dos 113) antes de passar ao ninja.

### O que foi commitado

| Commit | Autor | Assunto |
|---|---|---|
| `dcf07cc353` | Brian Degenhardt | `TASK-0091: remove a captura de video e, com ela, a dependencia do ffmpeg` |

`git cherry-pick -x` preservou a autoria do upstream e a linha `(cherry picked from commit …)`;
só o assunto foi reescrito, para levar o prefixo que o validador exige. O `--amend` que reescreveu a
mensagem **não mudou a árvore** (mesmo `tree` antes e depois).

### O cherry-pick: zero conflitos, e por que isso não foi aceito de olhos fechados

`git cherry-pick -x d04052c442` aplicou **sem nenhum conflito** (nenhum hunk a resolver à mão),
sobre o `HEAD 30e9b53d56`. O pai do commit deles é o próprio merge-base (`ce96af5046`), então o
3-way foi exatamente "merge-base × nosso HEAD × pós-commit deles".

A superfície foi **remedida** antes de aplicar, porque a tabela acima era de 08/09. Contra o `HEAD`
de 11/09 ela tem os mesmos 9 arquivos, mas três deles mudaram de "(no delta)" para delta nosso:

| Arquivo | Nosso delta (merge-base → HEAD) | Deles | Onde os dois se aproximam |
|---|---|---|---|
| `pcsx2/CMakeLists.txt` | +2 −0 (`GuestPoisonWatch`) | −20 | longe |
| `pcsx2/Config.h` | +8 −0 (`ForcePS2DepthQuantization`) | +1 −25 | mesma lista de bitfields, ~12 linhas acima de `OsdShowVideoCapture` |
| `pcsx2/GS/GS.cpp` | **+15 −2** (`SetGPUTimingAvailable`) | +1 −72 | longe |
| `pcsx2/Pcsx2Config.cpp` | **+4 −0** (`ForcePS2DepthQuantization`) | −46 | longe |
| `pcsx2/PerformanceMetrics.cpp` | +57 −5 | +1 −22 | **adjacente**: o nosso bloco `s_thread_cpu_time_readable` entra logo abaixo da linha `capture_time` que eles apagam, separado dela por uma linha em branco |
| `pcsx2/PerformanceMetrics.h` | +7 −0 | −2 | longe |
| `pcsx2/SPU2/spu2.h` | +5 −0 (`SPU2freeze9A54`) | −3 | longe |
| `pcsx2/VMManager.cpp` | +15 −0 (`vu1Thread.Close()`) | −8 | longe |
| `native-lib.cpp` | **+71 −11** | −8 (stubs `Host::OnCapture*`) | ~60 linhas do nosso `OnVMDestroyed` |

Como um auto-merge limpo não prova que o resultado está certo, o resultado foi conferido de três
formas:

1. **Nos 188 arquivos, o `HEAD` difere do pós-commit do upstream (`d04052c442`) apenas nos 9
   arquivos acima, e exatamente pelo tamanho do nosso delta** (+184 −18 = a soma da coluna "Nosso
   delta"). Nenhuma adição nossa se perdeu, nenhuma remoção deles deixou de acontecer.
2. **Em 8 dos 9, o patch aplicado tem o mesmo `git patch-id` do patch do upstream.** O nono,
   `PerformanceMetrics.cpp`, difere só porque as mesmas remoções caíram em linhas deslocadas pelas
   nossas; o diff aplicado foi lido hunk a hunk.
3. **O `PerformanceMetrics::Update()` resultante foi aberto inteiro** (linhas 305-449): o nosso bloco
   lê só `s_cpu_thread_handle` e `cpu_time`, não referencia nada de captura, e continua antes do
   cálculo dos deltas. O `#include <utility>` que eles acrescentam é necessário para nós também — o
   arquivo usa `std::exchange` e o `<utility>` vinha antes, transitivamente, do `GSCapture.h`.

**Nenhuma adição nossa dependia de captura, então nada nosso saiu junto.**

### Uma premissa da task que não se confirmou: o "contorno local" era do upstream

O `if(NOT ANDROID)` em volta do `GSCapture.cpp` e os stubs inline do `GSCapture.h` **não são
nossos**: vieram de `f06e144f57` (jpolo1224, 31/08, *"GS: don't build video capture on Android"*),
que já está na história do merge-base. Nenhum dos dois aparecia no nosso delta. O que a task queria
dizer continua valendo, só que medido do jeito certo — contra `upstream/master`:

| `git diff --shortstat upstream/master HEAD` | Arquivos | Linhas |
|---|---|---|
| antes (`30e9b53d56`) | 773 | +130.985 −31.103 |
| depois (`dcf07cc353`) | **606** | **+90.867** −31.093 |

**167 arquivos e 40.118 linhas a menos de divergência.** O upstream fez em dois passos (primeiro
cercou o Android, depois removeu tudo); nós carregávamos o primeiro passo como se fosse diferença.

### Quem chamava o que saiu — varredura da árvore inteira, não só dos 9 arquivos

Procurados no `HEAD` todos os símbolos que o commit apaga (`GSCapture`, `GSBeginCapture`,
`GSEndCapture`, `Host::OnCaptureStarted/Stopped`, `OsdShowVideoCapture`, `EnableVideoCapture`,
`CaptureContainer`, `EmuFolders::Videos`, `SPU2::SetAudioCaptureActive`/`IsAudioCaptureActive`,
`GetCaptureThread*`, `GSGetBaseVideoFilename`, `ToggleVideoCapture`, `FFMPEG_INCLUDE_DIRS`,
`USE_LINKED_FFMPEG`, `DeliverAudioPacket`):

- **Lado Kotlin/Java (`platforms/android/app/src/main/java`), recursos, assets e manifesto:
  nenhuma ocorrência.** O app nunca expôs captura de vídeo. As 18 chaves `OsdShow*` que o
  `Settings.kt` grava não incluem `OsdShowVideoCapture`.
- **JNI:** os únicos acertos em `native-lib.cpp` eram os stubs vazios `Host::OnCaptureStarted/Stopped`,
  que o próprio commit remove. `NativeApp.java` não declara nada de captura de vídeo;
  `captureGsDump` e `saveScreenshot` são outra coisa (ambos entram por `GSQueueSnapshot`).
- **Fora do build Android, sobras que o upstream também deixou** (estão iguais em `upstream/master`
  hoje): `pcsx2/pcsx2.vcxproj` e `.filters` ainda listam `GSCapture.cpp/.h` (projeto MSVC), e
  `platforms/ios/app/src/main/cpp/ARMSX2Bridge.mm:3993` ainda escreve
  `EmuConfig.GS.OsdShowVideoCapture` — isso **não compila no iOS**. Nenhum dos dois é nosso para
  corrigir (regra do CLAUDE.md); são candidatos a patch no upstream.
- **`platforms/android/pgo/armsx2.profdata`** contém nomes de função de captura. Só é lido com
  `USE_PGO_OPTIMIZE`, que já passa `-Wno-error=profile-instr-out-of-date`: função que sumiu só deixa
  de casar. Não afeta o build.

### Critério 1 — o build fecha

Build nativo completo, **árvore nova** (`D:/DevCaches/armsx2-t91-build`), exatamente a linha da
seção 3:

| Etapa | Resultado |
|---|---|
| configuração (CMake 3.31.6) | **exit 0, 140 s**, sem erro; o grafo continua com **1.704 alvos** (o `GSCapture.cpp` nunca era compilado no Android, então a contagem não muda) |
| `ninja -j 4 emucore_4k` | **exit 0, 673,7 s (11 min 14 s)**, zero `error:` no log |

`llvm-readelf --needed-libs libemucore_4k.so`:

```
NeededLibraries [
  libGLESv1_CM.so
  libGLESv2.so
  libOpenSLES.so
  libandroid.so
  libc.so
  libdl.so
  liblog.so
  libm.so
  libz.so
]
```

`grep -i av` nessa lista: vazio (exit 1). **Nenhum `libavcodec`/`libavformat`/`libavutil`/
`libswscale`/`libswresample`.**

**Mas este teste, sozinho, não discrimina no Android** — e isso precisa ficar escrito: a
`libemucore_4k.so` do APK "antes" (2.0.5, md5 `31cb514b…`) dá **a mesma lista**, porque o Android
já não compilava o `GSCapture.cpp`. O que distingue antes de depois são as strings de configuração
de captura, que o `Pcsx2Config.cpp` e o `GS.cpp` carregavam mesmo com os stubs:

| String no binário | antes | depois |
|---|---|---|
| `CaptureContainer` | 1 | 0 |
| `EnableVideoCapture` | 2 | 0 |
| `OrganizeVideoCaptureByGame` | 1 | 0 |
| `OsdShowVideoCapture` | 1 | 0 |
| `ToggleVideoCapture` | 1 | 0 |
| `Video Dumping Directory` | 1 | 0 |
| símbolos `GSCapture*` / `*Capture*` de captura (`llvm-nm -C`) | 0 | 0 |
| strings `libav*`/`libsw*`/`ffmpeg` | 0 | 0 |

**A remoção está no binário.**

Os quatro arquivos que perderam o `#include "GS/GSCapture.h"` — e, com ele, o `common/Threading.h`
que o stub incluía **só no Android** (dependência transitiva que o CI desktop do upstream nunca
veria) — compilaram sem erro: `GS.cpp`, `GSRenderer.cpp`, `ImGuiOverlays.cpp`, `spu2.cpp`.

**ctest:** não configurado nesta árvore. A opção `ENABLE_TESTS` existe no `BuildParameters.cmake`,
mas o `CMakeLists.txt` do Android não adiciona `tests/`, não há `CTestTestfile.cmake` no build, e os
binários seriam arm64. Não aplicável.

(pendente: APK, aparelho, critérios 2-7)
