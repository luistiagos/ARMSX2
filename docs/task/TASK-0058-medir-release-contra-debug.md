# TASK-0058: medir `githubRelease` contra `githubDebug` no aparelho com o teto

- **Status:** concluída
- **Criada em:** 2026-08-30
- **Concluída em:** 2026-08-31 (medida no A12, boot frio dos dois, quadros casados)
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** item 3 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0058:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## 🔴 A premissa desta task estava errada: não são duas variáveis, são três

O texto abaixo (e o backlog) descrevem release-vs-debug como "R8 mais `debuggable`", com o núcleo
nativo igual dos dois lados porque o `CMakeCache.txt` do debug traz `-O3 -g`. **As flags são
mesmo iguais; o LTO não é.** Lido nas duas árvores que o AGP configura:

| | `githubDebug` | `githubRelease` |
|---|---|---|
| `CMAKE_CXX_FLAGS` | `-O3 -g` | `-O3 -g` |
| `CMAKE_BUILD_TYPE` | `Debug` | `Release` |
| **`LTO_PCSX2_CORE`** | **`OFF`** | **`ON`** |

Ou seja, o flavor de release **já** liga LTO no núcleo. A afirmação de "NÃO entra" mais abaixo —
que LTO é "uma terceira variável e entra depois, sozinha" — **não se sustenta**: ela não é
separável, porque medir `githubRelease` mede LTO junto. E LTO toca o código nativo, que é
justamente onde a [TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) mediu o gargalo
(`EE 100%`). Isso torna a medição *mais* interessante do que o backlog supunha, não menos.

### E isso deixa de ser teórico para a UB da TASK-0060

A [TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) registra que dividir por
`GetTickFrequency()` valendo zero é **comportamento indefinido em C++**, e que hoje se observa
`udiv → 0` só porque o compilador não enxerga o divisor através da fronteira de tradução.

**Com LTO ligado ele enxerga.** O build que vai para o cliente é exatamente o que pode inlinear
`GetTickFrequency()` dentro de `ShortSpinOn` e assumir que a divisão nunca acontece. Todo o
diagnóstico daquela investigação foi feito em `githubDebug`, sem LTO — o release podia estar se
comportando de outro jeito, e ninguém teria como saber. Consertar o contrato (o que a TASK-0060
fez) fecha isso; blindar cada divisão não fecharia.

## Como medir sem destruir os dados do usuário

O caminho ingênuo — `adb install -r` do release por cima do debug — **não funciona e é
destrutivo**. Verificado no aparelho:

- `armsx2_keystore.properties` existe, então o release é assinado com `retrosystem_release.jks`
  (SHA256 `D3:4A:78:8A:…`), **chave diferente** da do debug instalado. O `install -r` recusa.
- A "solução" seria desinstalar, e o diretório do app tem **14 GB**: 14 GB de ROMs, 17 MB de
  memory cards e os savestates. Desinstalar apaga tudo.

O caminho seguro, e que é o usado aqui: construir o release com **outro `applicationId`**
(`-Parmsx2.applicationId=come.nanodata.armsx2.perf`), instalar **ao lado**, semear só o necessário
no diretório próprio dele — o BIOS (4 MB), a ROM de teste (103 MB) e o `PCSX2-Android.ini`, para
que a configuração do núcleo seja idêntica — e desinstalar o pacote de teste no fim. O app real
não é tocado em momento nenhum.

## Contexto

Todo o desempenho medido na investigação do GOS saiu de um APK `githubDebug` (confirmado pelo
`sha256` do `base.apk` e pela flag `DEBUGGABLE` no `dumpsys package`). O núcleo nativo **não** está
sem otimização — o `CMakeCache.txt` do build debug traz `CMAKE_CXX_FLAGS=-O3 -g` com
`CMAKE_CXX_FLAGS_DEBUG` vazio —, mas o lado Java/Kotlin roda sem R8 e o processo é `debuggable`, o
que muda o caminho do JIT do ART.

Ninguém mediu a diferença. Pode ser 0% e pode ser 20%, e é a incógnita mais barata do backlog.

## O que já foi conferido, e dispensa o aparelho

O risco que o backlog levanta — "o release é ofuscado por R8, e o que o nativo alcança **por nome**
precisa de regra em `proguard-rules.pro`" — **está coberto**. A conferência é estática e foi feita:

| classe resolvida por nome no nativo | onde | regra que a mantém |
|---|---|---|
| `kr/co/iefriends/pcsx2/NativeApp` | `native-lib.cpp:452, 2593, 2609, 2651` | `-keep class kr.co.iefriends.pcsx2.** { *; }` |
| `kr/co/iefriends/pcsx2/HttpClient` | `common/HTTPDownloaderAndroid.cpp:48` | idem |
| `kr/co/iefriends/pcsx2/HttpClient$Response` | `common/HTTPDownloaderAndroid.cpp:68` | idem |
| `com/armsx2/BiosInfo` | `native-lib.cpp:4920` | `-keep class com.armsx2.BiosInfo { *; }` |

São **todos** os `FindClass` da árvore fora de `3rdparty`. Os métodos nativos declarados em Java
estão cobertos por `-keepclasseswithmembernames class * { native <methods>; }`.

Isso não torna o release seguro por inteiro — reflexão em Kotlin, `Class.forName`, e classes
citadas só no manifesto continuam sendo risco, e as regras existentes já mostram cicatriz disso
(SDL, ReLinker, Discord). Mas o caminho JNI, que era a preocupação nomeada, está fechado.

## Escopo

**Entra:**

- A tabela de `PerfLog` das quatro combinações: `{githubRelease, githubDebug} × {GOS vivo, GOS morto}`.
- Mesmo jogo, mesmo save, mesmo tempo de amostra, mesmo protocolo do backlog.

**NÃO entra:**

- Mudar `isMinifyEnabled`, o flavor publicado ou as regras do R8. Se a medição mostrar que o
  release ganha, ele já é o canal; se mostrar que perde, aí sim abre-se a discussão — com número.
- ~~LTO. O release do upstream liga `LTO_PCSX2_CORE`, e um build com LTO ainda não foi medido: é uma
  terceira variável e entra depois, sozinha.~~ **Riscado:** LTO não é separável de `githubRelease`,
  porque o flavor já a liga — ver a caixa no topo. A medição desta task inclui LTO, e dizer o
  contrário seria descrever um experimento que não é o que está sendo feito.

## O resultado

Galaxy A12, GOS morto, `10 Pin - Champions Alley` (PAL, alvo 50 fps), **boot frio dos dois lados**,
mesma ROM, mesmo BIOS, mesmo `PCSX2-Android.ini`. Seis janelas de `PerfLog` cada, comparadas em
**números de quadro casados** — o contador de quadros é o índice de tempo *do jogo*, então é ele que
garante que os dois lados estão na mesma cena:

| quadro (dbg / rel) | fps dbg | fps rel | **EE dbg** | **EE rel** | GS dbg | GS rel | GPU dbg | GPU rel |
|---|---|---|---|---|---|---|---|---|
| 754 / 792 | 25,1 | 26,2 | 100 | 100 | 35 | 36 | 15 | 17 |
| 2033 / 2174 | 42,4 | 45,7 | 61 | 50 | 84 | 83 | 51 | 51 |
| 3538 / 3682 | 50,0 | 50,0 | 61 | 49 | 84 | 82 | 52 | 51 |
| 5047 / 5190 | 50,0 | 50,0 | 61 | 51 | 84 | 83 | 52 | 51 |
| 6553 / 6697 | 50,0 | 50,0 | 60 | 49 | 84 | 82 | 51 | 52 |
| 8061 / 8206 | 50,0 | 50,0 | 63 | 50 | 85 | 82 | 52 | 51 |

**Em regime: EE 61,2% → 49,8%. Cerca de 19% menos trabalho na thread que é o gargalo.**
GS praticamente igual (84,3 → 82,4), GPU idêntica (51,5 → 51,2).

### O `fps` não muda, e é isso que quase me fez ler errado

Os dois lados marcam **50,0 fps**, porque os dois batem no teto do limitador de quadros do PAL. Num
jogo que já atinge o alvo, o release **não deixa mais rápido — deixa mais barato**. O ganho aparece
como folga de EE, não como quadros.

E é justamente essa folga que esta trilha do backlog persegue: com o teto do GOS cortando o clock
pela metade, 19% do orçamento de EE é a diferença entre alcançar o alvo e não alcançar. Num jogo que
*não* atinge o alvo (o `007` a ~29 de 59,94 registrado no bug da MTVU), os mesmos 19% saem em fps.

> ⚠️ **Uma leitura intermediária minha estava errada e vale registrar.** Comparei primeiro o release
> contra uma medição de debug de outra execução e li "release é ~2× mais rápido" — 50 fps contra
> 25,7. As duas amostras estavam em **cenas diferentes** do loop de atração (`GS 11%` contra `GS 83%`),
> e o 10 Pin tem fases com custo muito diferente. Casar por número de quadro é o que corrige, e é por
> isso que a tabela acima tem as duas colunas de quadro.

## Quatro variáveis, não uma — e qual é a suspeita

O ganho é do pacote inteiro. `githubRelease` difere de `githubDebug` em:

| variável | evidência |
|---|---|
| **`PCSX2_DEBUG`** | `build.ninja`: **391** linhas com `-DPCSX2_DEBUG` no Debug, **0** no Release |
| **`LTO_PCSX2_CORE`** | `ON` no Release, `OFF` no Debug |
| `NDEBUG` | 2045 linhas no Release, 0 no Debug |
| R8 | `minifyGithubReleaseWithR8` roda só no release |
| `debuggable` | `false` no release |

> 🔴 **Correção de 2026-09-01 — eu tinha nomeado o flag errado.** A versão anterior desta tabela
> dizia que o `-DNDEBUG` era quem compilava as asserções fora, e que ele era a suspeita principal.
> **Não é.** As asserções do emulador são `pxAssert` / `pxAssertMsg` / `pxFail`, e
> `common/Assertions.h:28` as gateia por `PCSX2_DEBUG` **ou** `PCSX2_DEVBUILD` — não por `NDEBUG`:
>
> ```cpp
> #if defined(PCSX2_DEBUG) || defined(PCSX2_DEVBUILD)
> #define pxAssertMsg(cond, msg) pxAssertRel(cond, msg)   // verificação viva
> #else
> #define pxAssertMsg(cond, msg) ((void)0)                // some
> #define pxAssumeMsg(cond, msg) ASSUME(cond)             // e ainda AJUDA o otimizador
> #endif
> ```
>
> `cmake/BuildParameters.cmake:355` define `$<$<CONFIG:Debug>:PCSX2_DEVBUILD;PCSX2_DEBUG;_DEBUG>`, e
> o `build.ninja` confirma: **391 unidades de compilação com `-DPCSX2_DEBUG` no debug, zero no
> release**. O `NDEBUG` continua diferindo, mas ele governa o `assert()` da libc, não o nosso.
>
> A conclusão de antes sobrevive — asserções vivas no núcleo do emulador —, mas pelo flag certo. E
> ela ficou **mais forte**: no release o `pxAssumeMsg` não só desaparece como vira `ASSUME(cond)`,
> uma dica que o compilador usa para otimizar melhor. Não é só deixar de gastar; é passar a ganhar.

**A suspeita principal é o `PCSX2_DEBUG`**, e o argumento é o próprio resultado: o ganho está
concentrado na **EE** (−19%) e não aparece em GS nem GPU. R8 e `debuggable` mexem no lado
Java/Kotlin, que durante o jogo não é o gargalo. Sobram as duas nativas, e asserções vivas dentro do
recompilador do EE são o tipo de custo que bate no EE e em mais nada.

**Não está isolado, e a task não finge que está.** Separar as asserções do LTO exige um terceiro
build — release com `LTO_PCSX2_CORE=OFF`, que isola o LTO com tudo o mais igual. O
`build.gradle.kts` já tem meio caminho: `-Parmsx2.pgo=generate` produz release com LTO OFF, mas
acrescenta instrumentação de PGO, que custa por si e sujaria a comparação. Falta uma alavanca limpa,
e ela é a próxima task, não esta.

## O que isso faz com o resto do backlog

Toda a investigação do teto do GOS — inclusive os números de referência do backlog, **8,5 fps com o
teto e 49,8 sem** — foi colhida em `githubDebug`, ou seja, **com asserções ativas no núcleo de
emulação**. Não invalida as conclusões (o teto do GOS é real e mede a Samsung, não o nosso código),
mas significa que os números absolutos daquela análise são o piso, e não o que o cliente vê.

## R8: passou, e sem regra nova

- Build: `minifyGithubReleaseWithR8` sem erro.
- Runtime: o app sobe, o catálogo carrega (6311 jogos), a biblioteca varre, o jogo dá boot e roda 8
  mil quadros. **Zero exceções no `emulog.txt`, zero entradas no buffer de crash do logcat.**
- Tamanho: 46,6 MB contra 93,8 MB do debug.

Ou seja, a conferência estática das regras de `-keep` (tabela acima) se confirma na prática. O
`Cuidado` que o backlog levantava para este item está fechado.

## Como validar

**Não instale um por cima do outro** — ver a caixa "Como medir sem destruir os dados do usuário"
no topo. O procedimento usado foi: release construído com
`-Parmsx2.applicationId=come.nanodata.armsx2.perf`, instalado ao lado, semeado com BIOS + ROM +
`PCSX2-Android.ini` do app real, e desinstalado no fim.

Para cada lado:

```bash
# 1. GOS morto
adb shell am force-stop com.samsung.android.game.gos
adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq   # tem de subir
# ... jogar 2 min ...
adb shell "grep PerfLog /storage/emulated/0/Android/data/come.nanodata.armsx2/files/logs/emulog.txt | tail -4"

# 2. GOS vivo — sair do app e voltar ressuscita o serviço; conferir o clock antes de medir
```

**Fazer isto só depois da [TASK-0055](TASK-0055-contadores-de-desempenho-que-nao-mentem.md).** Sem
os contadores de EE/GS/VU funcionando, a tabela mostra só `fps`, e um `fps` igual entre release e
debug não distingue "não mudou nada" de "mudou o gargalo de lugar".
