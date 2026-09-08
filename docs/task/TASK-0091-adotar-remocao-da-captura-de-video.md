# TASK-0091: adotar a remoção de captura de vídeo do upstream e apagar o nosso contorno local

- **Status:** aberta
- **Criada em:** 2026-09-08
- **Concluída em:** —
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

— (a preencher pela sessão que implementar)
