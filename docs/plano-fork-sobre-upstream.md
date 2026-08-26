# Plano: virar um fork da árvore Android do upstream

- **Criado em:** 2026-08-26
- **Este documento é auto-contido.** Foi escrito para ser lido numa sessão nova, sem contexto
  anterior. Onde ele depende de outro documento, o link diz exatamente o que ir buscar lá.

---

## 1. A decisão, em uma frase

**Parar de manter um app próprio que consome um core portado, e passar a ser um fork da árvore
Android do `ARMSX2/ARMSX2` com um conjunto pequeno de mudanças por cima.**

A base passa a ser a deles. Atualizar deixa de ser "portar commits" e vira `git merge
upstream/master` com um delta conhecido.

### Por que, em três números medidos

1. **~85% do nosso app já existe lá.** Das nossas 24.096 linhas de Java, a maior parte é tela,
   controle, conquistas, driver de GPU e idioma — todos com equivalente na árvore deles, muitas
   vezes mais completo (21 áreas de UI contra as nossas 6 Activities).
2. **O delta real é ~3.757 linhas** de lógica genuinamente nossa (§4).
3. **A árvore deles compila**, medido em 2026-08-26: 825 s a `-j 4`, exit 0. Ver
   [`spike-transplante-upstream-2026-08-26.md`](spike-transplante-upstream-2026-08-26.md) §4b.

### O que esta decisão NÃO resolve

O acoplamento do core. Nossas correções de motor (caminho gráfico em Mali) continuarão sendo
necessárias e continuarão tocando arquivos que o upstream também mantém. Medido: em duas semanas,
22 arquivos compartilhados. A regra que resolve isso é de processo, não de estrutura: **toda
correção de motor nasce como contribuição ao upstream, não como edição local.** O transplante dá um
ponto de partida limpo; sem essa regra, ele suja de novo na mesma velocidade.

---

## 2. Ponto de partida

### Qual versão deles

**`upstream/master`.** Não existe branch de desenvolvimento separada — `master` é a linha de
desenvolvimento, e o app Android vive em `platforms/android/`. Verificado em 2026-08-26:

```
662b114168  refs/heads/master          <- usar esta
bb9df22fc9  refs/heads/cache-dxstg-followups
b09a0bac75  refs/heads/jni-thread-ownership
112bc73c4c  refs/heads/memcard-rollback
e1f8fb1c56  refs/heads/pergame-settings-precedence
b9c18658be  refs/heads/revert-523-feat/unified-number-row
```

As outras são branches de feature. `jni-thread-ownership` pode ser relevante para nós — olhar antes
de começar, não durante.

> `git fetch --depth 100 upstream master` traz 100 commits. Não há merge-base com o upstream, então
> **não existe contagem exata** de quão atrás estamos; "~1.050 commits" é ordem de grandeza.

### Branch de trabalho

Branch nova, **preservando a árvore atual intacta**. A branch `feature/handoff-end-to-end` (e a
`main`) continuam como estão — se o fork não der certo, nada foi perdido.

```powershell
git checkout -b feature/fork-upstream-android
```

### Já existe uma árvore configurada e compilável

```
D:/projects/play2/ARMSX2-upstream-spike     # git worktree, detached em 662b114168
D:/projects/play2/ARMSX2-upstream-spike/.spike-build
```

2,6 GB. **Não apagar** — a configuração de CMake já rodou e o build já passou. Para remover, se um
dia for preciso: `git worktree remove --force D:/projects/play2/ARMSX2-upstream-spike`.

### Cadeia de ferramentas — já instalada nesta máquina

| Item | Estado |
|---|---|
| NDK `28.2.13676358` | **instalado** |
| CMake `3.31.6` | **instalado** (o 3.22.1 do AGP **falha** ao configurar spirv-tools) |
| Dependências do shaderc | **buscadas** (`python3 app/src/main/cpp/3rdparty/shaderc/utils/git-sync-deps` — não são vendoradas nem submódulos) |
| Rust / cargo | **não instalado, e não é preciso** — sem cargo o librashader se desliga sozinho e o build segue |
| SDK platform 37 | **não instalado.** `platforms;android-37` não existe no repositório que o `cmdline-tools` desta máquina enxerga (ele entende SDK XML até v3; o repositório está em v4). **Atualizar o `cmdline-tools` é pré-requisito para o build Gradle/APK** — o build nativo não precisa. |

Comando que fez o build nativo passar, para repetir:

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

`-DLTO_PCSX2_CORE=OFF` foi deliberado — o release deles liga LTO, que encarece o link e não muda a
resposta de "compila?". **Um build com LTO ligado ainda não foi medido.**

---

## 3. O que a árvore deles já dá (e que NÃO deve ser reimplementado)

Verificado por busca de conceito, não por nome de arquivo. Se você se pegar reescrevendo algo desta
lista, pare e procure na árvore deles primeiro.

| Nosso | Deles |
|---|---|
| 6 Activities + 115 XML | 21 áreas de UI em Compose: bios, achievements, controls, emulation, friends, home, language, memorycards, news, onboarding, patches, saves, settings, settingshub, textures, about, in-game overlay, quick menu |
| `hid/` (1.702) + `input/` (1.697) + `SDLControllerManager` (785) | 62 arquivos tratando SDL/controller |
| `RetroAchievementsBridge` (308) | 27 arquivos |
| `GpuDriverHelper` (553) + `GpuDriverMetadata` (220) | 11 arquivos, via adrenotools |
| `LocaleHelper` (73) | 24 arquivos + 1.768 linhas de i18n |
| — | memory cards, savestates, patches, texture packs: **não temos, ganhamos** |

---

## 4. O que É genuinamente nosso — a lista de reimplementação

**~3.757 linhas.** Esta é a lista de trabalho. Cada item tem o caminho no repositório atual, para
consulta durante a reescrita.

### 4.1. Catálogo de ROMs + fila de download — 1.697 linhas

O maior diferenciador. **Nenhum equivalente na árvore deles** (o `TextureCatalog.kt` deles é de
texture packs, não de ROMs).

| Arquivo | Linhas | Natureza |
|---|---|---|
| `catalog/CatalogActivity.java` | 479 | **tela** — reescrever em Compose |
| `catalog/CatalogDownloadActivity.java` | 161 | **tela** — reescrever em Compose |
| `catalog/RomDownloadManager.java` | 491 | lógica — porta quase direto |
| `catalog/DownloadQueueManager.java` | 205 | lógica — porta quase direto |
| `catalog/DownloadForegroundService.java` | 178 | serviço Android — porta quase direto |
| `catalog/CatalogParser.java` | 146 | lógica — porta direto |
| `catalog/CatalogEntry.java` | 37 | modelo — porta direto |

Especificações já escritas, **ler antes de reimplementar**:
[`ROM_CATALOG_DOWNLOAD.md`](ROM_CATALOG_DOWNLOAD.md) e [`download-queue.md`](download-queue.md).

### O manifesto — resolvido, copiar como está

`catalog_manifest_ps2.txt` (926 KB, 12.628 entradas). Vive em dois lugares, **byte a byte
idênticos** e ambos rastreados desde a [TASK-0015](task/TASK-0015-manifesto-catalogo-curado.md):

| Caminho | Papel |
|---|---|
| `catalog_manifest_ps2.txt` (raiz) | fonte que `sort_manifest.py` lê |
| `app/src/main/assets/catalog_manifest_ps2.txt` | o que vai no APK |

**A ordenação é curada e deve ser preservada tal como está.** São dois blocos, cada um alfabético:

| Bloco | Linhas | Conteúdo |
|---|---|---|
| 1 | 1–1780 | entradas **com capa**: 1.779 `.chd` com URL + **um `.iso` promovido à mão** (`PS2-Super Bomba Patch 2026`) |
| 2 | 1781–12628 | todo o resto, com e sem URL misturados |

Aquele `.iso` promovido importa: ele prova que o bloco 1 **não** é derivável de uma regra pura sobre
o conteúdo da linha. `sort_manifest.py` já foi corrigido para aprender a curadoria do próprio
arquivo em vez de re-derivá-la — a versão anterior a destruía em silêncio. Ao levar para a árvore
nova, **copiar os dois arquivos e o script juntos**; não regerar a ordem.

### 4.2. Telemetria `/logErr` — 993 linhas

**Zero arquivos da árvore deles** citam telemetria, crash report ou `ApplicationExitInfo`. É
inteiramente nosso, e é o que torna um relato de campo diagnosticável.

| Arquivo | Linhas | Nota |
|---|---|---|
| `utils/CrashReporter.java` | 419 | handler de crash + watchdog de ANR + recuperação de `ApplicationExitInfo` |
| `utils/TombstoneParser.java` | 289 | decodifica tombstone protobuf (Android 12+) e texto (Android 11) |
| `utils/TelemetryReporter.java` | 285 | envio ao `/logErr`, dedup por sessão, kill-switch |

Tem teste: `app/src/test/.../TombstoneParserTest.java`.

Depende de dois ganchos no lado C++ (§4.6).

### 4.3. Atualização in-app pelo nosso canal — 475 linhas

Eles têm updater próprio (`UpdaterEntry.kt`, flavors `github`/`play`), mas é outro mecanismo: não lê
o nosso `version.json` nem conhece o nosso canal.

| Arquivo | Linhas |
|---|---|
| `updates/AppUpdateManager.java` | 440 |
| `updates/UpdateInstallReceiver.java` | 35 |

Contrato a preservar (detalhes em [`../CLAUDE.md`](../CLAUDE.md)):

- Endpoint: `https://versions.digitalstoregames.com/rgs/ps2/version.json`
  (`BuildConfig.APP_UPDATE_ENDPOINT`)
- Canal: `"default"` (`BuildConfig.APP_UPDATE_CHANNEL`) — o `version.json` tem de casar, senão o app
  rejeita
- Compara `versionCode` contra `BuildConfig.VERSION_CODE`; download com resume; **verifica o SHA-256
  do `version.json`**; instala por `PackageInstaller`, com Intent+FileProvider de fallback
- Throttle de 12 h; "Depois" grava o `versionCode` pulado nas SharedPrefs `armsx2`

> 🔴 **Este é o item que pode deixar cliente órfão.** Um usuário instalado que atualizar para uma
> versão sem este mecanismo nunca mais recebe atualização. Ele tem de estar funcionando **antes** da
> primeira publicação do fork, não depois.

### 4.4. Diagnóstico de saúde gráfica — 307 linhas

`utils/GraphicsHealthMonitor.java`. Amostra 32×32 do `SurfaceView`, classifica vermelho dominante e
preto uniforme, e reporta à telemetria com a linha `GSBoot` anexada.

**É diagnóstico e só.** Ele já trocou renderer sozinho e isso foi removido na TASK-0005, porque a
heurística decide sem saber o driver e o caminho de ação chega em `abort()`. Ao reimplementar,
**não** ressuscitar a troca automática. Há um teste que varre o fonte para impedir isso:
`GraphicsHealthMonitorTest.monitorNeverChangesTheRenderer`.

### 4.5. Coletor de adaptadores de rede — 285 linhas

`utils/NetworkAdapterCollector.java`. Zero equivalente do lado deles.

### 4.6. Ganchos no C++ que sustentam o acima

O que sobra de genuinamente nosso no core, depois que o resto vem de graça:

| Gancho | Onde | Para quê |
|---|---|---|
| `Host::ReportGraphicsBootDiagnostics` | `pcsx2/Host.h` + `main.cpp` | a linha `GSBoot` (GPU, driver, versão, flags) fora do gate de `Log::GetMaxLevel()` |
| `NativeApp.onGraphicsBootDiagnostics` | ponte JNI | entrega a linha ao TelemetryReporter |
| `Host::ReportErrorAsync` → diálogo | `main.cpp` | sem isso, toda falha do emulador vira tela preta silenciosa |
| `DebugTools/GuestPoisonWatch` | `pcsx2/Hw.cpp` | detector do crash do SotC — **temporário por construção**, some quando o bug fechar |

**O que NÃO precisa ser reimplementado no core**, porque já está lá ou é desnecessário:

- Perfil de GPU + banco de 27 regras de driver (TASK-0002) — **veio deles**
- Assinatura de driver no `GLShaderCache` (TASK-0003) — **veio deles**
- `DecideGLFramebufferFetch` e o fim da regra de MGS3 por título (TASK-0005) — **é o desenho deles**
- Port do MFIFO/SPR (TASK-0008) — **veio deles**
- Precisão GLES no shader CAS (TASK-0007) — **provavelmente desnecessária**: o
  `CreateCASPrograms()` deles não tem ramo GLES. Confirmar antes de descartar.

---

## 5. Identidade do produto — o que não pode mudar

Renomear qualquer coisa desta seção quebra instalação existente.

| Item | Valor | Como preservar na árvore deles |
|---|---|---|
| `applicationId` | `come.nanodata.armsx2` | **É propriedade de linha de comando**: `-Parmsx2.applicationId` no `build.gradle.kts` deles (default `com.armsx2`). Não precisa tocar em fonte. ⚠️ O `come` é grafia herdada, **não é typo a corrigir** — corrigir quebra toda instalação. |
| Nome do `.ini` | `PCSX2-Android.ini` | **idêntico nos dois lados**, verificado. Definições sobrevivem. |
| Namespace JNI | `Java_kr_co_iefriends_pcsx2_*` | **preservado no upstream**, apesar do `applicationId` `com.armsx2` |
| SharedPreferences | arquivo `armsx2`; também `controller_mode`, `hidapi` | ⚠️ **o deles chama-se `ARMSX2`** — ver o aviso abaixo |
| Caminhos de dados | via `DataDirectoryManager` | ⚠️ ver §6 — colisão de modelo |

> 🔴 **O arquivo de SharedPreferences difere só na CAIXA, e isso não falha em lugar nenhum.**
> Nós gravamos em `getSharedPreferences("armsx2", ...)`; eles, em `"ARMSX2"`
> (`BootSplashActivity.kt:33`). Nomes de SharedPreferences são **nomes de arquivo** e portanto
> case-sensitive no Android: `armsx2.xml` e `ARMSX2.xml` são dois arquivos distintos. Ao atualizar
> da 1.0.23 para o fork, tudo que o usuário tinha lá fica invisível — não some do disco, o app novo
> simplesmente olha para o outro arquivo e vê tudo vazio.
>
> Seis pontos do nosso app usavam esse arquivo. O mais grave é o `DataDirectoryManager`, que guarda
> **a raiz de dados escolhida**: sem migração, o app novo procura jogos e saves no lugar padrão. Os
> outros são o `versionCode` pulado do updater, o ícone alternativo, as decisões do monitor gráfico
> e alguns toggles de UI.
>
> A boa notícia: a maior parte das configurações **não** está aqui. Vai por `NativeApp.setSetting()`
> para o `PCSX2-Android.ini`, que tem o mesmo nome nos dois lados e sobrevive intacto.
>
> A migração pertence à etapa em que cada módulo for reimplementado, não à identidade.

### O que muda de propósito (identidade visual e textual)

| Item | Onde está hoje |
|---|---|
| Nome do app | `res/values/strings.xml`: `app_name` = **RetroSystem PS2** (+ `app_name_tv`, `app_name_desktop`) |
| Ícone | `res/mipmap-*/ic_launcher.png` e `ic_launcher_round.png` (5 densidades) + `drawable/ic_launcher_{background,foreground,monochrome}.xml` |
| Ícones alternativos | `utils/AppIconManager.java` (355 linhas) + `layout/include_settings_card_app_icon.xml` — o usuário escolhe o ícone do launcher |
| Textos | **471 strings** em `res/values/strings.xml`. A árvore deles tem i18n próprio (1.768 linhas) — decidir se adotamos o mecanismo deles e traduzimos, ou mantemos o nosso |
| User-Agent | `"Mozilla/5.0 (Android) RetroSystemPS2/1.0"` em `HomeActivity` e `CatalogActivity` |
| Discord Rich Presence | display name "RetroSystem PS2" (`utils/DiscordBridge.java`) — eles têm `discord/` (490 linhas), reconciliar |
| Sobre / créditos | `MainActivity` linhas ~3143 e ~3832 |
| `versionName` / `versionCode` | `app/build.gradle`: hoje `1.0.23` / `37`. **Sempre incrementar o `versionCode`** antes de publicar |

---

## 6. Os três riscos que precisam de decisão antes do código

### 6.1. Modelo de pastas: `initialize` tem 3 parâmetros lá, 2 aqui

```cpp
// nosso
void Java_..._NativeApp_initialize(JNIEnv*, jclass, jstring path, jint apiVer);
// deles
void Java_..._NativeApp_initialize(JNIEnv*, jclass, jstring path, jstring biosFolder, jint apiVer);
```

Não é um parâmetro a mais — são **duas respostas concorrentes ao mesmo problema**, colidindo na
primeira chamada nativa que o app faz. Eles fixam a BIOS em `externalFilesDir/bios` porque o
systemDir escolhido pelo usuário não hospeda BIOS sob scoped storage no Android 11+. Nós resolvemos
o mesmo com `DataDirectoryManager.getDataRoot()` + `reloadDataRoot`.

**Adotar o modelo deles** é a recomendação — é a base, e o assistente de configuração deles depende
disso. Mas exige um caminho de migração para os usuários que já têm dados na nossa estrutura.

### 6.2. `getGameCRC` retorna tipos diferentes

```cpp
// nosso    main.cpp:686
JNIEXPORT jint    JNICALL Java_..._NativeApp_getGameCRC(JNIEnv*, jclass);
// deles    native-lib.cpp:524
JNIEXPORT jstring JNICALL Java_..._NativeApp_getGameCRC(JNIEnv*, jclass);
```

**JNI liga por nome, não por assinatura.** Mesmo nome com tipo de retorno diferente compila, linka e
roda — o `int` do Java receberia os 32 bits baixos de um ponteiro. Não crasha; produz CRC errado em
silêncio, e o CRC alimenta busca de capas, overrides de GameDB e a chave do `GraphicsHealthMonitor`.

Adotando o app deles, isso desaparece (o Kotlin deles já casa com o nativo deles). **Mas o nosso
código de catálogo usa CRC** — ao reimplementar, usar a forma deles (`jstring` hexadecimal).

Reconferir a superfície inteira antes de escrever Java:

```bash
python scripts/compare_jni_surface.py app/src/main/cpp/main.cpp <upstream>/native-lib.cpp
```

Última medição (26/08, contra `662b114168`): 56 nossos, 143 deles, 33 comuns → **30 idênticos, 3
divergentes**, 23 só nossos, 110 só deles.

### 6.3. Savestates: `0x9A54` → `0x9A59`

**Preservá-los é viável e barato.** Análise completa em
[`savestates-preservar-no-transplante.md`](savestates-preservar-no-transplante.md). Resumo:

- Dos 56 arquivos que participam da serialização, **54 têm sequência de wire idêntica**. Os 2 que
  diferem são renomeação (`Sio2`) e um struct de wire que existe para o formato **não** mudar
  (`fpuRegs`, 264 bytes nos dois lados).
- A única incompatibilidade real é o alargamento dos contadores de ciclo de `u32` para `u64`.
- O upstream **já mantém** `pcsx2/SaveStateLegacy.cpp` (1.168 linhas) que lê formatos antigos, e o
  caso difícil já está resolvido lá: `WidenCycle()`, que trata a volta do contador de 32 bits — uma
  extensão-com-zero ingênua erraria, e erraria com frequência (a volta acontece a cada ~15 s).
- **O nosso formato é quase o `0x9A34` que eles já leem**: `cpuRegs` campo por campo idêntico
  (1008 bytes), bloco `Cycles` idêntico (24 bytes), `fpuRegs` idêntico. Só o `psxRegs` difere, por
  um `u32 iopCycleEECarry` inserido — campo que a versão nova ainda tem, mapeamento 1:1.

**Trabalho:** acrescentar `0x9A54` ao `IsSupportedVersion`, declarar três structs `_9A54` (dois são
alias do `_9A34`), tornar `SupportsLegacy()` por-versão para PAD/USB, e **validar byte a byte contra
savestates reais da 1.0.23** — validação barata, não depende de aparelho.

Ainda assim, avisar no app antes de publicar: o leitor pode não carregar tudo. Mas o aviso passa a
ser "seus savestates foram migrados, confira antes de apagar o memory card" e não "morreram".

---

## 7. Ordem de execução sugerida

Cada etapa termina em algo verificável.

1. **Branch nova + árvore deles dentro dela.** Sem nenhum código nosso ainda. Critério: o build
   nativo passa (§2).
2. **Build do APK deles, sem modificação.** Exige atualizar o `cmdline-tools` e instalar
   `platforms;android-37`. Critério: APK instala e abre num aparelho.
3. **Identidade** (§5): `applicationId`, nome, ícone, strings. Critério: instala **por cima** de uma
   1.0.23 existente sem desinstalar, e as definições do `PCSX2-Android.ini` sobrevivem.
4. **Telemetria** (§4.2) + os ganchos de `Host::` (§4.6). Critério: um crash provocado chega ao
   `/logErr` com tombstone decodificado.
5. **Updater** (§4.3). Critério: o app detecta uma versão nova no `version.json` real e instala.
   **Não publicar nada antes disto funcionar.**
6. **Catálogo + fila de download** (§4.1), em Compose. Critério: baixar uma ROM do catálogo de ponta
   a ponta, com a fila sobrevivendo a fechar o app.
7. **Monitor de saúde gráfica** (§4.4) + `NetworkAdapterCollector` (§4.5).
8. **Savestates** (§6.3).
9. **Publicar**, com aviso de savestate.

Só depois de 5 é seguro publicar. Antes disso, qualquer build é interno.

---

## 8. Regras de processo que continuam valendo

Nada abaixo muda por causa do fork.

> **Nenhum commit em `app/src/`, `scripts/` ou arquivos de build sem uma task em `docs/task/` que o
> descreva. Uma task = um commit. O agente é quem commita.**

Antes de todo push: `python scripts/check_traceability.py`.

A exceção `chore:` cobre só o que **não roda no aplicativo** — README, docs, formatação. **Não**
cobre `app/src/`.

Especificação completa em [`README.md`](README.md).

> ⚠️ Nesta sessão o `--fix` do validador anunciou "9 linhas atualizadas" e deixou 3 tasks fora do
> índice, além de manter `aberta` em linhas que já tinham commit. Conferir o índice à mão depois de
> rodá-lo. Registrado em
> [`bugs/open/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md`](bugs/open/checktraceability-fix-nao-insere-task-ausente-do-indice_2026-08-25T22-44.md);
> conserto planejado na [TASK-0010](task/TASK-0010-corrigir-validador-rastreabilidade.md).

---

## 9. O que fica para trás, e onde encontrá-lo

A branch atual **não é apagada**. Se o fork não vingar, ela continua sendo o produto.

| O quê | Onde |
|---|---|
| Nosso app completo, 1.0.23 | branches `main` e `feature/handoff-end-to-end` |
| Trabalho desta sessão (11 commits, TASK-0004/0005/0012/0013/0014 + spikes) | `feature/handoff-end-to-end`, **não empurrada** |
| 2 commits herdados, também não empurrados | `feature/sync-upstream-oficial` |

### Validações de campo que ficaram pendentes e continuam valendo

Independem do fork — são sobre a 1.0.23 que está com os clientes:

| O quê | Como |
|---|---|
| Perfil de driver resolvido | abrir um jogo → telemetria `armsx2/graphics-boot` com `gpu_driver ≠ Unknown` e `drv_rules > 0` |
| Crash de JIT do SotC | SotC ~2 min + `adb logcat -s NDK_LOG \| grep PoisonWatch` — a mais barata da lista |
| Tela branca no A07 | apagar `<DataRoot>/cache/gl_programs.*` e reabrir o jogo |
| MGS3 no A15 em Vulkan | renderiza correto e FPS ≥ 1.0.16 |
