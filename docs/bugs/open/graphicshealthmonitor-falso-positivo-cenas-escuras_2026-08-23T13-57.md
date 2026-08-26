# Bug: GraphicsHealthMonitor confunde cenas escuras com tela preta corrompida

- **Detectado em:** 2026-08-23 13:57 (telemetria de produção) + captura de cliente
- **Origem:** telemetria `armsx2/graphics` (`GraphicsHealthMonitor.java::evaluate`)
- **Errors (serviço):** 1852, 1853, 1854, 1856, 1857, 1872, 1874, 1875, 1879, 1880,
  1882, 1883, 1885, 1886, 1890, 1985, 1986, 1987, 1988, 1989, 1993, 1994, 1997, 1998,
  2011, 2013, 2014, 2015, 2016, 2017, 2018, 2024, 2025, 2026, 2028, 2029, 2030 e 2032
  (38 eventos)
- **Classe:** regressão / falso positivo com alteração automática do renderer
- **Reincidência:** bug novo introduzido pelo fallback visual distribuído na 1.0.21
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0005](../../task/TASK-0005-bloco-c-pontos-de-consumo.md)

## Sintoma

Durante uma cena legitimamente escura, o app mostra `Corrupção gráfica detectada. Aplicando modo
compatível…` e altera as opções gráficas sem solicitação do usuário. A captura recebida corresponde
ao evento mais recente, error **2032**: Galaxy A07 `SM-A075M`, Tomb Raider Underworld
(`SLUS-21858/618769D6`), OpenGL (`original_renderer=12`).

Todos os 38 eventos observados declaram `ARMSX2/graphics 1.0.21`. Eles atingem seis modelos
(Samsung A07/A17, dois Motorola, Redmi e POCO) e pelo menos 15 jogos. Portanto não é defeito
específico de Samsung A05/A15 nem do workaround de Metal Gear Solid 3/Mali.

A distribuição dos eventos contém 16 detecções iniciais de `persistent-black`, cinco sequências que
chegam a Software e quatro que continuam pretas mesmo em Software. Esse último caso é evidência
forte de que o conteúdo do jogo permaneceu escuro enquanto o monitor trocava renderizadores, e não
de corrupção simultânea e idêntica em todos os backends.

## Causa raiz

Confirmada em `GraphicsHealthMonitor`:

1. O monitor reduz todo o `SurfaceView` a apenas 32×32 pixels. Qualquer pixel com R/G/B até 16 é
   contado como preto, inclusive detalhe visível de cenas muito escuras.
2. `classifyPixelsForTest()` declara falha quando 99% da amostra cai nessa faixa. A imagem não é
   comparada com frames anteriores e o monitor não sabe se já houve jogo saudável antes da cena.
3. O único gate de atividade é `FPS >= 1`; uma cena normal escura também tem FPS e VM ativos.
4. Oito amostras consecutivas (~29 segundos após o início) chamam `retryWithOpenGL()` ou
   `retryWithSafeCopies()`. Se a cena clareia naturalmente, apenas duas amostras claras atribuem a
   melhora ao fallback e persistem a decisão em `SharedPreferences`.
5. Em cenas escuras longas, o fluxo continua até Software, causando perda grande de desempenho e
   podendo reaplicar o renderer inadequado nos próximos boots daquele jogo/aparelho.

O detector de vermelho é separado e muito mais específico (vermelho dominante uniforme em quatro
amostras). Dos 38 eventos, somente um foi `OpenGL remained red`. Remover o fallback automático por
preto não exige remover o workaround estático de MGS3/Mali nem a detecção de tela vermelha.

## Como reproduzir

1. Instalar a versão 1.0.21.
2. Abrir Tomb Raider Underworld (`SLUS-21858`) no Galaxy A07 com OpenGL.
3. Permanecer por aproximadamente 30 segundos em uma cena quase preta.
4. Observar o toast de corrupção e o error `armsx2/graphics` com `persistent-black`.

Também há sequências completas reproduzidas em Darkwatch, Final Fantasy X, MTX Mototrax,
007 - Agent Under Fire e outros títulos nos errors listados acima.

## Plano de correção definido na triagem

1. Hotfix: impedir que `FRAME_UNIFORM_BLACK` altere renderer ou persista fallback com base apenas
   nos pixels. Manter a coleta diagnóstica e o caminho de vermelho.
2. Invalidar as chaves `graphics_safe_copy:*`, `graphics_opengl:*` e `graphics_software:*` gravadas
   pela 1.0.21, para não deixar jogos presos em modo compatível/Software após a atualização.
3. Se o fallback preto do A07 for mantido, limitá-lo ao boot antes do primeiro frame saudável e
   exigir recorrência em mais de uma sessão. Depois que conteúdo válido apareceu, uma cena preta
   nunca deve ser interpretada como falha de inicialização.
4. Adicionar testes de sequência, não apenas de uma imagem: saudável → cena escura longa → saudável
   não pode mudar renderer nem persistir decisão; preto desde o boot pode somente gerar diagnóstico.

## Correção aplicada — 2026-08-23

- `FRAME_UNIFORM_BLACK` continua sendo identificado para telemetria, mas deixou de participar do
  limiar que aciona fallback. Após oito amostras, é emitido no máximo um evento por jogo/sessão com
  `persistent-black observed; automatic fallback suppressed`; nenhum toast ou renderer é alterado.
- Todas as transições automáticas (`safe copies`, OpenGL e Software) agora exigem explicitamente
  `FRAME_DOMINANT_RED` e quatro amostras vermelhas consecutivas.
- O schema das decisões do monitor foi elevado para 2. Na primeira execução do hotfix, somente as
  chaves `graphics_safe_copy:*`, `graphics_opengl:*` e `graphics_software:*` da 1.0.21 são removidas.
  Preferências não relacionadas permanecem intactas.
- Foram adicionados testes garantindo que preto é apenas diagnóstico, vermelho ainda autoriza
  fallback e a migração não seleciona preferências estranhas ao monitor.

**Validação local:** `testUnrestrictedDebugUnitTest` e `assembleUnrestrictedDebug` concluíram com
sucesso (15 testes, 0 falhas).

## Publicação

O hotfix foi publicado em 2026-08-23 como **1.0.22 (`versionCode` 36)**, assinado com o certificado
oficial e verificado pela URL pública. APK: 32.137.302 bytes; SHA-256
`29ec6666eeb2dbbe9beb0ec4b6681686d761abf23f242e2bf4c9a71af42ad62a`.

**Status:** aguardando reteste em produção. Na consulta de 2026-08-24, a telemetria continha 2.091
erros no total e **nenhum evento `armsx2/graphics` da 1.0.22**; portanto ainda não há amostra de
campo suficiente para encerrar o bug. Quando ocorrer, o novo evento
`persistent-black observed; automatic fallback suppressed` será diagnóstico esperado e não
representará troca gráfica. Os eventos antigos `persistent-black detected; retrying...` não podem
voltar a ser emitidos pelo código da 1.0.22.

## Validação em aparelho — 2026-08-25

Testado no Galaxy A12 (`SM-A127M`, Android 13, Exynos 850 / Mali-G52, driver `v1.r38p1`) com o mesmo
jogo do error 2032: **Tomb Raider Underworld** (`SLUS-21858`), em OpenGL (`gpu_profile=Mali`,
`api=OpenGL` pela linha `GSBoot` da [TASK-0006](../../task/TASK-0006-diagnostico-boot-gs.md)).

O jogo foi levado até uma cena genuinamente escura — ruína submersa, com colunas e arcos apenas
insinuados sobre fundo quase preto, que é exatamente a classe de frame que a 1.0.21 classificava como
`FRAME_UNIFORM_BLACK`. Rodou **mais de 4 minutos** nessa condição.

Resultado: **nenhum evento do monitor, nenhum toast de "Corrupção gráfica detectada", nenhuma troca
de renderer.** O PID permaneceu o mesmo (22195) e não houve crash.

Isso valida em campo o hotfix da 1.0.22 no jogo e na condição exatos que produziram o falso positivo.

**O que esta validação NÃO cobre:** o caminho de vermelho continua ativo — `GraphicsHealthMonitor`
ainda chama `setTemporaryRenderer` e `enableGraphicsSafeMode` quando detecta
`FRAME_DOMINANT_RED` por quatro amostras. Esse caminho não foi exercitado aqui e continua sendo o
alvo da [TASK-0005](../../task/TASK-0005-bloco-c-pontos-de-consumo.md), que prevê desligar a troca
automática e manter só o diagnóstico.

## Fechamento do caminho de vermelho — 2026-08-25 (TASK-0005)

O parágrafo acima está resolvido: a [TASK-0005](../../task/TASK-0005-bloco-c-pontos-de-consumo.md)
removeu **toda** troca automática de renderer do monitor. Vermelho e preto continuam sendo
classificados e enviados à telemetria — agora com a linha `GSBoot` (GPU, driver, versão, renderer)
anexada ao contexto —, mas nenhuma classificação de pixel altera configuração gráfica.

Três razões, na ordem em que pesam:

1. **A heurística decide sem saber o driver.** Vermelho na tela pode ser um defeito de driver que o
   banco de regras já conhece, e a resposta certa para esse caso é uma linha de tabela chaveada na
   versão do driver — não uma troca de renderer decidida por 32×32 pixels 8 segundos depois do boot.
2. **O caminho de ação podia matar o processo.** `setTemporaryRenderer` / `enableGraphicsSafeMode`
   levam a `GSUpdateConfig` → `GSreopen`, e uma falha dupla ali termina em `pxFailRel` → `abort()`.
3. **O precedente do preto.** A mesma heurística já produziu os 38 falsos positivos deste bug, e a
   correção da 1.0.22 foi exatamente esta forma: manter a classificação, remover a ação. Aplicá-la
   ao vermelho é terminar o que aquele hotfix começou.

O schema das decisões subiu para **3**, e a migração continua rodando: as chaves
`graphics_safe_copy:*`, `graphics_opengl:*` e `graphics_software:*` gravadas pelas 1.0.21–1.0.23 são
apagadas na primeira execução. Sem isso, quem ficou preso em Software naquelas versões continuaria
preso, agora sem nenhum código capaz de tirá-lo de lá.

Guardado por teste: `GraphicsHealthMonitorTest.monitorNeverChangesTheRenderer` varre o fonte do
monitor procurando os dois nomes fora de comentário. Renomear o método de ação não faz o teste voltar
a passar.

**Status:** o falso positivo em si está corrigido e validado em aparelho (seção acima). O que resta
para fechar este bug é uma janela de telemetria sem nenhum evento de troca automática — que agora é
uma impossibilidade estrutural, não uma expectativa.
