# TASK-0051: dar ao usuário a ação que desarma o limite do aparelho

- **Status:** concluída
- **Criada em:** 2026-08-29
- **Concluída em:** 2026-08-29
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
  — resolve a parte que é alcançável; o corte em si é do aparelho
- **Commit:** — (o vínculo é o prefixo `TASK-0051:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

A [TASK-0050](TASK-0050-detectar-limite-de-clock-do-aparelho.md) entregou o diagnóstico: o app já
detecta que o aparelho está segurando a CPU e diz isso ao usuário. Faltava a ação, e na época eu
tinha registrado que ela exigia um PC. **Estava errado**, e a medição que corrigiu isso está no bug:

| momento | clock | emulação |
|---|---|---|
| jogo rodando, GOS vivo | 1053 MHz | 8,5 fps |
| 30 s após forçar parada do GOS | 2002 MHz | 39,9 fps |
| 100 s após, processo ainda morto | 2002 MHz | **49,8 / 49,8 / 50,0 fps** |

Na página de informações do Game Optimizing Service o botão **Desativar está morto** (esmaecido, o
toque não produz diálogo nem mudança — verificado), mas **Forçar parada está vivo** e devolve a
velocidade cheia na hora. Essa página abre por intent documentada, que um app comum dispara.

O app continua **sem poder** desabilitar o GOS: `CHANGE_COMPONENT_ENABLED_STATE` é
`signature|privileged|role` e o nosso APK sideload não a tem. O que ele pode é levar o usuário até
o botão.

## Objetivo

Quem foi avisado do corte ganha, dentro do app, um caminho de um toque até a tela onde ele mesmo
desarma o corte — com a instrução do que tocar lá.

## Escopo

**Entra:**

- `ThrottleWatcher` passa a persistir `throttle.detected` (ligado quando o corte é detectado, e
  nunca desligado sozinho) e a expor `detected` como estado de Compose.
- `ThrottleWatcher.openVendorThrottlerSettings(context)` — dispara
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` com `package:com.samsung.android.game.gos`,
  devolve `false` e avisa pelo banner se nenhuma Activity resolver.
- Em Configurações → App, sob o interruptor do aviso: uma linha de instrução e um botão
  **Como resolver**, visíveis apenas quando o corte foi detectado **e** o aparelho é Samsung —
  porque a instrução é específica dela.
- Strings novas em `I18n.kt` (inglês, que é o fallback) e em `pt-BR.json`.

**Não entra:**

- Botão dentro do banner. O `WelcomeBanner` é texto transitório de 2,6 s, sem ação; dar-lhe botões
  mexe num componente compartilhado por outros avisos e é decisão de UI própria.
- Executar a parada forçada pelo app. Precisa de `FORCE_STOP_PACKAGES`, que é permissão de
  sistema — a mesma barreira do `pm disable-user`.
- Prometer permanência. O GOS **volta sozinho** — medido: ~40 min depois da parada forçada, sem
  `pm enable` e sem reiniciar, o processo estava vivo e o teto de volta. O texto ao usuário diz
  que é preciso repetir quando o jogo ficar lento de novo, em vez de vender conserto definitivo.

## Como validar

No SM-A127M:

1. Com `throttle.detected` ligado, Configurações → App mostra a linha de instrução e o botão
   **Como resolver** logo abaixo do interruptor do aviso.
2. O botão abre a página de informações do Game Optimizing Service (título
   "Game Optimizing Service", com **Forçar parada** ativo).
3. Com `throttle.detected` desligado, nem a instrução nem o botão aparecem.

## Resultado

Validada no SM-A127M em 2026-08-29, os três critérios:

1. **Sem detecção** (o `throttle.detected` nem existia nas prefs, porque o APK anterior não o
   gravava): o interruptor aparece sozinho, sem instrução e sem botão — a lista vai direto dele
   para "Biblioteca de música".
2. **Com detecção**, e ela foi obtida do jeito certo — não forcei a preferência: rearmei o
   `throttle.warned`, abri um jogo com o GOS vivo, o detector disparou e gravou
   `throttle.detected=true` sozinho. A instrução e o botão **Como resolver** apareceram sob o
   interruptor.
3. **O botão abre a página certa**, disparado de dentro do app: o foco foi para
   `com.android.settings/…InstalledAppDetails`, mostrando "Game Optimizing Service" com
   **Forçar parada** ativo e **Desativar** esmaecido.

Nesta rodada também ficou medido que **o GOS volta sozinho**: cerca de 40 min depois da parada
forçada, sem `pm enable` e sem reiniciar o aparelho, o processo estava vivo (`pidof` → 11295) e o
teto de 1053 MHz tinha voltado. O texto entregue ao usuário foi reescrito por causa disso — a
versão anterior dizia "talvez seja preciso repetir depois de reiniciar o aparelho", o que prometia
mais do que a medição sustenta.
