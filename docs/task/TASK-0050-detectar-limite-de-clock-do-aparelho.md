# TASK-0050: avisar quando o aparelho está segurando o clock da CPU durante o jogo

- **Status:** concluída
- **Criada em:** 2026-08-29
- **Concluída em:** 2026-08-29
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0050:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

O bug ligado mede, em seis rodadas no SM-A127M, que o Game Optimizing Service da Samsung prende os
8 núcleos em 1053 MHz de 2002 MHz enquanto o nosso jogo está em primeiro plano, e que a emulação cai
de 49,8 fps para 8,6 fps por causa disso. Ali também estão registradas as quatro tratativas testadas
e descartadas — inclusive `android.game_mode_config`, que **não** alcança frequência de CPU.

**Não há correção.** O que sobra é o app parar de parecer culpado por uma lentidão que não é dele:
hoje o usuário abre o jogo, vê um terço da velocidade e conclui que o emulador é ruim.

## Objetivo

Enquanto um jogo roda, detectar que o aparelho não deixa a CPU passar de uma fração do próprio teto,
e dizer isso ao usuário **uma vez**, em texto que ele entenda.

## Escopo

**Entra:**

- `com.armsx2.ThrottleWatcher` — novo objeto, no mesmo formato do [`BatteryWatcher`](../../platforms/android/app/src/main/java/com/armsx2/BatteryWatcher.kt):
  `enabled`/`load()`/`set()` persistidos em `MainActivityRuntime.prefs`, `start()`/`stop()`, aviso
  por `WelcomeBanner.show(I18n.get(...))`.
- Engate: `start()` nos dois pontos em que a emulação começa (boot de jogo e boot de BIOS, os dois
  `emulationOwnsOrientation = true` de `MainActivityRuntime.kt`), `stop()` em
  `onReturnedToLibrary()` — o caminho terminal único documentado ali —, e `load()` junto do
  `BatteryWatcher.load()`.
- Interruptor em Configurações → Aplicativo, ao lado do aviso de bateria.
- Strings novas em `I18n.kt` (inglês, que é o fallback de `I18n.get`) e em `pt-BR.json`.
- Uma linha no log de sessão (`@@ANDROID_THROTTLE@@`) com teto, pico e velocidade medidos, para o
  suporte enxergar o mesmo que o usuário.

**Não entra:**

- Corrigir o corte. Não é possível de dentro do app — está provado no bug.
- Executar `pm disable-user` ou qualquer outra ação no sistema. O app não pode, e não deve fingir
  que pode.
- O giro da thread MTVU e o núcleo queimado pelo fundo animado da biblioteca. Estão registrados no
  mesmo bug como achados de lado e **não** são esta task.
- Telemetria. O aviso é local; se virar telemetria, é outra task.

## Como decide

Por *policy* de cpufreq (um diretório por cluster em `/sys/devices/system/cpu/cpufreq/policy*`):

- `cpuinfo_max_freq` é o teto de hardware, lido uma vez;
- `scaling_cur_freq` é amostrado a cada 3 s enquanto há VM.

Regra: depois de **20 amostras com a emulação abaixo de 92%** de velocidade
(`NativeApp.getEmuSpeedPercent()`, que é `PerformanceMetrics::GetSpeed()` = `fps / frameRate * 100`),
se **nenhum** cluster tiver chegado a 70% do próprio teto, avisa.

As três condições existem para não mentir:

1. **Só com a emulação atrasada.** Num aparelho rápido o governador segura o clock porque não
   precisa dele — ali um clock baixo é correto, não é defeito.
2. **Pico, não média.** Basta um cluster ter alcançado o teto uma vez para provar que o aparelho
   não está preso.
3. **Todos os clusters.** Em big.LITTLE as nossas threads podem estar no cluster pequeno; se o
   grande subiu, não há corte.

Se o sysfs não puder ser lido (SELinux varia por aparelho), o vigia se desliga sozinho e nunca mais
tenta — sem aviso, sem log de erro repetido.

## Validação

No SM-A127M, com o mesmo jogo e o mesmo protocolo das medições do bug:

1. **GOS ligado** → o aviso aparece dentro de ~1 min de jogo, e o log de sessão traz a linha
   `@@ANDROID_THROTTLE@@` com pico ≈ 1053000 e teto 2002000.
2. **GOS desabilitado** (`pm disable-user --user 0 com.samsung.android.game.gos`) → nenhum aviso, e
   nenhuma linha no log, na mesma duração de sessão.
3. Interruptor desligado em Configurações → Aplicativo → nenhum aviso com o GOS ligado.

O item 2 é o que importa: um detector que avisa sempre não detecta nada.

## Resultado da validação

Feita em 2026-08-29 no SM-A127M, com o APK `githubDebug` deste commit, aparelho partindo de
AP 30,6 °C. Entre as rodadas o `throttle.warned` foi rearmado por `run-as` para o aviso poder
disparar de novo.

| # | condição | clock | emulação | avisou? |
|---|---|---|---|---|
| 1 | GOS ligado | 1053 MHz | 8,6 fps | **sim**, 62 s após abrir o jogo |
| 2 | GOS desabilitado | 2002 MHz | 50,1 fps | **não**, em 185 s de jogo |
| 3 | GOS ligado, interruptor desligado | 1053 MHz | 26–27 fps | **não**, em 150 s de jogo |

A linha gravada na rodada 1, com os números que a medição do bug previa:

```
@@ANDROID_THROTTLE@@ peakKHz=1053000 maxKHz=2002000 pct=52 speed=19 manufacturer=samsung
```

O banner foi fotografado sobre o jogo em execução, com o texto da variante Samsung:
*"O Game Optimizing Service da Samsung está limitando a CPU a 52%."* A linha
**Aviso de limite do aparelho** aparece em Configurações → App, logo abaixo de Avisos de bateria,
e escreve `throttle.warnings` como esperado.

A rodada 2 é a que dá valor ao detector: mesmo aparelho, mesmo jogo, mesma duração, e ele fica
calado quando não há corte.
