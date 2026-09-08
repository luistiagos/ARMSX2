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

— (a preencher pela sessão que implementar)
