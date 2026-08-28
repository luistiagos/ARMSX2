# TASK-0044: portar a telemetria de falha de boot nativo e de instalação de assets

- **Status:** concluída
- **Criada em:** 2026-08-28
- **Concluída em:** 2026-08-28
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0044:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

A [TASK-0018](TASK-0018-telemetria-no-fork.md) trouxe as três classes de telemetria e os
componentes de **crash** (`armsx2/java`, `armsx2/anr`, `armsx2/native`). Faltaram os pontos de
captura de falha que **não** produzem crash — a linha anterior tinha quatro, e nenhum veio.

Esta task porta dois deles:

| Componente | O que detecta |
|---|---|
| `armsx2/boot` | a inicialização nativa não acontece: `emucore` não carregou, ou `initializeOnce` lançou |
| `armsx2/assets` | um asset empacotado (BIOS, shaders, GameIndex, fontes) não chegou ao disco |

Sem eles, os dois modos de falha mais comuns de "o app abre e não roda nada" chegam ao suporte como
"não funciona", sem um único dado.

## O que este ramo tinha antes

Nada. Confirmado abrindo os dois caminhos, não pelo nome:

**1. Falha de carga do `emucore` é silenciosa.** `NativeApp` já a detecta — o `static {}` põe
`hasNoNativeBinary = true` no `UnsatisfiedLinkError` e imprime `PCSX2_LOAD_FAILED` no stdout. Mas
`grep hasNoNativeBinary` sobre todo o fonte Java/Kotlin devolve **só as três linhas do próprio
`NativeApp`**: ninguém lê a variável. O boot segue e morre na primeira chamada JNI, atribuída à
tela que teve o azar de chamar `NativeApp` primeiro.

**2. Falha de cópia de asset é engolida duas vezes.** `MainActivityRuntime.copyAssetAll` tem um
`catch (e: IOException)` que só loga. Isso parece o ponto de captura óbvio — e **não é**: quem
copia o arquivo é `MainActivity.copyFile`, e ela tem o **próprio** `catch (IOException e)` que loga
e fecha os streams. A exceção nunca sobe. O `catch` do `copyAssetAll` só vê falha de
`assetMgr.list()`, ou seja, quase nunca. Um gancho ali reportaria ~nada.

> É a diferença entre ler o nome do `catch` e abri-lo. O primeiro desenho desta task era exatamente
> esse gancho inútil.

## Escopo

**Entra** — tudo em `runtime/MainActivityRuntime.kt`, o arquivo que o fork já edita:

- `armsx2/boot`: dentro do `invoke { }` de `kickoffEmucoreInit`, reporta (a) `hasNoNativeBinary`
  antes de chamar `initializeOnce`, e (b) qualquer `Throwable` de `initializeOnce` — **relançado**,
  para o fluxo continuar idêntico ao de hoje.
- `armsx2/assets`: como a exceção não sobe, a verificação é **por resultado** — depois de
  `MainActivity.copyFile`, o destino tem de existir. Falhas são acumuladas na varredura inteira e
  viram **um** relato por passe (`bios`, `resources`), com a contagem e os primeiros caminhos.

**NÃO entra:**

- **`armsx2/graphics-boot` e `armsx2/graphics`.** Decisão explícita do pedido. O primeiro exigiria
  emitir evento proativo a cada abertura do GS; o segundo exigiria portar as 307 linhas do
  `GraphicsHealthMonitor`, que não existe neste ramo (item 4.4 do
  [plano do fork](../plano-fork-sobre-upstream.md)) e cujo falso positivo segue
  [em aberto](../bugs/open/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md).
- **`kr/co/iefriends/pcsx2/MainActivity.java`.** Seria o lugar natural do gancho de `assets` — é
  onde a exceção realmente morre. Mas `git log upstream/master..HEAD` sobre esse arquivo é
  **vazio**: o fork nunca o tocou, e abrir superfície de conflito ali para um relato que dá para
  obter checando o resultado não se paga.
- Qualquer edição em `pcsx2/` ou `common/`. O delta no core continua zero.

## Por que um relato agregado, e não um por arquivo

`TelemetryReporter.report` deduplica por `hash(componente + "|" + mensagem)` dentro da sessão. Com o
caminho do arquivo na mensagem, cada arquivo vira uma mensagem distinta — um disco cheio geraria uma
centena de POSTs no boot. A linha anterior mandava **um** evento (`awaitAssetsReady` devolvia um
booleano), e é esse formato que se mantém: um relato por passe, com `failed=<n>` e a amostra dos
caminhos.

## Como validar

1. `armsx2/boot` (caminho `hasNoNativeBinary`): instalar num aparelho de ABI incompatível, ou
   remover `libemucore_*.so` do APK. Sem aparelho de ABI diferente, o caminho é exercitável
   forçando `hasNoNativeBinary = true` num build local.
2. `armsx2/assets`: negar escrita no `assetCopyRoot` (ou apontá-lo para um caminho não gravável) e
   confirmar um único evento `armsx2/assets` com `failed=<n>`.
3. Compilar o APK de **release com R8** e confirmar que as strings novas sobrevivem — é onde este
   tipo de mudança falha em silêncio, como a TASK-0018 registrou.

## Resultado

Entregue. Os dois componentes emitem, e nenhum dos dois altera o fluxo do app: o relato de `boot`
relança a exceção que já subia, e o de `assets` roda depois de uma cópia que já havia falhado e sido
engolida.

Validação 1 e 2 **dependem de aparelho / build instrumentado** e seguem pendentes, na mesma situação
da validação 2 da TASK-0018 (crash real chegando ao `/logErr`).
