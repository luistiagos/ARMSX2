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

## Por que está aberta e não em andamento

**Esta task é uma medição, e não há aparelho nesta sessão.** Não dá para entregá-la escrevendo
código: o que ela produz é uma tabela de quatro linhas colhida num Galaxy A12. Fica registrada
para que o item 3 do backlog tenha um lugar, e para que o trabalho estático que *era* possível —
a conferência do R8, abaixo — não fique escondido dentro de outra task.

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
- LTO. O release do upstream liga `LTO_PCSX2_CORE`, e um build com LTO ainda não foi medido: é uma
  terceira variável e entra depois, sozinha.

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
