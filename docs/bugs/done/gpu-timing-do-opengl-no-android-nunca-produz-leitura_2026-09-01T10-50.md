# Bug: o tempo de GPU do renderizador OpenGL no Android nunca produz leitura

- **Detectado em:** 2026-09-01 (Galaxy A12 `SM-A127M`, Mali-G52, `renderer=opengl`)
- **Origem:** `GSDeviceOGL::PopTimestampQuery` / `KickTimestampQuery` (`pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp`)
- **Errors (serviço):** nenhum
- **Classe:** correção / instrumentação
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0085](../../task/TASK-0085-tempo-de-gpu-do-gl-usa-os-entry-points-da-extensao.md)

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

## Corrigido — 2026-09-04 ([TASK-0085](../../task/TASK-0085-tempo-de-gpu-do-gl-usa-os-entry-points-da-extensao.md))

```
PerfLog: 41.9 fps | EE 100% GS 22% VU 1% GPU 22% | frame 1269
```

Galaxy A12 `SM-A127M`, Mali-G52 r38p1, `githubDebug`, Lara Croft Anniversary em OpenGL. O campo
`GPU` passou a existir onde antes **sumia**.

### A hipótese deste relatório estava errada, e a medição mostrou onde

O relatório supunha `GL_INVALID_ENUM`: o core do GLES recusando `GL_TIME_ELAPSED` por ser alvo da
extensão. Trocar para os entry points `*EXT` era, portanto, a correção esperada — e **não bastou**.
Com eles, o driver responde outra coisa:

| passo | o que a medição no aparelho mostrou |
|---|---|
| `glBeginQueryEXT`, 1ª chamada | rejeitada com **`0x0505` (`GL_OUT_OF_MEMORY`)** — não `INVALID_ENUM` |
| `glBeginQueryEXT`, 2ª em diante | **aceita** |
| leitura de disponibilidade | **`0x0502` (`GL_INVALID_OPERATION`)**, `available=0`, todo quadro |
| `id` lido | **sempre `1`**, com `waiting` subindo 1, 2, 3, 4 … |

Esse `id=1` fixo é a resposta inteira. A primeira query é iniciada por `CreateTimestampQueries`,
antes de o driver ter desenhado qualquer coisa, e é recusada — mas `KickTimestampQuery` marcava
`m_timestamp_query_started = true` **sem conferir**. O anel avançava com um slot que nunca rodou; o
leitor ficava preso para sempre naquela query morta, que responde `INVALID_OPERATION`; nada nunca
ficava disponível; o acumulador ficava em zero pela vida do processo.

**A extensão sempre esteve presente** — `GL_EXT_disjoint_timer_query` aparece na lista do driver
neste aparelho. O problema nunca foi a extensão faltar, nem o alvo ser recusado por ser dela.

### O que entrou

1. **Um begin recusado não consome o slot.** Até um `glBeginQuery` ser aceito, cada tentativa é
   conferida com `glGetError`; falhou, o slot fica livre e o quadro seguinte tenta de novo. Depois
   do primeiro sucesso a checagem para — custo zero por quadro.
2. **Os entry points `*EXT` em todo o ciclo** (`glGenQueriesEXT`, `glBeginQueryEXT`, `glEndQueryEXT`,
   `glDeleteQueriesEXT`, `glGetQueryObjectuivEXT`, `glGetQueryObjectui64vEXT`). Antes só a *leitura
   do resultado* estava sob `#if defined(__ANDROID__)`; criação e begin/end eram os do core. Estava
   errado por princípio, mesmo não sendo a causa — e o `ui64v` remove de quebra o teto de ~4,29 s
   que o comentário anterior documentava.
3. **`SetGPUTimingEnabled` devolve `false`** quando a extensão não existe, em vez de armar um
   mecanismo que não pode funcionar. `GS.cpp` já lê esse retorno.
4. **O aviso fica no código.** Se um driver recusar de novo, o log diz o código do erro em vez de o
   acumulador zerar em silêncio.

### Uma nota de método que vale mais que a correção

**Duas versões do meu próprio instrumento mentiram antes de acertar.** `glGetError()` devolve o erro
**mais antigo pendente** e limpa uma flag por chamada: ler depois da chamada sem **drenar antes**
atribui a ela o erro de outra. A primeira versão culpou o `glBeginQuery` por um `0x0505` levantado
noutro lugar; a segunda repetiu o erro na leitura de disponibilidade. Só com o dreno o `0x0502`
ficou provado como sendo daquela chamada — e foi ele que levou ao `id=1`.

### O que NÃO foi provado

- **Que o valor varia com a carga.** Duas amostras, `GPU 22%` nas duas, com fps caindo de 41,9 para
  27,6. O número é plausível e não é degenerado (nem 0%, nem 100%), e o primeiro resultado cru foi
  1.803.384 ns — mas a contraprova de cena leve × cena pesada não foi feita.
- **Que a correção vale para outros drivers.** Medido num Mali-G52 r38p1. A recusa do primeiro begin
  pode ser específica dele; a defesa contra ela, não.
- **Desktop.** Nenhuma linha fora de `#if defined(__ANDROID__)` mudou de comportamento.

### Candidato a contribuição upstream

`git log upstream/master -- GSDeviceOGL.cpp` não traz nada sobre timestamp query, e o defeito do
slot consumido por um begin recusado é do código do upstream, não do nosso delta. Conforme o
`CLAUDE.md`, correção de motor nasce como contribuição — esta é uma.
