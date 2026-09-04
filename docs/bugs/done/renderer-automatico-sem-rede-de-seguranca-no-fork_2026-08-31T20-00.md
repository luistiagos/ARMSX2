# Bug: o renderer automático do fork não tem rede de segurança — a da linha anterior foi aposentada sem substituto

- **Detectado em:** 2026-08-31 20:00 (leitura de código, durante o registro da cadeia gráfica do A12)
- **Fechado em:** 2026-09-04, com as duas classes medidas no `SM-A127M`
- **Origem:** comparação entre `feature/fork-upstream-android` e `feature/handoff-end-to-end`
- **Errors (serviço):** não avaliado — o defeito é a ausência de recuperação, não um erro reportado
- **Classe:** fail
- **Reincidência:** o defeito que a rede cobria já aconteceu em campo (Motorola, 1.0.17)
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0066](../../task/TASK-0066-rede-de-seguranca-do-renderer-automatico.md)
  (a classe "morreu antes do primeiro quadro") e
  [TASK-0082](../../task/TASK-0082-acao-de-imagem-nao-apareceu-troca-o-backend.md)
  (a classe "apresenta quadros que ninguém vê")

## Sintoma

Se o renderer que o `auto` escolhe faz o app fechar sozinho ao abrir um jogo, **o app fecha de novo
na próxima vez, e na seguinte, indefinidamente**. Não há recuperação automática. A saída existe
(Configurações → Renderer → escolher à mão), mas exige que o usuário saiba que ela existe, e que
consiga chegar às Configurações num app que fecha ao abrir jogo.

## O que existia e não existe mais

A linha anterior (`feature/handoff-end-to-end`, `app/src/main/cpp/pcsx2/GS/GSUtil.cpp`) tinha dois
arquivos em `EmuFolders::Cache`:

| arquivo | papel |
|---|---|
| `auto_renderer_boot.tmp` | armado antes de o renderer automático ser usado; aposentado depois de 600 quadros apresentados ou num shutdown limpo |
| `auto_renderer_no_vulkan.tmp` | gravado quando um marcador velho é encontrado. Persistente **de propósito** — sem isso o usuário alternaria entre sessão boa e crash para sempre |

O raciocínio, do comentário original: *"Finding it at startup means the previous run died before
ever presenting one"* — um crash não é valor de retorno, então nenhum `if (!GSopen())` consegue
detectá-lo. O registro do A07 já tinha catalogado por que os três mecanismos existentes não pegavam
esse caso ([gs-tela-preta-silenciosa-sem-diagnostico-a07](../open/legado-version1/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md), item 4).

Ele foi escrito em 2026-08-21 depois de um relato real: **Motorola, 1.0.17 — Vulkan crashava ao
abrir o jogo, OpenGL na mão funcionava.**

O passo 4 do [plano de convergência](../../plano-grafico-mali-convergencia-upstream.md) mandou
aposentá-lo junto com `IsAllowlistedAndroidVulkanGPU`, chamando-o de *"a nossa versão cega do mesmo
problema"*. A parte de aposentar foi feita. **A parte de substituir não.**

Confirmado: `grep -rn "auto_renderer_boot" pcsx2/ platforms/android/app/src/main/` no fork não
retorna nada.

## Por que "o banco de drivers substitui isso" não fecha o buraco

O banco de regras responde *"este driver tem este defeito conhecido"*. Ele não responde *"este
aparelho, aqui, agora, não conseguiu apresentar um quadro"*. São perguntas diferentes:

- a regra é um **palpite prévio**, escrito por nós, a partir de outro aparelho;
- o marcador é **evidência local**, colhida no aparelho do usuário.

Um driver novo, um aparelho que ninguém testou, ou uma regra escrita larga demais produzem
exatamente o caso que o marcador pega e a regra não.

## A segunda classe: "apresenta quadros que ninguém vê" — fechada pela TASK-0082

**A tela preta do A12 não mata o processo** — a VM, o áudio e o contador de quadros continuam, e
`Host::BeginPresentFrame` é chamado normalmente. Do lado de dentro do emulador, uma sessão preta é
**indistinguível** de uma sessão boa, então o marcador da TASK-0066 nunca dispara nela.

Distinguir exigiria amostrar pixels, e isso está proibido com número medido no plano: *"Classificar
saúde gráfica por amostra de pixel. Já produziu 38 falsos positivos em 6 modelos."* — o
`GraphicsHealthMonitor` já foi por esse caminho e o registro do falso positivo está em
[graphicshealthmonitor-falso-positivo-cenas-escuras](../open/legado-version1/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md).

A saída, portanto, é **perguntar em vez de adivinhar**: uma ação visível durante o jogo, do tipo
*"a imagem não apareceu"*, que troca o backend por jogo e reinicia. Um toque do usuário, nenhum
falso positivo. Foi o que a
[TASK-0082](../../task/TASK-0082-acao-de-imagem-nao-apareceu-troca-o-backend.md) escreveu.

## A prova medida — 2026-09-04, `SM-A127M` (Mali-G52 r38p1, Android 13), APK `githubDebug`

Jogo: 007: Everything or Nothing (`SLUS-20751`), o mesmo do relatório da tela preta.

### 1. O defeito, reproduzido e medido antes de tocar em nada

Com o renderizador do jogo em `auto`, o log resolve
`Android: Auto renderer -> OpenGL reason='platform-default'` — e a saída congela em preto depois do
FMV de abertura. Duas capturas separadas por **40 s** são byte a byte idênticas, md5
`629192d67bc9d079dd30d6a549d2b453` (o **mesmo** hash registrado no relatório da tela preta em
2026-09-01), enquanto o `PerfLog` do intervalo mostra a VM viva:

```
PerfLog: 36.8 fps | EE 100% GS 68% VU 19% | frame 4751
PerfLog: 36.7 fps | EE 100% GS 71% VU 19% | frame 5855
PerfLog: 37.3 fps | EE 100% GS 72% VU 20% | frame 6985
```

Quadros apresentando, tela preta. É exatamente a classe que nenhum mecanismo interno vê.

### 2. O menu é alcançável com a tela preta

O overlay de toque fica **por cima** da área de render, então o botão de pausa e o menu desenham
normalmente sobre o preto. A ação aparece na primeira aba, já com o alvo no rótulo:
*"A imagem não apareceu — Reiniciar usando Vulkan"*.

O alvo está certo sem ninguém ter dito qual era: gravado `auto`, veredito `OpenGL` → propõe o
oposto.

### 3. A recuperação faz o que promete

Confirmando, a VM reinicia e o log traz `@@ANDROID_GS_SETTINGS@@ reason=commit renderer=14`
(`14` = `GSRendererType::VK`). E, no `shared_prefs`, **só** a chave do renderizador mudou:

```
antes:   config.game.SLUS-20751 = {"renderer":"auto",    "upscaleFloat":1.25}
depois:  config.game.SLUS-20751 = {"renderer":"vulkan",  "upscaleFloat":1.25}
```

O `upscaleFloat` que o usuário tinha fixado sobreviveu, e nenhuma outra chave foi tocada.

### 4. A escada avança, e o segundo degrau é que entrega a imagem neste aparelho

O Vulkan **também** não serve para este jogo aqui: a sessão morre em ~30 s com
`VK_ERROR_DEVICE_LOST` e `Host GPU lost too many times, device is probably completely wedged` — que
é o outro relatório, [mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale](../open/armsx2-fork/mali-g52-r38-vulkan-perde-o-device-com-qualquer-upscale_2026-09-02T11-33.md),
com o `upscaleFloat` de 1,25 do próprio usuário.

Reabrindo o menu, a ação já propõe sozinha o degrau seguinte: *"Reiniciar usando Software"*
(gravado `vulkan`, oposto do veredito → software). Confirmando, `renderer=13` (`SW`) e **a imagem
aparece**: três capturas em +75 s, +120 s e +150 s, com três md5 diferentes
(`f27c0018…`, `18936e74…`, `a9b41335…`), a cutscene de abertura legível, a ~36 fps.

**Duas confirmações, partindo de uma tela preta, e o aparelho está jogando** — num par
jogo/aparelho onde os dois backends de hardware falham, cada um do seu jeito.

### A classe de crash, no mesmo aparelho

Os cinco itens da validação da TASK-0066 (marcador armado, virada de backend, aviso uma única vez,
sem repetição no terceiro arranque, e limpeza pela escolha explícita) estão medidos e transcritos em
[TASK-0066](../../task/TASK-0066-rede-de-seguranca-do-renderer-automatico.md#validação-em-aparelho--2026-09-04-sm-a127m-mali-g52-r38p1-android-13).

## O que este fechamento NÃO afirma

- **Não conserta a tela preta do OpenGL.** O 007 continua preto em GL neste aparelho; o que existe
  agora é a saída assistida. A causa continua aberta em
  [gl-mali-g52-r38-tela-preta-contornada-nao-corrigida](../open/armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md).
- **Não conserta o device-lost do Vulkan.** Idem, relatório próprio e ainda aberto.
- **O aparelho não crashou de driver de verdade** na validação da classe de crash: o `am force-stop`
  é um stand-in. Está dito na TASK-0066.
- **Um caso de canto continua sem prova:** com um bloqueio da TASK-0066 ativo, o veredito lido pela
  ação fica defasado do renderizador em uso, e o primeiro toque a partir de `auto` pode propor o que
  já está rodando. O segundo corrige. Exercitar isso exige crash-loop **e** tela preta no mesmo
  aparelho; não foi montado.
