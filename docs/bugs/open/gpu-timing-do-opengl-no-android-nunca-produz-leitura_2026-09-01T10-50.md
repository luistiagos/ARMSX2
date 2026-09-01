# Bug: o tempo de GPU do renderizador OpenGL no Android nunca produz leitura

- **Detectado em:** 2026-09-01 (Galaxy A12 `SM-A127M`, Mali-G52, `renderer=opengl`)
- **Origem:** `GSDeviceOGL::PopTimestampQuery` / `KickTimestampQuery` (`pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp`)
- **Errors (serviço):** nenhum
- **Classe:** correção / instrumentação
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda

## Sintoma

Com o renderizador **OpenGL** no Android, `PerformanceMetrics` nunca recebe tempo de GPU. Antes da
[TASK-0055](../../task/TASK-0055-contadores-de-desempenho-que-nao-mentem.md) isso aparecia como um
`GPU 0%` permanente no `PerfLog` — um número que parece medição e não é. Depois dela o campo é
**omitido**, que é o comportamento correto, mas o dado continua não existindo:

```
PerfLog: 24.9 fps | EE 100% GS 36% VU 0% | frame 758      <- sem campo GPU
PerfLog: 50.0 fps | EE  65% GS 98% VU 0% | frame 3558
```

No **Vulkan** o mesmo `PerfLog` traz números reais (medido: `GPU 4%`, `15%`, `73%` conforme o jogo).
É específico do caminho OpenGL.

## O que já foi descartado

- **A extensão existe.** `GL_EXT_disjoint_timer_query` está na lista que o próprio app registra em
  `GL_EXTENSIONS`, no `emulog.txt` deste aparelho.
- **O ciclo begin/end está correto.** `PopTimestampQuery` drena o que está disponível, fecha a query
  aberta com `glEndQuery`, avança o índice de escrita e incrementa `m_waiting_timestamp_queries`;
  `KickTimestampQuery` abre a próxima. A contabilidade confere.
- **Não é o defeito que o comentário do código diz ter corrigido.** O comentário em
  `PopTimestampQuery` descreve uma versão anterior que lia o slot errado e incrementava em vez de
  decrementar, e nomeia o sintoma exatamente como *"'GPU: 0%' symptom"*. Essa correção está no
  código — **e o sintoma continua**.

## Hipótese, não medição

Em GLES, `GL_TIME_ELAPSED` é alvo **da extensão**, não do core. O core do GLES 3.x valida o alvo de
`glBeginQuery` contra a própria lista (`GL_ANY_SAMPLES_PASSED*`,
`GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN`) e recusa qualquer outro com `GL_INVALID_ENUM`. Os
entry points certos são `glBeginQueryEXT` / `glEndQueryEXT` / `glGetQueryObjectuivEXT`.

O arquivo usa os do **core**: `grep -c "QueryEXT" GSDeviceOGL.cpp` devolve **0**. O comentário do
próprio código menciona que o enum tem o mesmo valor (`GL_TIME_ELAPSED_EXT === 0x88BF ===
GL_TIME_ELAPSED`) — o que é verdade e insuficiente: o valor do enum ser igual não faz o entry point
do core aceitar o alvo.

Se for isso, `glBeginQuery` falha em silêncio, nenhuma query chega a rodar, o resultado nunca fica
disponível, o `break` do laço dispara sempre e o acumulador fica em zero para sempre — que é
exatamente o que se observa.

**Não confirmado:** faltou ler `glGetError` depois do `glBeginQuery`. É o próximo passo, e é uma
linha.

## Como reproduzir

Abrir um jogo com o renderizador OpenGL e ler o `emulog.txt`:

```bash
adb shell "grep -E 'renderer=|PerfLog' \
  /storage/emulated/0/Android/data/come.nanodata.armsx2/files/logs/emulog.txt | head"
```

Com `renderer=opengl`, as linhas de `PerfLog` saem **sem** o campo `GPU`. Trocando para Vulkan, o
campo aparece com valores plausíveis.

## Por que passou despercebido

Porque o sintoma era um `0%`, e `0%` lê-se como "a GPU não está fazendo nada" — não como "não há
medição". Foi preciso separar as duas coisas no log
([TASK-0055](../../task/TASK-0055-contadores-de-desempenho-que-nao-mentem.md)) para o defeito ficar
visível: agora o campo some, e um campo que some é uma pergunta; um `0%` não era.
