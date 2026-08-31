# TASK-0058: medir `githubRelease` contra `githubDebug` no aparelho com o teto

- **Status:** aberta
- **Criada em:** 2026-08-30
- **Concluída em:** —
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

## Como validar

Instalar os dois APKs em sequência (mesma `versionCode`, `adb install -r`), e para cada um:

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
