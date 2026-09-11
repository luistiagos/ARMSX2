# TASK-0090: trazer as duas correções de campo do upstream — afinidade térmica e page cache da extração

- **Status:** aberta
- **Criada em:** 2026-09-08
- **Concluída em:** —
- **Feature:** [FEAT-0003](../features/FEAT-0003-colheita-upstream-setembro-2026.md)
- **Bugs que resolve:** — (nenhum bug nosso escrito; os dois defeitos vêm do relato do upstream e
  foram confirmados por leitura na nossa árvore, ver "O que já foi verificado")
- **Commit:** — (o vínculo é o prefixo `TASK-0090:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

> **Bloco 1 de 5 da FEAT-0003.** Esta é a primeira. Nada depende dela, e a TASK-0091 só começa
> quando esta estiver commitada e validada no aparelho.

## Resumo em uma linha

Dois commits do upstream, ~40 linhas somadas, que corrigem dois defeitos que o usuário sente hoje:
o aparelho esquenta apesar de o Sustained Performance estar ligado, e o app é morto pelo sistema
depois de uma extração grande do Quick Loading.

## Os dois defeitos

### Defeito 1 — a afinidade anula o Sustained Performance, em silêncio

Commit do upstream: **`a18f2eb238`** — *"Affinity: Sustained Performance wins over Performance
Cores"*, 18 linhas em `MainActivityRuntime.kt` + 1 string em `I18n.kt`.

As duas configurações puxam em direções opostas:

- **Sustained Performance** pede à plataforma um clock mais baixo e **estável**, para o aparelho não
  entrar em boost-e-trotina.
- **Modo de afinidade 7 ("Performance Cores")** confina as threads EE/VU/GS ao cluster grande — ou
  seja, mantém-nas exatamente nos núcleos mais quentes, e impede o escalonador de empurrar trabalho
  para os pequenos conforme a temperatura sobe.

Com as duas ligadas, **a afinidade vencia, sem dizer nada**. Relatado no upstream num Galaxy S22+
(Snapdragon 8 Gen 1, quatro núcleos grandes mais quatro A510 que as threads do emulador não
alcançavam mais): depois de atualizar, o Sustained Performance "parou de funcionar", a CPU trotinava
e o aparelho esquentava.

A correção sobrepõe **apenas o modo 7**, e **apenas quando o pref está ligado**. Os modos 1–6 são
colocações por núcleo que alguém foi procurar de propósito — ficam intactos. A sobreposição é
**logada** (`@@ANDROID_AFFINITY@@`) e **declarada na descrição da própria configuração**, porque uma
sobreposição silenciosa é ela mesma um defeito — foi exatamente o que tornou isto difícil de achar.

> **O que a correção NÃO resolve, e o upstream diz isso com todas as letras:** o Sustained
> Performance é opt-in e vem **desligado**. Um aparelho quente **sem** ele continua sendo fixado no
> cluster grande. Se chegarem mais relatos de calor em 8 Gen 1 / Dimensity **sem** o pref ligado, a
> resposta é virar o default, não continuar remendando em volta.

### Defeito 2 — a extração do Quick Loading enche o page cache e o lmkd mata o app

Commit do upstream: **`c2bea2f029`** — *"Quick loading: drop each extracted file from the page
cache"*, 19 linhas em `native-lib.cpp`.

A extração já era **streaming**, então a memória *nossa* estava limitada. Mas toda página escrita
fica **suja** no page cache até o writeback terminar, e um DVD são vários GB delas. Página suja não
pode ser recuperada. Um aparelho com RAM modesta e armazenamento lento fica com o kernel travado em
writeback e **nada que possa liberar**: o lmkd reporta *"device is not responding"* e mata o app.

**E isso cai em cima do que o usuário fizer A SEGUIR**, e é por isso que o defeito se apresentava
como *"o ELF de boot está crashando"*. Não estava. O log do relato mostra a extração concluindo, o
override de disco aplicado, a BIOS de pé e o ELF carregado — e então **signal 9**, antes de o
dispositivo de GS sequer ser criado, ~20 s depois de uma extração de 4,7 GB num tablet de 6 GB. O
mesmo build passava num aparelho de 8 GB mais rápido, que é a pista inteira.

A correção: `fflush` + `fsync` + `posix_fadvise(POSIX_FADV_DONTNEED)` a cada arquivo. O pico de
memória suja passa a ser **um arquivo**, não o disco inteiro.

**A ordem importa: `fsync` antes.** O `posix_fadvise(DONTNEED)` é no-op em página que ainda está
suja — descartar só funciona depois de limpa. Escrever na ordem errada compila, roda e não corrige
nada.

**Custo aceito:** a extração fica mais lenta, porque passamos a esperar cada arquivo chegar ao
armazenamento em vez de deixar o kernel agrupar. Uma extração que termina e deixa o aparelho usável
vale mais que uma rápida que faz o app ser morto depois.

## O que já foi verificado nesta árvore (2026-09-08)

Não repita este trabalho — mas **confira que ainda vale**, porque há alterações não commitadas na
árvore (ver "Estado da árvore").

| Verificação | Resultado |
|---|---|
| `MainActivityRuntime.kt:763` é exatamente o pre-image do patch | ✅ `runCatching { NativeApp.setAffinityMode(bootCfg.affinityMode) }` |
| Já temos a sobreposição de afinidade? | ❌ Não. Só existe o pref. |
| `ui.sustainedPerf` existe e é lido | ✅ `MainActivityRuntime.kt:2670`, para `window.setSustainedPerformanceMode(true)` |
| `perf.affinity.description` no nosso `I18n.kt` | ✅ linha **1556**, string idêntica à do pre-image (o número da linha difere do deles) |
| `perf.affinity.description` traduzido | ⚠️ Só em `assets/i18n/pt-BR.json`, linha ~1435, **e a tradução de lá é mais antiga que o texto em inglês** — descreve os modos numerados como se fossem o comportamento principal |
| `extractIsoToHostfs` existe | ✅ `native-lib.cpp:4503` |
| `extractIsoToHostfs` é **chamado** por algo que o usuário alcança? | ❌ **Não** — conferido só em 2026-09-11. O único chamador é `QuickLoadSetup.run`, que não tem chamador no nosso app (a entrada ficava na `HomeScreen.kt` do upstream, descartada na TASK-0067). Esta linha faltou na verificação original; ver o Resultado |
| A região do `fp.reset()` é idêntica ao pre-image deles | ✅ conferido em `native-lib.cpp:4570-4600` |
| Já temos `posix_fadvise`? | ❌ Não aparece em `native-lib.cpp` |

## Escopo

**Entra:**

1. `git cherry-pick a18f2eb238` — a sobreposição de afinidade + a frase nova na descrição em inglês.
2. `git cherry-pick c2bea2f029` — `fflush`/`fsync`/`posix_fadvise` na extração.
3. **Atualizar `assets/i18n/pt-BR.json`** para `perf.affinity.description`, incluindo a frase nova
   sobre o Sustained Performance. A tradução atual está desatualizada em relação ao inglês **antes**
   desta mudança; deixá-la como está faria o usuário pt-BR ler uma descrição que não corresponde ao
   comportamento. As demais línguas não têm a chave e continuam caindo no inglês — comportamento
   já existente, não é regressão desta task.

**NÃO entra:**

- **Virar o default do Sustained Performance para ligado.** É a sugestão que o próprio upstream
  registra como "o que fazer se chegarem mais relatos". Não chegaram para nós ainda, e mudar default
  de desempenho sem medição é como os quatro ciclos de correção gráfica de 1.0.17–1.0.22 começaram.
- **Qualquer outra coisa do upstream.** Os outros blocos têm suas próprias tasks.
- **Otimizar a extração para compensar a lentidão nova.** Se a lentidão incomodar, é medição e task
  própria — não improviso dentro desta.
- **Traduzir a descrição para as outras 14 línguas.** Elas não têm a chave hoje.

## Como implementar

### 0. Estado da árvore, antes de qualquer coisa

Havia **alterações não commitadas** quando esta task foi escrita, e elas **não são desta task**:

```
 M platforms/android/app/src/main/java/com/armsx2/config/Settings.kt
 M platforms/android/app/src/main/java/com/armsx2/ui/InGameOverlay.kt
 M platforms/android/app/src/main/java/com/armsx2/ui/settingshub/SettingsViewModel.kt
 M platforms/android/gradle.properties
 M publish-retrosystem-ps2.ps1
```

**Não commite estes arquivos.** Commite apenas os caminhos que esta task toca, por caminho
explícito (`git add <path>`), nunca `git add -A`. Se atrapalharem, pergunte ao usuário — **não**
use `git stash` puro: a pilha é compartilhada entre worktrees.

### 1. Buscar e aplicar

```bash
git fetch upstream --prune
git cherry-pick a18f2eb238
git cherry-pick c2bea2f029
```

Espera-se que os dois apliquem limpo — o pre-image de ambos foi conferido acima. **Se conflitar**,
é porque a árvore andou desde 2026-09-08: resolva mantendo a lógica deles, não reescreva.

`git cherry-pick` preserva a autoria de `jpolo1224`. Isso é desejado. Acrescente o rodapé
`Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ao amend da mensagem se editá-la para pôr o
prefixo `TASK-0090:`.

### 2. A tradução pt-BR

Editar `platforms/android/app/src/main/assets/i18n/pt-BR.json`, chave `perf.affinity.description`.
O texto novo tem de dizer as duas coisas: que "Núcleos de Desempenho" é o padrão, e que **ligar o
Sustained Performance o desativa**, porque essa configuração pede clock estável mais frio e segurar
o emulador nos núcleos grandes trabalha contra ela.

### 3. Compilar

**O lado C++** (`native-lib.cpp`) — não precisa do build completo de 14 minutos. O AGP deixa árvores
ninja configuradas em `platforms/android/app/.cxx/{Debug,Release}/<hash>/arm64-v8a/`; use
`ninja -t targets all | grep native-lib` para achar o alvo exato e peça só ele:

```bash
cd platforms/android/app/.cxx/Debug/<hash>/arm64-v8a
"D:/DevCaches/Android/Sdk/cmake/3.31.6/bin/ninja.exe" -j 4 <alvo>
```

`-j 4` é obrigatório — sem ele o clang morre com `LLVM ERROR: out of memory`, que é falta de RAM e
não erro de código.

**O lado Kotlin** — atenção, esta task **toca `I18n.kt`**, que é a armadilha conhecida:

```bash
cd platforms/android && ./gradlew.bat --stop
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:compileGithubDebugKotlin \
  -Pkotlin.incremental=false \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

- `-Pkotlin.incremental=false` **não é opcional aqui.** Editar `I18n.kt` faz a compilação
  incremental falhar com `Unresolved reference LSFG_EN` e dezenas de erros em cascata em
  `RendererTab.kt`. **Não é erro do código** — `LSFG_EN` é declarado em `src/github/`, outro source
  set, e o incremental perde a referência.
- O contorno de `JAVA_HOME`/`installations.paths` **também não é opcional**: sem ele o Gradle
  autodetecta um JRE de extensão de editor que não tem `jlink`.
- No PowerShell, **cada `-D...` precisa das próprias aspas**.
- Depois de qualquer invocação que falhe, `--stop` antes de tentar de novo — um daemon vivo com a
  configuração errada é reaproveitado e o erro seguinte é outro (`androidJdkImage`), o que despista.

### 4. Instalar no aparelho

**`compile*Kotlin` verde NÃO põe a mudança no aparelho.** Use `:app:installGithubDebug` (≈7 min),
nunca `compile` seguido de `adb install` do APK que já estava em `outputs/`.

```bash
cd platforms/android
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:installGithubDebug \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

Confirme que o APK instalado é o novo antes de concluir qualquer coisa do teste — compare o tamanho
do `base.apk` no aparelho (`adb shell pm path come.nanodata.armsx2`, depois `ls -la` no caminho que
ele devolve) com o do APK local em
`platforms/android/app/build/outputs/apk/github/debug/app-github-debug.apk`. Tamanho igual = é o
mesmo arquivo.

> ⚠️ **Não havia aparelho conectado em 2026-09-08** (`adb devices` vazio). Peça ao usuário para
> conectar antes de começar a parte de validação. O aparelho de referência das medições anteriores
> é o **SM-A127M** (Galaxy A12, Mali-G52, Android 13) — é o aparelho fraco, que é onde os dois
> defeitos aparecem.

## Como validar

Compilar não é validar. Os dois defeitos precisam de evidência **no aparelho**.

### Defeito 1 — a sobreposição de afinidade

1. Configurações → Desempenho: **ligar** "Sustained Performance" e deixar o modo de afinidade em
   **"Núcleos de Desempenho"** (modo 7).
2. `adb logcat -c`, dar boot num jogo, e:

```bash
adb logcat -d -s System.out | grep ANDROID_AFFINITY
```

**Critério 1a:** aparece `@@ANDROID_AFFINITY@@ sustained performance on -> affinity forced to
Disabled`.

3. **Desligar** o Sustained Performance, manter o modo 7, dar boot de novo.

**Critério 1b:** a linha **não** aparece — a sobreposição só age quando o pref está ligado.

4. Escolher um modo numerado (1 a 6) **com** Sustained Performance ligado, dar boot.

**Critério 1c:** a linha **não** aparece — modos 1–6 são escolha explícita e ficam intactos.

5. **Critério 1d:** a descrição da configuração na tela mostra a frase nova sobre o Sustained
   Performance, **em português** (o app em pt-BR) e em inglês (trocando o idioma).

### Defeito 2 — o page cache da extração

Este é o teste que de fato importa, e ele **precisa de um jogo grande** — um DVD, não um CD. Alvo:
algo na casa dos **4 GB**.

1. `adb logcat -c`
2. Ligar o Quick Loading para esse jogo e disparar a extração.
3. Enquanto extrai, amostrar a memória suja do kernel a cada ~2 s:

```bash
adb shell "grep -E 'Dirty|Writeback' /proc/meminfo"
```

**Critério 2a — o número que define a correção:** o `Dirty` deve estabilizar na ordem do **maior
arquivo individual** do disco, e **não** crescer monotonicamente rumo aos GB da extração inteira.
Registre a série dos dois lados (antes e depois) — sem o "antes" não há prova.

> Para ter o "antes", meça uma vez com o APK **anterior** ao cherry-pick (ou com os dois commits
> revertidos localmente), no mesmo aparelho e com o mesmo jogo. Um "depois" sem par não prova nada.

4. Ao terminar a extração, **usar o app normalmente por ~1 minuto**: dar boot no jogo, entrar no
   menu, voltar. É aqui que o defeito antigo se manifestava.

**Critério 2b:** o app **não** é morto. Confirmar que não houve `signal 9` procurando por `lmkd`,
`died` e `signal 9` no `adb logcat -d`, filtrado por `come.nanodata.armsx2`. Não deve haver `lmkd`
reportando *"device is not responding"* nem morte do processo.

5. **Critério 2c — a extração continua correta.** O custo aceito é lentidão, não corrupção.
   Conferir que a contagem de arquivos escritos bate com a do log
   (`extractIsoToHostfs: wrote N file(s) to ...`) e que **o jogo dá boot pelo ELF extraído**.

6. **Critério 2d — registrar o custo.** Cronometrar a extração antes e depois, mesmo jogo, mesmo
   aparelho. A regressão de tempo é esperada e deve ser **escrita na seção Resultado**, não
   escondida. Se passar de ~2× ela merece uma nota — não bloqueia a task, mas informa a próxima.

### Testes de regressão

```bash
cd platforms/android
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:testGithubDebugUnitTest \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

Devem continuar verdes (eram **37 testes** na TASK-0084; confirme a contagem atual e registre).

## Antes de fechar

```bash
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

E preencher, **neste arquivo**, uma seção `## Resultado` com:

- a série de `Dirty` antes e depois (o número, não a impressão);
- os tempos de extração antes e depois;
- as quatro linhas de critério do defeito 1, com o que o logcat mostrou;
- **o que não foi feito e por quê**, se algo não foi feito.

Uma task que diz "validado" sem os números não passa em revisão neste projeto — ver a
[TASK-0084](TASK-0084-ajuste-em-configuracoes-nao-bloqueia-a-thread-da-ui.md) como referência do
nível esperado, inclusive na parte de admitir o critério que **não** foi medido.

## Resultado

> **Defeito 1: os quatro critérios passaram no aparelho (2026-09-11). Defeito 2: nenhum dos quatro
> foi medido, e não por falta de tempo nem de aparelho — no nosso app a extração do Quick Loading
> não tem ponto de entrada.** `QuickLoadSetup.run` não tem chamador: o único ficava na
> `HomeScreen.kt` do upstream, e o merge da TASK-0067 manteve a nossa HomeScreen inteira. Ninguém
> consegue disparar `extractIsoToHostfs` no RetroSystem PS2 hoje, então o defeito que o
> `c2bea2f029` corrige **não existe para os nossos usuários**, e a premissa da task ("o app é morto
> pelo sistema depois de uma extração grande do Quick Loading", "sintoma que o usuário sente hoje")
> vale para o upstream, não para nós. A correção entrou, está no binário e é inofensiva; ela passa a
> importar no dia em que a entrada voltar.
>
> **A task não fecha**: 2a–2d ficam sem medir. O que fazer com isso é decisão — ver
> "O que decidir agora", no fim desta seção.

### Veredito por critério (aparelho, 2026-09-11)

| Critério | Veredito | Evidência |
|---|---|---|
| **1a** — sustained ON + modo 7 → linha aparece | ✅ **passou** | `15:26:53.997 @@ANDROID_AFFINITY@@ sustained performance on -> affinity forced to Disabled`, 60 ms depois do `@@ANDROID_START_VM@@`; e o **nativo tomou o ramo do modo 0** (abaixo) |
| **1b** — sustained OFF + modo 7 → linha **não** aparece | ✅ **passou** | `@@ANDROID_START_VM@@` às 15:23:39.505, zero `@@ANDROID_AFFINITY@@`; nativo: `Affinity mode: Performance Cores (mask 0xff, 8 processors, mtvu=1)` |
| **1c** — sustained ON + modo 3 → linha **não** aparece | ✅ **passou** | `@@ANDROID_START_VM@@` às 15:30:24.944, zero `@@ANDROID_AFFINITY@@`; nativo: `Affinity mode 3: EE rank 1 (0x20), VU rank 0, GS rank 2 (0x40), mtvu=1` |
| **1d** — frase nova na tela, pt-BR e inglês | ✅ **passou** | texto completo renderizado no diálogo "i" do cartão, nos dois idiomas (citado abaixo) |
| **2a** — série de `Dirty` antes × depois | ⛔ **não medido** | a extração não pode ser disparada no nosso app |
| **2b** — app não é morto depois da extração | ⛔ **não medido** | idem |
| **2c** — extração correta, boot pelo ELF | ⛔ **não medido** | idem |
| **2d** — custo em tempo da extração | ⛔ **não medido** | idem |

### Aparelho e proveniência dos dois APKs

- **Aparelho:** `RX8R90G1D6E`, SM-A127M, Android 13, Helio P35 (2 clusters, 8 × Cortex-A53),
  `MemTotal` 3.792.268 kB, 14 GB livres.
- **"Antes" (o instalado, pela decisão de 2026-09-08):** 2.0.4 / versionCode 2004, `base.apk` de
  94.329.132 bytes. A condição que a decisão impôs foi conferida **no momento da medição** e **vale**:
  `@@ANDROID_AFFINITY@@` ausente dos `classes*.dex`, e a `libemucore_4k.so` dele **não importa nem
  `posix_fadvise` nem `fsync`** (`llvm-readelf --dyn-syms`) — as duas metades da task ausentes. Era
  um "antes" válido. Ficou sem uso porque o Defeito 2 não tem como ser disparado, e o Defeito 1 não
  precisa de par.
- **"Depois":** construído do `HEAD` **`8d3ad9a338`** por `:app:installGithubDebug` (1 min 11 s),
  mais uma única diferença não commitada na entrada do build: `platforms/android/gradle.properties`
  com `armsx2.versionCode=2005` / `versionName=2.0.5` (o commitado é 2000 / 2.0.0; é trabalho do
  usuário para a próxima versão e **não** foi tocado). Nenhuma outra alteração de código na árvore.
  Instalado às 15:22:40. **md5 `31cb514beacc47b771fba32ad8745462` idêntico no aparelho e no
  `outputs/`**, 93.677.811 bytes, versionCode 2005, e a tela de Configurações mostra
  "Instalado: 2.0.5". O APK de 08/09 **não** foi usado.

### Defeito 1 — como cada veredito foi lido

O critério escrito na task olha só a linha do Kotlin. Isso prova a **decisão**, não o que chegou ao
núcleo. Por isso cada rodada leu também o log do `VMManager::SetEmuThreadAffinities`, que imprime
coisas diferentes por ramo (`pcsx2/VMManager.cpp:4243-4360`):

| Ramo | O que o nativo imprime |
|---|---|
| modo 0 (Desativado) | **nada**: `return` na linha 4265-4274, **antes** do `EnsureCPUInfoInitialized()` da linha 4276 — portanto nem `Processor count` |
| modos 1–6 | `Processor count: …` e `Affinity mode N: EE rank …` |
| modo 7 | `Processor count: …` e `Affinity mode: Performance Cores (mask …)` |

E um cuidado nas duas rodadas em que o critério é "a linha **não** aparece" (1b, 1c): a ausência só
vale se o boot chegou ao `start()`. As duas mostram `@@ANDROID_START_VM@@`, impresso no `start()`
imediatamente antes do código da afinidade. Sem isso, "não apareceu" seria vácuo.

Em **1a** o silêncio do nativo é, então, evidência positiva: as três linhas que qualquer modo
diferente de 0 imprime (`Processor count`, `Affinity mode…`, `(Oboe) … pinned to perf-cluster`)
estão ausentes, **numa VM que comprovadamente rodou** — 415 linhas do núcleo e
`PerfLog: 41.6 fps | EE 69% GS 30% VU 2%` aos 64 s. Em 1b o mesmo jogo imprimiu as três.

Os estados de cada rodada foram postos **pela própria tela de Configurações** (escopo Global),
tocada por `adb shell input`, e conferidos **no disco** (`run-as … cat shared_prefs/ARMSX2.xml`)
antes de cada boot: 1b `(ui.sustainedPerf=false, affinityMode=7)`, 1a `(true, 7)`, 1c `(true, 3)`.
O boot foi por `am start -a android.intent.action.VIEW -d file://…` na `BootSplashActivity`, com
*The Adventures of Darwin* (`.chd`, 74 MB) para o boot ser curto.

**1d**, texto renderizado no diálogo "i" do cartão "Modo de controle de afinidade":

- pt-BR: *"…Aplica-se na próxima inicialização. Ligar o Desempenho Sustentado o desativa: essa
  configuração pede um clock estável e mais frio, e segurar o emulador nos núcleos grandes trabalha
  contra ela."*
- inglês (idioma trocado para English e depois devolvido a "Idioma do sistema"): *"…Applies on the
  next boot. Turning on Sustained Performance disables it: that setting asks for a cooler steady
  clock, and holding the emulator on the big cores works against it."*

Duas observações que o aparelho mostrou e que valem registrar:

- **A sobreposição não muda o que a tela mostra**, como previsto em 2026-09-08: com o Sustained
  ligado, o seletor continua destacando "Núcleos de Desempenho". A frase da descrição é a única
  pista visível — e agora ela está lá, nos dois idiomas.
- **Neste aparelho o modo 7 não confina nada:** os dois clusters do Helio P35 são Cortex-A53, e
  "Núcleos de Desempenho" resolve para `mask 0xff` — os oito núcleos. Então o efeito térmico que o
  Defeito 1 descreve **não é observável no SM-A127M**; o que se validou aqui é a lógica da
  sobreposição, que é o que os critérios pedem. Medir calor exigiria um aparelho com cluster grande
  de verdade (o relato era um Snapdragon 8 Gen 1).

### Defeito 2 — por que nada foi medido

**A extração não tem ponto de entrada no nosso app.** Como isso foi estabelecido, e não presumido:

1. `QuickLoadSetup.run` — o único chamador de `NativeApp.extractIsoToHostfs` — **não tem chamador
   nenhum** em `platforms/android/app/src/`, em nenhum source set. As chaves de UI do fluxo
   (`games.quickLoad.confirmTitle`, `.continue`, `.working`) só aparecem no `I18n.kt`.
2. `git grep` pelos chamadores em quatro revisões: **`upstream/master` e o nosso merge-base
   `ce96af5046` chamam** de `ui/home/HomeScreen.kt` (linhas 193, 1143, 1168, 1185, 1240); **o nosso
   `HEAD` e o `662b114168` não**.
3. Na nossa linha de primeira-mãe, `HomeScreen.kt` **nunca** teve a chamada. O histórico do
   upstream entrou pelo merge **`e047ce36fe`** (*TASK-0067: traz o merge com o upstream…*), cujo
   segundo pai é **`6a86b38ebf`** (*TASK-0067: git merge upstream/master…*). A TASK-0067 registra que
   **"`HomeScreen.kt` ficou com a NOSSA versão inteira"** e anota a perda das prateleiras por
   categoria — mas **não** percebeu que a entrada do Quick Loading morava no mesmo arquivo. O
   `QuickLoadSetup.kt` chegou por auto-merge, como arquivo novo, com o único chamador descartado.
4. **Conferido na tela**, no build novo: o toque longo em *God of War II* (um `.iso` de 4,27 GB,
   exatamente onde o upstream oferece a opção) abre uma folha com **Jogar, Configurações, BIOS por
   jogo, Região de cobertura, Memory Cards, Adicionar à tela inicial, Remover dos reproduzidos
   recentemente, Ocultar da biblioteca, Deletar jogo** — e nada de "Set up quick loading". O menu
   lateral também não tem.

É exatamente o caso da regra do CLAUDE.md: *"Ao afirmar que uma função faz parte de um fluxo,
provar pelo call-site, não pelo nome."* Em 2026-09-08 esta task verificou que `extractIsoToHostfs`
**existe** (tabela "O que já foi verificado"), não que ela é **chamada**.

**Mesmo com a entrada de volta, o Critério 2a como está escrito não discriminaria neste aparelho.**
Duas coisas medidas em 2026-09-11 que a próxima tentativa precisa saber:

- **`vm.dirty_bytes = 104857600` (100 MB)**, `dirty_background_bytes` 25 MB,
  `dirty_expire_centisecs` 200. A Samsung limita a memória suja a 100 MB **absolutos**; o escritor é
  estrangulado ali. O "antes" não tem como "crescer monotonicamente rumo aos GB", que é o que o
  critério espera. O defeito relatado (um tablet de 6 GB) acontece com limite por **razão**
  (`dirty_ratio`), que cresce com a RAM.
- **Os quatro DVDs do aparelho são "um arquivo só".** Lendo o ISO9660 de cada um setor a setor por
  `adb exec-out dd` (sem puxar os GB): *God of War 2* tem 15 arquivos e `PART1.PAK` de **4.065 MB**;
  *3LDK* tem `PAC.BIN` de 1,9 GB; *120 Yen no Haru*, `DATA.BIN` de 1,1 GB; *_summer Double Sharp*,
  `ROM.` de 2,2 GB. O `fsync`+`DONTNEED` age **por arquivo** — durante o arquivo gigante, antes e
  depois rodam o mesmo código, e a diferença só aparece **no fim** dele.

Um critério que discriminaria: no fim da extração, `Dirty` perto de zero e `Cached` caindo na
ordem do tamanho do arquivo (o `DONTNEED` descartando as páginas limpas), contra `Cached` alto e
`Dirty` no teto no "antes" — medido num aparelho de limite por razão, ou num disco com vários
arquivos médios. Fica como sugestão para quem escrever a próxima task, não como coisa feita.

### Um ajuste alterado sem querer, e desfeito

**"Verifique no lançamento" (`update.checkOnLaunch`) foi alternado duas vezes por toques meus.** A
transição do menu lateral para Configurações leva mais de 3 s neste aparelho; a captura de tela
ainda mostrava o menu, e eu toquei de novo em (220, 877) — que, já na aba App, cai dentro da linha
desse interruptor (a linha inteira é clicável). Aconteceu nas duas navegações que fiz assim: às
~15:25 e às ~15:31.

O valor final é `false`. A última cópia independente das preferências — `files/x.xml` e
`files/ARMSX2.xml.new`, de 2026-09-04, com 81 chaves cada — **não contém chave `update.*`
nenhuma**, ou seja, o interruptor nunca tinha sido gravado e valia o padrão, `false`. A leitura
consistente é: `false` → `true` (15:25) → `false` (15:31), de volta ao original. **Mas é inferência**:
se o usuário o tiver ligado por conta própria entre 04/09 e 11/09, ele está desligado agora e
precisa ser religado na tela. Não reescrevi o XML para apagar a chave: `false` e "ausente" leem
igual, e escrever preferências na marra é justamente o que esta task descartou.

Todo o resto voltou ao estado de antes da sessão: `ui.sustainedPerf=false`, `affinityMode=7`,
`ui.language=system`, e o `config.global` final é **byte a byte igual** ao capturado antes da
rodada 1c. As outras diferenças no arquivo são carimbos que o próprio app grava
(`playtime.last.*`, `telemetry_last_exit_ts`).

### Correções no script de validação

O `library` do `TASK-0090-validar-no-aparelho.py` anunciou **"nenhuma imagem de disco"** num
aparelho com quatro ISOs de DVD: o `find` do Android é o do toybox e **não tem `-printf`**. Passou a
usar `find … -exec stat -c '%s|%n'`, testado no aparelho. Também deixou de rotular `.chd`/`.cso`
como "CD" pelo tamanho (um `.chd` de DVD comprime para a faixa de um CD) e passou a avisar que o
tamanho do `.iso` não basta para o Critério 2a. O cabeçalho do script agora diz que o Quick Loading
não tem entrada no app e que, no Git Bash, é preciso `MSYS_NO_PATHCONV=1` para passar caminhos
`/storage/...` — sem isso o MSYS os reescreve como caminho do Windows, e só escapam os que têm `[`
ou `]`, o que torna a falha intermitente.

### O que decidir agora

A TASK-0090 não tem como fechar com os critérios que tem: 2a–2d dependem de uma entrada de UI que
não existe. Três saídas, e a escolha não é desta sessão:

1. **Reescopar e fechar**: declarar o Defeito 2 como "código do upstream carregado, dormente no nosso
   app", marcar 2a–2d como não aplicáveis, e fechar a task com o Defeito 1 validado.
2. **Task nova para devolver a entrada do Quick Loading** à nossa HomeScreen (é um recurso do
   upstream que perdemos sem saber, na TASK-0067) e validar o Defeito 2 **lá**, com um critério que
   discrimine (acima).
3. As duas: fechar esta pelo (1) e abrir a (2) como bug/task própria.

A (3) é a que eu recomendaria: o que esta task prometia sobre o Defeito 1 está provado, e o
Defeito 2 virou outra pergunta — "queremos o Quick Loading no nosso app?" — que merece registro
próprio em vez de ficar pendurada aqui.

### O que foi commitado

| Commit | Autor | Assunto |
|---|---|---|
| `98d6c01402` | jpolo1224 | `TASK-0090: faz o Sustained Performance vencer o modo Nucleos de Desempenho` |
| `5a6ef7fcf2` | jpolo1224 | `TASK-0090: descarta do page cache cada arquivo extraido pelo Quick Loading` |
| `03f3e48a1f` | Luis Tiago | `TASK-0090: atualiza a descricao da afinidade em pt-BR` |
| `02198734e1` | Luis Tiago | `TASK-0090: registra o resultado parcial -- codigo provado no APK, aparelho pendente` |
| `33544306c2` | Luis Tiago | `TASK-0090: registra a decisao do "antes" e deixa a validacao pronta em um comando` |

Os resultados de aparelho de 2026-09-11 e as correções do script entram num commit seguinte, com
o mesmo prefixo (o índice guarda todos os hashes).

Os dois primeiros são `git cherry-pick -x` de `a18f2eb238` e `c2bea2f029`. **Os dois aplicaram
limpo** (`Auto-merging`, sem conflito), a autoria de `jpolo1224` foi preservada e a linha
`(cherry picked from commit ...)` ficou no corpo. Só o assunto foi reescrito, para levar o prefixo
`TASK-0090:` que o validador exige — sem isso `check_traceability.py --commits` reprova, porque um
cherry-pick **não** é merge e entra no laço de conferência.

As cinco alterações não commitadas que não são desta task (`Settings.kt`, `InGameOverlay.kt`,
`SettingsViewModel.kt`, `gradle.properties`, `publish-retrosystem-ps2.ps1`) continuam
**não commitadas e intocadas**. Nenhum `git add -A`, nenhum `git stash`.

### Verificação de símbolo antes de escrever (a regra do CLAUDE.md)

Não era código novo, mas o pre-image de um cherry-pick pode mentir tanto quanto um `grep`:

| Símbolo | Onde foi aberto | O que se confirmou |
|---|---|---|
| `prefs` | `MainActivityRuntime.kt:139` | `lateinit var prefs: SharedPreferences`, membro do `companion object` — **em escopo** no ponto de inserção (`start()`, também no companion) |
| `"ui.sustainedPerf"` | `MainActivityRuntime.kt:2670` | chave exata, default `false`, a mesma que aciona `window.setSustainedPerformanceMode(true)` |
| `bootCfg.affinityMode` | `Settings.kt:445` | `Int`, default `7` |
| `NativeApp.setAffinityMode` | `NativeApp.java:764` | `public static native void setAffinityMode(int mode)` — recebe `int`, então o `affinity: Int` calculado serve |
| `fp` | `common/FileSystem.h:108` | `ManagedCFilePtr = std::unique_ptr<std::FILE, FileDeleter>` → `fp.get()` é `std::FILE*`, e `fileno()`/`std::fflush()` valem |
| `FileDeleter` (comportamento em destruição) | `common/FileSystem.h:99-105` | só `std::fclose(fp)`. Não há nada entre o `fsync` e o `fp.reset()` que desfaça a ordem exigida |
| `<unistd.h>` / `<stdio.h>` | `native-lib.cpp:4` e `:6` | já presentes → `fsync` e `fileno` declarados. Só faltava `<fcntl.h>`, que o patch traz |
| `ConfigStore.migrateAffinityPerfCores` | `ConfigStore.kt:297-305` | migração **única**, atrás de um flag em prefs. **Não interage** com a sobreposição: a sobreposição não grava nada, só troca o valor entregue ao nativo |

Uma consequência dessa última linha, que vale registrar para quem for testar: **a sobreposição não
muda o que a tela mostra.** Com o Sustained Performance ligado, o seletor continua exibindo
"Núcleos de Desempenho"; o que muda é o valor empurrado para o nativo. É exatamente por isso que a
frase nova na descrição não é enfeite — ela é a única pista visível de que a sobreposição existe.

### Compilação e testes (medidos)

| Etapa | Resultado |
|---|---|
| `native-lib.cpp.o` (ninja `-j 4`, árvore `.cxx/Debug/2r5u2a6y/arm64-v8a`) | **exit 0, 19,6 s.** 3 warnings — `env_main`, `s_dump_frame_number`, `s_loop_number` — todos pré-existentes e fora do trecho novo |
| `:app:compileGithubDebugKotlin` (`-Pkotlin.incremental=false`, JDK 21) | **BUILD SUCCESSFUL, 1 min 48 s.** Só avisos de deprecação pré-existentes; os de `MainActivityRuntime.kt` são das linhas 2499/2657/2658, não do trecho novo (763-780) |
| `:app:testGithubDebugUnitTest` | **BUILD SUCCESSFUL, 1 min 6 s — 42 testes, 0 falhas, 0 erros, 0 ignorados** |
| `:app:assembleGithubDebug` | **BUILD SUCCESSFUL, 47 s.** APK de 93.677.811 bytes |
| `check_traceability.py` | `OK -- 94 task(s), 3 feature(s)` |
| `check_traceability.py --commits upstream/master..HEAD` | `OK -- ... 157 commit(s)` |

**Os 42 testes corrigem o número da própria task**, que citava 37 (TASK-0084). A suíte cresceu; a
contagem por classe é `RendererRecoveryTest` 13, `RomArchiveExtractorTest` 8, `DownloadFormatTest` 5,
`RomDownloadFallbackTest` 5, `AngleDriverTest` 5, `DiscordSessionClockTest` 4, `ExampleUnitTest` 1,
`I18nKeysTest` 1.

`I18nKeysTest` foi aberto antes de mexer no JSON: ele confere **chave usada × chave definida em
`I18n.kt`**, e não olha os JSONs de tradução. Trocar um *valor* em `pt-BR.json` não o afeta — e o
cherry-pick também não adiciona nem remove chave, só troca o texto de uma.

### A mudança está mesmo dentro do APK (e não só "compilou")

Esta é a armadilha registrada em `apk-instalado-nao-tem-a-correcao.md`, então foi conferida por
conteúdo, não por "BUILD SUCCESSFUL":

- **C++:** `posix_fadvise` aparece **uma única vez em toda a árvore de código** (`native-lib.cpp:4608`,
  a linha nova). Na `libemucore_4k.so` ligada às 18:50:52 — depois do `.o` das 18:46:44 —
  `llvm-readelf --dyn-syms` mostra `UND FUNC GLOBAL posix_fadvise@LIBC` e `UND ... fsync@LIBC`. O
  símbolo não tinha de onde vir a não ser do trecho novo.
- **Kotlin:** a string de log `@@ANDROID_AFFINITY@@ sustained performance on -> affinity forced to
  Disabled` está em `classes6.dex`; a frase nova em inglês (`Turning on Sustained Performance
  disables it`) está em `classes11.dex`.
- **Tradução:** `assets/i18n/pt-BR.json` **dentro do APK** abre com `"Núcleos de Desempenho" é o
  padrão:` e contém `Desempenho Sustentado`. O JSON continua válido, com as **1553 chaves**
  originais, UTF-8 sem BOM, LF, e `git diff --numstat` de **1 linha alterada, 1 inserida**.

### Histórico: 2026-09-08, sem aparelho

Nesta data nenhum critério de aparelho foi medido — `adb devices` ficou vazio a sessão inteira. A
tabela de vereditos no topo desta seção substitui a que estava aqui.

**O APK "antes" também não existia.** A tentativa de produzi-lo — reverter os quatro arquivos
para `b0fe13f769` com `git checkout <commit> -- <paths>`, compilar, guardar o APK e restaurar — foi
**bloqueada pelo classificador de permissão** da sessão, e não foi contornada: sobrescrever arquivos
da árvore de trabalho com cinco alterações alheias em cima é justamente o que aquela barreira
existe para segurar. Fica registrado como decisão de parar, não como esquecimento.

### Decisão do usuário sobre o "antes" do Critério 2a (2026-09-08)

**O "antes" será o APK que já estiver instalado no aparelho.** Reverter os quatro arquivos para
`b0fe13f769` para produzir um "antes" está **descartado por decisão**, não adiado: a árvore fica
como está, com as cinco alterações alheias intocadas, e a barreira que bloqueou aquele caminho
**não deve ser contornada**.

Isso traz uma condição, e ela é o ponto todo: **só vale se o build instalado for anterior aos
quatro commits desta task.** Isso tem de ser conferido **no momento da medição**, não presumido —
ninguém sabe hoje o que está instalado naquele aparelho. Se na hora ele for igual ou mais novo, o
Critério 2a **fica sem par e deve ser marcado como não medido**. Não se inventa um substituto: uma
série de `Dirty` sem o par "antes" não prova nada, e escrever que provou é pior que não medir.

Três conferências, da mais fraca para a mais forte:

| Sinal | Como | O que diz |
|---|---|---|
| `versionCode` e data | `adb shell dumpsys package come.nanodata.armsx2 \| grep versionCode`; `adb shell pm path ...` e `stat` no `base.apk` | circunstancial — os dois lados podem compartilhar o mesmo `versionCode`, porque nada nesta task o incrementa |
| Tamanho | `stat -c %s` no `base.apk` instalado × 93.677.811 do APK novo | tamanho igual = é o mesmo arquivo, e aí **não** serve de "antes" |
| **Marcador no dex** | puxar o `base.apk` instalado e procurar `@@ANDROID_AFFINITY@@` nos `classes*.dex` | **decisivo.** A string só existe a partir de `98d6c01402`. Se ela **está** no instalado, aquele build já tem a correção e não é "antes" nenhum |

A terceira é a que resolve, e é por isso que o script abaixo a implementa: ela responde
"este build tem a correção?" diretamente, em vez de inferir de número de versão ou de data de
arquivo, que é o tipo de inferência que já custou uma medição inteira neste projeto.

### Script de validação, para a medição ser uma passagem só

[`TASK-0090-validar-no-aparelho.py`](TASK-0090-validar-no-aparelho.py), ao lado deste arquivo.
Existe para que a próxima sessão **não re-derive como medir** — os oito critérios já estão
codificados, com os mesmos números da seção "Como validar".

```bash
python docs/task/TASK-0090-validar-no-aparelho.py preflight   # aparelho, APK, e se o instalado serve de "antes"
python docs/task/TASK-0090-validar-no-aparelho.py library     # acha o alvo de DVD (~4 GB) no proprio aparelho
python docs/task/TASK-0090-validar-no-aparelho.py defeito1 --game "/storage/.../Jogo.iso"
python docs/task/TASK-0090-validar-no-aparelho.py meminfo --label antes
python docs/task/TASK-0090-validar-no-aparelho.py posmortem
```

O que ele automatiza de verdade: a conferência de identidade do APK e a decisão sobre o "antes"; a
listagem da biblioteca com tamanhos, separando DVD de CD (e dizendo com todas as letras quando só
há CD, caso em que 2a–2d **não devem ser medidos** — um CD não enche o page cache o bastante); as
três rodadas do Defeito 1, **inclusive o boot**, por `am start -a android.intent.action.VIEW -d
file://…` na `com.armsx2.BootSplashActivity`, que é exportada e declara esse filtro
(AndroidManifest.xml:102-108); a leitura do logcat e o veredito de 1a/1b/1c; a amostragem de
`Dirty`/`Writeback` com série gravada em TSV; e a varredura de `lmkd`/`signal 9`/`died` de 2b mais
a contagem de arquivos de 2c.

O que ele **deliberadamente não faz**, e o motivo em cada caso:

- **Não escreve em `shared_prefs`.** Trocar `ui.sustainedPerf` e `affinityMode` na marra exigiria
  force-stop e reescrita de um XML com um JSON escapado dentro — código que eu **não teria como
  exercitar sem aparelho**, entrando justamente no passo que decide o veredito. Um erro ali não
  falha alto: mede com a régua torta. Então o script **lê** o pref e **confere** que o estado
  pedido valeu antes de dar boot; quem troca é o operador, na tela.
- **Não dispara a extração do Quick Loading.** O fluxo passa por um seletor de arquivo do sistema
  (SAF) para escolher o ELF — `QuickLoadSetup.run(context, iso, elfUri)`. Não há como conduzir isso
  por adb sem automação de UI. O script pede que o operador dispare e mede em volta.
- **Não instala nem desinstala nada.** A ordem antes/depois é decisão de quem mede, e desinstalar
  perde saves.
- **Não julga o 1d sozinho.** Ele puxa duas capturas de tela (pt-BR e inglês); quem decide se a
  frase está lá é quem olha.

Conferido sem aparelho: o script compila, o `--help` responde, `preflight` sem aparelho falha alto
com mensagem clara e `exit=1`, e a função que decide o "antes" foi exercitada contra o APK novo —
ela responde corretamente que **esse** APK contém o marcador e portanto não serviria de "antes".
Os caminhos que exigem adb continuam **não exercitados**, e isso é o que é.

Os artefatos que ele gera (séries TSV, capturas, e a cópia de ~90 MB do `base.apk` instalado) caem
em `docs/task/_TASK-0090-medicoes/`, que entrou no `.gitignore`: os **números** vão para esta
seção, os arquivos não vão para o repositório.

O APK "depois" está pronto e é o do build acima:
`platforms/android/app/build/outputs/apk/github/debug/app-github-debug.apk` (93.677.811 bytes).
Na hora de instalar, vale a regra da própria task: `:app:installGithubDebug`, e conferir o tamanho
do `base.apk` no aparelho contra esse número antes de acreditar em qualquer medição.

### O jogo do Defeito 2 se escolhe na hora, no próprio aparelho

O usuário não nomeou um título, e não precisa: `library` lê o pref `romsDirs` do aparelho, varre as
pastas configuradas, e lista cada imagem de disco com o tamanho, separando **DVD** (≥ 3 GB) de
**CD**. O corte em 3 GB fica longe dos dois lados — um DVD de PS2 passa de 4,7 GB, um CD para em
700 MB — então não há zona cinzenta para errar.

Duas condições que o script já verifica e que valem estar escritas aqui:

- **Tem de ser `.iso` puro.** `.chd` e `.cso` não servem — é a própria mensagem do app
  (`games.quickLoad.extractFailed`: *"Quick loading needs a plain .iso"*).
- **Se só houver CD, 2a–2d não devem ser medidos.** Um CD não enche o page cache o bastante para o
  defeito aparecer; medir com ele produziria um "passou" que não significa nada. O desfecho certo
  nesse caso é registrar "sem alvo de DVD no aparelho", não medir com o que tem à mão.
