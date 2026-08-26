# Avaliação: transplantar a nossa camada Android sobre o core do ARMSX2 oficial

- **Data:** 2026-08-25 (revisto no mesmo dia, com verificação contra `upstream/master` = `be72a8e1eb`)
- **Pergunta original:** criar uma branch com a versão mais recente do `ARMSX2/ARMSX2` e recolocar por
  cima as nossas modificações (catálogo, BIOS embutida, fila de download, melhorias). Viável? Qual o
  esforço?
- **Contexto:** o nosso core `pcsx2/` está cerca de mil commits atrás. Nesta sessão portámos 3.

> **Nota de vocabulário — isto não é um `git rebase`.**
> `git merge-base HEAD upstream/master` devolve **vazio**: o nosso repositório é um snapshot sem
> história partilhada com o upstream, portanto não existe ancestral comum e
> `git rebase --onto upstream/master` não tem sobre o que operar. A operação real é um **transplante
> de árvore**: partir da árvore do upstream e recolocar o nosso módulo Android por cima, num commit
> inicial sem ascendência nossa. A versão anterior deste ficheiro chamava-lhe "rebase" — a Opção A
> descrevia a coisa certa em prosa, mas o nome induzia o comando errado logo no primeiro passo.

---

## 1. O que a medição mostrou

Todos os números abaixo foram reverificados contra `upstream/master` em 2026-08-25.

### O nosso lado — o que teria de ser recolocado

| Área | Arquivos | Linhas |
|---|---|---|
| `activities/` (Home, Main, Settings, Onboarding…) | 6 | 12.104 |
| `utils/` | 19 | 5.600 |
| `hid/` | 4 | 1.702 |
| `catalog/` | 7 | 1.697 |
| `input/` | 6 | 1.697 |
| `updates/` | 2 | 475 |
| **Total Java** | **48** | **24.042** |
| Recursos XML | 115 | — |
| `main.cpp` (ponte JNI) | 1 | 2.463 |

```sh
find app/src/main/java -name '*.java' | wc -l
find app/src/main/java -name '*.java' -exec cat {} + | wc -l
```

### O lado deles

- **134 arquivos Kotlin + 16 Java** — UI reescrita em Compose.
- `native-lib.cpp` com **5.050 linhas** e **136 métodos JNI** (o nosso `main.cpp` tem 2.463 linhas e
  56 métodos).
- Build em `build.gradle.kts` (Kotlin DSL); o nosso é Groovy.
- Android em `platforms/android/`; o nosso em `app/`.
- **7.975 arquivos** de 3rdparty vendorados em `platforms/android/app/src/main/cpp/3rdparty/`
  (adrenotools, oboe, cubeb, ccc, imgui, shim lsfg).

### Quão atrás estamos, e por que o número é aproximado

Sem merge-base não existe `HEAD..upstream/master`, logo **não há contagem exata** — qualquer número é
uma estimativa por data de corte. Contando commits do upstream que tocam `pcsx2/`:

```sh
git rev-list --count upstream/master --since=<data> -- pcsx2/
```

| Desde | Commits em `pcsx2/` |
|---|---|
| 2026-01-01 | 1.417 |
| 2026-02-01 | 1.314 |
| 2026-03-01 | 1.256 |
| 2026-04-01 | 1.151 |

O nosso snapshot situa-se por volta de maio de 2026, o que coloca a distância na ordem dos **~1.050
commits**. Tentar fixar o ponto exato por blob (`git rev-parse <commit>:pcsx2/VMManager.cpp`) falha,
porque já modificámos esses ficheiros localmente. **Trate o número como ordem de grandeza, não como
medida.**

### O achado que torna a ideia viável

**O upstream manteve o namespace JNI `Java_kr_co_iefriends_pcsx2_*`**, mesmo tendo reescrito a UI em
Kotlin e mudado o `applicationId` para `com.armsx2`. A ponte nativa é compatível por nome com o nosso
Java.

**Nota tranquilizadora, porque é o tipo de coisa que gera pânico a meio do transplante:** o
`applicationId` é **independente** do package Java `kr.co.iefriends.pcsx2` que os símbolos JNI
codificam. Mantemos `come.nanodata.armsx2` sem tocar num único nome de método nativo.

#### Compatibilidade por nome não basta — as assinaturas foram conferidas

JNI liga **por nome**, não por assinatura. Um método com o mesmo nome e parâmetros diferentes
*compila e liga na mesma*, e corrompe a pilha em runtime. Foram comparadas as listas de parâmetros
dos 33 métodos comuns:

| | Contra `be72a8e1eb` (18/08) | Contra `662b114168` (26/08) |
|---|---|---|
| Comuns **e com assinatura idêntica** | 31 | **30** |
| Comuns **com assinatura divergente** (reconciliar) | 2 | **3** |
| Só nossos (reimplementar) | 23 | **23** |
| Só deles (ganho de graça) | 103 | **110** |

> Remedido em 2026-08-26 pelo [spike](spike-transplante-upstream-2026-08-26.md), com
> `scripts/compare_jni_surface.py`. A coluna de 18/08 não estava errada: o upstream andou.

As três divergências:

| Método | Nosso | Deles |
|---|---|---|
| `NativeApp_getGameCRC` | **retorna `jint`** | **retorna `jstring`** — CRC formatado em hexadecimal |
| `NativeApp_initialize` | `(jstring p_szpath, jint p_apiVer)` | `(jstring p_szpath, jstring p_szbiosfolder, jint p_apiVer)` |
| `NativeApp_clearAchievementsHostOverride` | `(jboolean restore_hardcore, jboolean hardcore_enabled)` | `()` — remove os valores base e reinicia; semântica diferente |

**A do `getGameCRC` é de outra natureza e merece ser lida devagar.** As outras duas divergem em
parâmetros; esta diverge no **tipo de retorno**, e o lado Java declara `int`. Como JNI liga por nome,
isto compila, liga e roda: o `int` recebe os 32 bits baixos de um ponteiro `jstring`. Não crasha — e
é justamente por isso que é o pior dos três. Produz um CRC errado em silêncio, e o CRC alimenta a
busca de capas, os overrides de GameDB e a chave de decisão do `GraphicsHealthMonitor`.

#### Os 23 "só nossos" não custam todos o mesmo

A versão anterior tratava-os como bloco. Não são:

- **Baratos.** `getSetting` não existe no upstream (eles só têm `setSetting`) e é por onde todo o
  nosso settings **lê** — parece grave, mas a nossa implementação
  ([`main.cpp:1460`](../app/src/main/cpp/main.cpp#L1460)) apenas chama
  `s_settings_interface->GetBoolValue(...)`, e o `native-lib.cpp` deles usa `s_settings_interface` em
  **42** sítios. O objeto de suporte sobrevive; reimplementar é quase de graça. O mesmo padrão vale
  para a maioria dos getters de estado.
- **Nossos por direito.** Os 5 da ponte RetroAchievements (`RetroAchievementsBridge_native*`).
- **A investigar.** Os que dependem de comportamento do core que pode ter mudado em ~1.050 commits —
  `canBootVm`, `hasValidVm`, `renderGpu`, `reloadDataRoot`, `setCustomDriverPath`.

Vários dos 23 (`setTemporaryRenderer`, `getCurrentRenderer`, `enableGraphicsSafeMode`) foram criados
nesta própria sessão.

### A colisão que está na primeira chamada nativa

O parâmetro extra do `initialize` **não é um parâmetro, é um modelo de dados divergente**. O
comentário deles explica: a pasta de BIOS é fixada em `externalFilesDir/bios` porque o systemDir
escolhido pelo utilizador não consegue necessariamente hospedar BIOS sob scoped storage no Android
11+. Nós resolvemos o mesmo problema com `DataDirectoryManager.getDataRoot()` +
`NativeApp_reloadDataRoot` (só nosso).

São **duas respostas concorrentes ao mesmo problema, a colidir na primeira chamada nativa que o app
faz**. É risco mais concreto do que "23 métodos" em abstrato, e deve ser decidido antes de escrever
código: ou adotamos a separação Folders/Bios deles, ou mantemos o nosso override e assumimos a
divergência para sempre.

### O que é genuinamente nosso e não existe lá

Verificado na árvore deles: **não há catálogo de ROMs nem fila de download.** `ControllerSkinStore`,
`TextureCatalog` e `ConfigStore` são outras coisas. Atualização in-app **existe** do lado deles
(`platforms/android/app/src/{github,play}/java/com/armsx2/update/UpdaterEntry.kt`), mas é outro
mecanismo — não lê o nosso `version.json` nem conhece o nosso canal.

---

## 2. Savestates: o custo é de convergir, não de transplantar

| | Versão |
|---|---|
| Nossa | `0x9A54` |
| Upstream | `0x9A59` |

**Cinco versões de diferença.** Isto invalida os savestates de todo utilizador instalado. Memory cards
são formato PS2 e sobrevivem; savestates não. É o tipo de coisa que gera a mesma reação do "pacote
inválido": o utilizador atualiza e perde onde estava.

**A conclusão que a versão anterior deste documento tirava ao contrário.** A quebra era apresentada
como argumento de cautela contra o transplante. Não é: **qualquer** port de commits de core que mexa
na estrutura de savestate força o mesmo bump. É custo de convergir com o upstream, não custo de
escolher a Opção A. Continuar a portar commit a commit não evita a quebra — dispersa-a por várias
versões, em momentos imprevisíveis, cada um deles uma surpresa para o cliente.

Isso inverte a recomendação de calendário: **fazer a quebra uma vez, deliberadamente e anunciada no
app**, é melhor do que sofrê-la aos bocados. O que continua a ter de ser explícito é o aviso — não a
descoberta pelo cliente.

Outras restrições que **não podem** quebrar (ver [`CLAUDE.md`](../CLAUDE.md)):

- `applicationId come.nanodata.armsx2` — renomear quebra toda instalação existente. (Ver acima: é
  independente do namespace JNI, portanto não é tocado pelo transplante.)
- Chaves de `SharedPreferences` e caminhos de dados. O upstream usa o mesmo `PCSX2-Android.ini`, o que
  ajuda — mas ver a colisão Folders/Bios acima.
- O mecanismo de atualização in-app (`version.json`, `versionCode`) precisa continuar a funcionar na
  transição, senão os utilizadores ficam órfãos. Note-se que o upstream tem updater próprio: são dois
  mecanismos, e um tem de ganhar.

---

## 3. Dois formatos possíveis

### Opção A — a nossa camada Android sobre o core deles

Partir da árvore do upstream e colocar o nosso módulo Android por cima, descartando a UI Compose
deles.

- **Fica como está:** 24.096 linhas de Java, 115 XML, catálogo, download, updates, HID, input.
- **Precisa de trabalho:** reimplementar 23 métodos JNI e reconciliar **3** contra o core novo; fundir
  o nosso `main.cpp` (2.463 linhas) com o `native-lib.cpp` deles (5.138); mover o build para
  `platforms/android/`; reaplicar as nossas correções de engine.
- **Ganha de graça:** ~1.050 commits do core, incluindo as TASK-0002, 0003 e 0008.
- **Perde:** os 110 métodos JNI deles ficam inacessíveis até alguém ligá-los — e, medido em
  2026-08-26, **63.998 linhas de app em Compose**: assistente de configuração, hub de definições,
  atualizador. A formulação "preserva o que temos" escondia que o outro prato da balança pesa 2,7×
  o nosso. Continua podendo ser a escolha certa, porque o catálogo e a fila de download são nossos e
  não existem lá — mas é escolha com preço, não neutralidade.

### Opção B — adotar a UI deles e portar as nossas features para Compose

Custeada, para não ser um espantalho:

- **Descarta** 12.104 linhas de Activities **e** os 115 XML — a camada de apresentação inteira.
- **Reescreve em Compose** as telas de catálogo, fila de download e updates, sobre ~2.172 linhas de
  lógica que migram quase intactas (`catalog/` 1.697 + `updates/` 475).
- **Herda** o updater deles com flavors github/play, que teria de ser reconciliado com o nosso
  `version.json` / canal R2 — ou abandonado, com o custo de migração dos instalados.
- **Mantém** `hid/` (1.702) e `input/` (1.697), que falam com Android e não com a UI — mas ambos
  teriam de ser religados a um host Compose.

Ou seja: descarta ~12.200 linhas, reescreve a apresentação de ~2.200 linhas de lógica, e abre uma
frente nova no canal de distribuição. Muito maior no curto prazo, muito mais próximo do upstream no
longo.

---

## 4. Avaliação

**Viável, sim** — e mais do que se esperava, por causa do namespace JNI compatível e de 31 de 33
assinaturas baterem. Não é "reescrever o app".

**O esforço não está no Java, está na fronteira.** Boa parte das nossas 24 mil linhas não toca o core:
catálogo, download, updates, HID e input falam com Android, não com o emulador. O trabalho
concentra-se em duas frentes reais e uma que se pode rebaixar:

1. **A ponte JNI** — 23 métodos a reimplementar e 2 a reconciliar contra um core que mudou ~1.050
   commits, mais a decisão Folders/Bios do `initialize`. É aqui que mora o risco de descobrir que uma
   função nossa dependia de comportamento que já não existe.
2. **O `main.cpp` × `native-lib.cpp`** — 2.463 linhas nossas a fundir com 5.050 deles. As nossas
   incluem o perfil de dispositivo, os defaults de desempenho e os ganchos de asset/telemetria. O
   upstream tem perfil próprio; conflito garantido, e as decisões medidas deles devem prevalecer (ver
   [`performance-optimization.md`](performance-optimization.md)).
3. **A estrutura de build — menor do que parecia.** As árvores já são paralelas: o nosso
   `app/src/main/cpp/{3rdparty,cmake,common,pcsx2,main.cpp,CMakeLists.txt}` tem exatamente a forma do
   `platforms/android/app/src/main/cpp/` deles. É sobretudo movimento de caminho e conversão
   Groovy → Kotlin DSL, não trabalho de arquitetura.

**Recomendação: Opção A**, por três razões — preserva o que nos diferencia, não exige reescrever UI, e
é reversível: a branch atual continua lá.

**Ordem de execução.** Há uma correção não publicada que os clientes estão à espera (a tela branca), e
um transplante é exatamente o tipo de mudança que não se faz com um incêndio aberto. A ordem sã
continua a ser: publicar o que está pronto, confirmar em campo, e só então abrir a branch do
transplante — mas por causa da inversão da secção 2, **não adiar indefinidamente**: cada versão
portada a conta-gotas paga um pedaço do mesmo custo de savestate sem colher o benefício.

**Sobre estimar em dias:** não há base honesta para isso, e chutar seria pior que não responder. O que
se pode afirmar é a forma do trabalho e onde está o risco.

---

## 5. O spike — o que ele deve medir

> **Parcialmente executado em 2026-08-26.** Resultados, incluindo o orçamento do build medido
> (5 min 33 s para reconstruir e linkar o core de 325 TUs a `-j 4`) e o custo real da Opção A
> (a camada de app deles tem **63.998** linhas em Compose, contra as nossas 24.096 em XML/Views),
> em [`spike-transplante-upstream-2026-08-26.md`](spike-transplante-upstream-2026-08-26.md).

A versão anterior propunha um spike de um dia cujo critério era *"o número de erros na fronteira JNI é
a estimativa real"*. **Esse número já está nesta página** (23 ausentes + 2 divergentes), obtido
estaticamente em minutos, sem compilar nada. O spike não deve gastar um dia a redescobri-lo.

O spike deve responder ao que só se responde a correr código:

1. **O core novo dá boot com um jogo real** depois de removidos o perfil de dispositivo e os defaults
   do nosso `main.cpp`? É a pergunta que decide se as decisões medidas deles nos servem.
2. **O crash do SotC (`addr=0x12218`) sobrevive ao transplante do JIT ARM64?** Se desaparecer, poupa
   uma investigação inteira.
3. **A colisão Folders/Bios:** com o `initialize` de 3 parâmetros deles, a nossa BIOS e os nossos
   memory cards continuam a ser encontrados?

**O custo está subestimado, e não pelo porte.** Trazer a árvore deles significa um build NDK a frio
sobre 7.975 arquivos de 3rdparty vendorados, com `-j 4` obrigatório para o clang não estourar a RAM.
Um dia é otimista para build **e** porte — orçamente o build em separado.

---

## 6. O que fica de graça no transplante

Vale registar, porque muda o cálculo: das cinco tasks de código desta sessão, **três desaparecem** por
serem código do upstream — TASK-0002 (perfil de GPU), TASK-0003 (assinatura de driver no shader cache)
e TASK-0008 (MFIFO/SPR). Só duas precisariam de ser recolocadas:

- **[TASK-0006](task/TASK-0006-diagnostico-boot-gs.md)** (diagnóstico de boot do GS) — nossa, e vale
  manter. **Mas há sobreposição a registar:** o upstream já emite marcadores próprios
  `@@ANDROID_GL_INIT@@ stage=...` (7 pontos em `GSDeviceOGL.cpp`) que nós não temos. São mecanismos
  diferentes — o nosso é `Host::ReportGraphicsBootDiagnostics()` → JNI → telemetria — logo
  pós-transplante ficariam dois caminhos de diagnóstico. O barato é alimentar os marcadores de estágio
  deles no nosso gancho de telemetria, em vez de manter os dois.
- **[TASK-0007](task/TASK-0007-cas-precisao-gles.md)** (precisão GLES do CAS) — nossa, e provavelmente
  desnecessária lá: o `CreateCASPrograms()` do upstream não tem ramo GLES nenhum (fica atrás de
  `GLAD_GL_VERSION_4_2 && GLAD_GL_ARB_compute_shader`, com header `#version 420`), ou seja, em GLES a
  feature simplesmente não liga.

O crash do SotC (`addr=0x12218`) é candidato a desaparecer sozinho: o upstream reescreveu o JIT ARM64
inteiro (`3e077eff9b`, 2026-07-19, "Merge yaps2: arm64 JIT transplant + test/perf/libretro
infrastructure"), e `pcsx2/arm64` é a área com mais arquivos tocados. Isso é hipótese, não promessa —
mas é testável no spike, e seria uma resposta bem mais barata que o detector de valor-veneno.
