# TASK-0085: o tempo de GPU no GLES passa a usar os entry points da extensão, e falha alto se não der

- **Status:** concluída
- **Criada em:** 2026-09-04
- **Concluída em:** 2026-09-04
- **Feature:** nenhuma
- **Bugs que resolve:** [gpu-timing-do-opengl-no-android-nunca-produz-leitura](../bugs/done/gpu-timing-do-opengl-no-android-nunca-produz-leitura_2026-09-01T10-50.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0085:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

No Android, com o renderizador OpenGL, `PerformanceMetrics` nunca recebe tempo de GPU: o campo
some do `PerfLog`. No Vulkan, o mesmo log traz números reais. O relatório levanta a hipótese e
diz, com todas as letras, que ela **não foi confirmada** — *"faltou ler `glGetError` depois do
`glBeginQuery`. É o próximo passo, e é uma linha."*

Esta task confirma ou derruba a hipótese **e** aplica a correção que ela implica, na mesma rodada,
porque cada ciclo de build nativo custa minutos e separar as duas coisas dobraria o custo sem
mudar o resultado.

## A hipótese, e por que ela é forte

Em GLES, `GL_TIME_ELAPSED` é alvo **da extensão** `GL_EXT_disjoint_timer_query`, não do core. O
core do GLES 3.x valida o alvo de `glBeginQuery` contra a própria lista
(`GL_ANY_SAMPLES_PASSED*`, `GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN`) e recusa qualquer outro com
`GL_INVALID_ENUM`. Os entry points certos são `glBeginQueryEXT` / `glEndQueryEXT` /
`glGetQueryObjectuivEXT`.

`GSDeviceOGL.cpp` usa os do **core**. O comentário do próprio arquivo observa que o enum tem o
mesmo valor (`GL_TIME_ELAPSED_EXT === 0x88BF === GL_TIME_ELAPSED`) — o que é verdade e
insuficiente: o valor do enum ser igual não faz o entry point do core aceitar o alvo.

Verificado antes de escrever código:

- **O upstream não corrigiu.** `git log upstream/master -- GSDeviceOGL.cpp` desde agosto não traz
  nada sobre timestamp query. Se a correção se confirmar, ela é candidata a contribuição.
- **O glad expõe os sete `*EXT`** — `glBeginQueryEXT`, `glEndQueryEXT`, `glGenQueriesEXT`,
  `glDeleteQueriesEXT`, `glGetQueryObjectuivEXT`, `glGetQueryObjectui64vEXT`, `glIsQueryEXT` — e a
  flag `GLAD_GL_EXT_disjoint_timer_query`, resolvida por `glad_gl_has_extension`.

## Escopo

**Entra:**

- **Os quatro pontos do ciclo passam a usar os `*EXT` sob `__ANDROID__`**: `glGenQueriesEXT` em
  `CreateTimestampQueries`, `glBeginQueryEXT` em `KickTimestampQuery`, `glEndQueryEXT` em
  `PopTimestampQuery` e `DestroyTimestampQueries`, `glDeleteQueriesEXT` ao destruir. Hoje só a
  **leitura do resultado** está sob `#if defined(__ANDROID__)`; a criação e o begin/end são
  compartilhados com o desktop, e são justamente eles que o core recusa.
- **`glGetQueryObjectui64vEXT` para o resultado**, em vez do `glGetQueryObjectuiv` de 32 bits. A
  extensão fornece a leitura de 64 bits, e o comentário atual documenta um teto de ~4,29 s de
  nanossegundos que deixa de existir.
- **`SetGPUTimingEnabled` passa a devolver `false`** quando a extensão não está presente, em vez de
  ligar um mecanismo que não pode funcionar. Os chamadores em `GS.cpp` já leem o retorno.
- **Um `glGetError()` depois do `glBeginQueryEXT`**, uma única vez por sessão, com aviso no console
  dizendo o código. É o passo que o relatório pediu, e ele fica no código: se um driver recusar de
  novo, o log diz por quê em vez de o acumulador ficar zerado em silêncio.

**NÃO entra:**

- As *pipeline statistics* (`PopPipelineStatisticsQuery`). São desktop-GL e continuam compiladas
  fora no Android, por outro motivo, documentado no próprio arquivo.
- Qualquer mudança no consumidor (`PerformanceMetrics`, `PerfLog`, OSD). Se o dado passar a existir,
  ele já tem para onde ir — foi a [TASK-0055](TASK-0055-contadores-de-desempenho-que-nao-mentem.md)
  que fez o campo sumir quando não há medição, e é por isso que este defeito ficou visível.
- Vulkan e desktop. Nenhuma linha fora do `#if defined(__ANDROID__)`.

## Como validar

Em aparelho (Galaxy A12 `SM-A127M`, Mali-G52, `githubDebug`), com um jogo rodando em **OpenGL**:

1. `adb logcat` durante o boot do GS — não pode haver o aviso novo de `glBeginQueryEXT` falhando.
2. O `PerfLog` passa a trazer o campo **`GPU`** com valor plausível, onde hoje o campo **não
   aparece**. É a diferença exata que o relatório registra entre OpenGL e Vulkan.
3. Contraprova de que a medição não é ruído: o valor tem de variar com a carga (cena leve × cena
   pesada), e não ficar preso num número.

## Resultado

`PerfLog: 41.9 fps | EE 100% GS 22% VU 1% GPU 22% | frame 1269` — o campo `GPU` existe onde antes
sumia. Medido no Galaxy A12 (Mali-G52 r38p1), Lara Croft Anniversary em OpenGL.

**A hipótese do relatório estava errada, e trocar os entry points não bastou.** Com os `*EXT`, o
driver não responde `GL_INVALID_ENUM`: a **primeira** chamada a `glBeginQueryEXT` — feita por
`CreateTimestampQueries`, antes de o driver desenhar — é recusada com `GL_OUT_OF_MEMORY` (0x0505),
e todas as seguintes são aceitas. Só que `KickTimestampQuery` marcava `m_timestamp_query_started`
**sem conferir**, o anel avançava com um slot que nunca rodou, e o leitor ficava preso naquele `id`
morto para sempre — `GL_INVALID_OPERATION` e `available=0` a cada quadro, com `waiting` subindo
1, 2, 3, 4… O acumulador ficava zerado pela vida do processo.

O que resolve é o item **1** abaixo, que não estava previsto no escopo original: um begin recusado
não consome o slot. Os outros três entraram e estão certos — os `*EXT` em todo o ciclo, o `false` do
`SetGPUTimingEnabled` e o aviso permanente —, mas nenhum deles era a causa.

**Duas versões do meu próprio instrumento mentiram antes de acertar**, e isso vale registrar:
`glGetError()` devolve o erro mais antigo pendente e limpa uma flag por chamada, então ler depois da
chamada **sem drenar antes** atribui a ela o erro de outra. A primeira versão culpou o
`glBeginQuery` por um `0x0505` de outro lugar; a segunda repetiu o erro na leitura de
disponibilidade. Só com o dreno o `0x0502` ficou provado como daquela chamada — e foi ele que levou
ao `id=1` preso.

**Não provado:** que o valor varia com a carga (duas amostras, 22% nas duas, com fps de 41,9 → 27,6);
que a recusa do primeiro begin acontece noutros drivers. Desktop não mudou: nada fora do
`#if defined(__ANDROID__)`.

**Candidato a contribuição upstream:** o defeito do slot consumido por um begin recusado é do código
do upstream, e `git log upstream/master -- GSDeviceOGL.cpp` não traz nada sobre isso.
