# Bug: `BootSplashActivity` é `singleTop` e descarta o intent que chega enquanto está no topo

- **Detectado em:** 2026-08-25 23:50 (revisão de código, durante o handoff)
- **Origem:** leitura do [`AndroidManifest.xml`](../../../app/src/main/AndroidManifest.xml) e de [`BootSplashActivity.java`](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/BootSplashActivity.java)
- **Errors (serviço):** nenhum — o sintoma é "não fez nada", que ninguém reporta
- **Classe:** fail
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0012](../../task/TASK-0012-portao-de-boot-nao-perde-informacao.md)

## Sintoma

O usuário toca num arquivo de ROM no gerenciador de arquivos enquanto a splash ainda está na tela.
Nada acontece com esse arquivo: quando a inicialização nativa termina, o app abre a Home como se
tivesse sido iniciado pelo ícone.

## Causa raiz

A activity é declarada `android:launchMode="singleTop"`. Quando ela já está no topo da pilha, um
novo intent **não** cria instância nem chama `onCreate` — chega em `onNewIntent`, que a classe não
implementava. Consequência: `getIntent()` continua devolvendo o intent original, e
`launchMainAndFinish()` lê dele o `EXTRA_TARGET_INTENT`, a ação, a URI, as categorias, o `ClipData`
e as *flags de concessão de permissão de URI*. Tudo isso vinha do intent velho; o novo era
descartado em silêncio.

A janela em que isso acontece é exatamente a janela em que a splash existe — ou seja, enquanto a
inicialização nativa roda. Num aparelho lento essa janela é de segundos, e é justamente onde o
usuário tenta de novo.

## Como reproduzir

1. Abrir o app pelo ícone e deixar a splash na tela (aparelho lento ajuda; se necessário, aumentar
   artificialmente o tempo da inicialização nativa).
2. Sem fechar, tocar num `.iso`/`.chd` associado ao app pelo gerenciador de arquivos.
3. Observar que o jogo não abre — a Home aparece vazia de intent.

## Correção — 2026-08-25 (TASK-0012)

`onNewIntent` implementado: chama `setIntent(intent)` para que o callback pendente leia o intent
novo, e — no caso raro em que o encaminhamento já aconteceu — reabre o encaminhamento em vez de
perder o intent. O guard `launchedMain` continua impedindo encaminhamento duplo do mesmo intent.

## Validação

`gradlew compileUnrestrictedDebugJavaWithJavac` limpo. O cenário de corrida (intent chegando depois
do encaminhamento) não foi reproduzido em aparelho — é o ramo defensivo, não o caminho principal.
