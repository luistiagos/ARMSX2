# TASK-0083: a escolha de ANGLE chega ao core quando ela é por jogo

- **Status:** concluída
- **Criada em:** 2026-09-04
- **Concluída em:** 2026-09-04
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum — ela **não** corrige a
  [tela preta do GL em Mali-G52 r38](../bugs/open/armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md);
  torna alcançável o contorno que a investigação dessa mesma sessão mediu
- **Commit:** — (o vínculo é o prefixo `TASK-0083:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

A investigação da tela preta do OpenGL no `SM-A127M` (Mali-G52, driver ARM r38p1) chegou, nesta
sessão, a uma medida que fecha a causa e que está escrita no relatório do bug: **o mesmo jogo, no
mesmo aparelho, com estatísticas de GS byte a byte idênticas, renderiza quando o OpenGL passa por
ANGLE e fica preto quando passa pelo driver GLES nativo da ARM.**

| braço | GL_VENDOR | OSD do GS | resultado |
|---|---|---|---|
| driver do sistema | `ARM` | `50216 PRIM \| 50 DRW \| 53 DRWC \| 0 BAR \| 11 RP \| 0 RB \| 5 TC \| 12 TU` | preto |
| ANGLE | `Google Inc. (ARM)` | **a mesma linha** | imagem |

Ou seja: o emulador emite o mesmo trabalho nos dois braços; quem difere é o driver. O contorno
correto para esse aparelho, então, **não** é trocar de backend (o que muda semântica de emulação —
[piso de Z](../bugs/done/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md)), é
continuar em OpenGL e trocar a **implementação de GL**. Isso já existe no app: a chave
`useAngleOpenGL`, que vira `ARMSX2_ANGLE_EGL_LIBRARY` e faz `GLContextEGL::LoadEGL` abrir
`libEGL_angle.so` em vez de `libEGL.so` do sistema.

## O defeito que essa medição expôs

Ao ligar ANGLE **pelo menu em jogo** — aba Renderizador → ANGLE → "Aplicar e Reiniciar" — nada
acontece. Medido no aparelho: o log não emite nenhuma linha `@@ANGLE@@` nesse caminho e o
`GL_VENDOR` continua `ARM`. Ligar a mesma chave no arquivo de preferências, no nível **global**, com
a app reiniciada, funciona (`GL_VENDOR: Google Inc. (ARM)`).

São duas falhas independentes, ambas em código nosso (`platforms/android/app/src/main/java/`), e
nenhuma delas em `pcsx2/`:

1. **`applyAngleEnv` lê só o global.** Ela chama `ConfigStore.loadGlobal()` e testa
   `settings.renderer == "opengl"` no global. O menu em jogo grava em `SettingsScope.Game` (é o que
   `InGameOverlay.saveSettings` faz quando há serial), e `Settings.diff`/`Settings.merge` já sabem
   guardar e ler `useAngleOpenGL` por jogo — só o consumidor não olha para lá. O comentário da
   função diz isso em voz alta: *"Uses the GLOBAL settings; per-game renderer overrides are out of
   scope for v1"*. O efeito colateral é o mesmo para o `renderer`: quem tem global `vulkan` e
   por-jogo `opengl` + ANGLE também não liga ANGLE nenhum.
2. **O caminho de "Aplicar e Reiniciar" não chama `applyAngleEnv`.** As duas únicas chamadas estão
   em `launchGame` e na inicialização da Activity. "Aplicar e Reiniciar" é
   `MainActivityRuntime::restart` → `stop(restartAfterStop = true)` → `start()`, e `start()` não
   passa por `launchGame`. Então a variável de ambiente fica com o valor do arranque.

Há ainda uma terceira, menor, no mesmo lugar: em `launchGame` a chamada acontece **antes** de
`currentGame.value = info` (linha 1013 contra 1024), então mesmo que ela resolvesse por jogo,
resolveria pelo jogo **anterior**.

## Escopo

**Entra:**

- `runtime/AngleDriver.kt` (novo) — a decisão como função **pura**: renderizador resolvido + chave +
  presença das duas `.so` → `Enabled` / `MissingLibs` / `Off`. Pura para ser testada sem aparelho, no
  mesmo molde do `RendererRecovery` da TASK-0082.
- `runtime/MainActivityRuntime.kt` — `applyAngleEnv` passa a aceitar as `Settings` já resolvidas
  (e, quando não recebe nenhuma, resolve por `currentGame.value?.settingsKey`, que devolve o global
  quando não há jogo); `applyRendererPrefs` passa a chamá-la com o `resolved` que ela já calcula,
  antes de qualquer JNI que possa reabrir o dispositivo GS; a chamada de `launchGame`, que roda cedo
  demais e com o jogo errado, sai.
- `app/src/test/.../runtime/AngleDriverTest.kt` — a tabela da decisão.
- O relatório do bug, com a medição inteira: o que foi eliminado, com que número, e o que sobrou.

**Não entra:**

- **Qualquer coisa em `pcsx2/`, `common/` ou `3rdparty/`.** A medição diz que o core emite o mesmo
  trabalho nos dois braços; não há correção de motor a fazer aqui, e portanto também não há
  contribuição a mandar para o upstream por esta task.
- **Ligar ANGLE sozinho em Mali r38.** Seria o quarto movimento do mesmo tipo que o
  [plano gráfico](../plano-grafico-mali-convergencia-upstream.md) proíbe: alcance global a partir de
  um jogo em um telefone. A evidência aqui é de **um** título; a regra
  `gl-arm-g52-r38-auto-vulkan` saiu na [TASK-0072](TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md)
  exatamente por isso.
- **Pôr ANGLE na escada de "a imagem não apareceu"** (TASK-0082). É a continuação natural e está
  anotada no bug como próximo passo, mas muda uma escada já validada em aparelho e merece a sua
  própria task.
- Fechar o bug da tela preta. A causa está fora do nosso código; o relatório continua aberto com o
  que foi medido.

## Como será validado

1. **Teste de unidade** — `:app:testGithubDebugUnitTest`, cobrindo as linhas da tabela de decisão
   (ligado, desligado, renderizador diferente de OpenGL, `.so` ausente).
2. **No aparelho (`SM-A127M`, Mali-G52 r38p1, Android 13), APK `githubDebug`** — com
   `config.game.SLUS-20751` contendo `useAngleOpenGL:true` e `renderer:"opengl"`, e o **global**
   com `useAngleOpenGL` em `false`, bootar 007: Everything or Nothing e comprovar no log
   `GL_VENDOR: Google Inc. (ARM)` mais `@@ANGLE@@ enabled`, e por captura de tela que a imagem
   aparece.
3. **Que o global sozinho continua funcionando** — sem override por jogo, o comportamento de hoje
   não muda.

## O que a validação deu — 2026-09-04

**1. Teste de unidade.** `:app:testGithubDebugUnitTest`: **37 testes, 0 falhas**, dos quais 5 são o
`AngleDriverTest` novo. O `I18nKeysTest` roda junto e passa (esta task não acrescenta chave de
tradução).

**2. No aparelho `SM-A127M` (Mali-G52 r38p1, Android 13), APK `githubDebug`** (versionCode 2004, o
mesmo já instalado — `adb install -r` aceita a reinstalação e as preferências sobrevivem). Com o
**global** em `useAngleOpenGL=false` e apenas `config.game.SLUS-20751` carregando
`{"upscaleFloat":1.25,"renderer":"opengl","useAngleOpenGL":true}`:

```
09-04 14:57:52 @@ANGLE@@ off     renderer=opengl useAngle=false gsBackThread=0   <- init da Activity, sem jogo: global
09-04 14:57:53 @@ANGLE@@ enabled renderer=opengl useAngle=true  gsBackThread=0 egl=.../lib/arm64/libEGL_angle.so
09-04 14:57:55 GL_VENDOR: Google Inc. (ARM)
09-04 14:57:55 GL_RENDERER: ANGLE (ARM, Vulkan 1.3.213 (Mali-G52 (0x72120000)), Mali-G52-38.1.0)
```

As duas linhas juntas são a prova exata: a primeira mostra o global (falso) e a segunda mostra a
resolução por jogo (verdadeiro) no arranque da VM. **Antes da correção o mesmo arquivo de
preferências produzia só a primeira**, e o `GL_VENDOR` ficava em `ARM`.

E a imagem aparece: capturas em +105 s e +160 s com md5 `e8315ec15af85baa5053148f90bb98e6` e
`d43122017d7d381bd8ac9598244b11ab`, com **12.244** e **32.546** cores distintas na área de render —
contra a área de **uma** cor uniforme, byte a byte idêntica entre capturas, do braço com o driver do
sistema.

**3. Global sozinho continua funcionando.** Foi o braço usado pela própria investigação, antes da
correção: global `useAngleOpenGL=true`, sem chave por jogo, dá `@@ANGLE@@ enabled` e a imagem
(capturas a +100/130/160/190/220 s, md5 sempre diferente). A resolução por jogo devolve o global
quando não há chave gravada, então esse caminho não mudou.

### O que ficou sem provar

- **Alternar ANGLE de uma sessão para outra dentro do mesmo processo.** `LoadEGL`/`UnloadEGL` são
  contados por referência e a biblioteca é fechada quando o último contexto morre, então em tese
  funciona; o que foi medido em aparelho foi sempre com o processo reiniciado entre os braços. O
  caminho "Aplicar e Reiniciar" agora **chama** `applyAngleEnv`, mas o que se comprovou dele foi a
  chamada, não a troca de biblioteca a quente.
- **O caso `MissingLibs`.** Está coberto por teste de unidade, não por aparelho — exigiria um APK
  sem as `.so` do ANGLE.
- **A qualidade da imagem sob ANGLE.** Ela aparece, mas com uma **treliça fina visível sobre todo o
  quadro** nas duas capturas. Não foi investigada e está anotada no relatório do bug; é outra
  medida, não esta.
