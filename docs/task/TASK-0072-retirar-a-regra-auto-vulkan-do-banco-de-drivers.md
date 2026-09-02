# TASK-0072: retirar a regra `gl-arm-g52-r38-auto-vulkan` — o defeito é do título, não do driver

- **Status:** em andamento
- **Criada em:** 2026-09-02
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum — **estreita o contorno** registrado em
  [gl-mali-g52-r38-tela-preta-contornada-nao-corrigida](../bugs/open/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md),
  que segue aberto
- **Commit:** — (o vínculo é o prefixo `TASK-0072:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## O que mudou desde que a regra foi escrita

O reteste no A12 depois do merge com o upstream ([TASK-0067](TASK-0067-merge-com-o-upstream.md))
mediu uma coisa que ninguém tinha medido: **o *10 Pin - Champions Alley* bootou em OpenGL no mesmo
aparelho, na mesma sessão, e renderizou normalmente.**

Só o 007 fica preto. A regra foi escrita achando que o discriminador era o **driver**; ele é o
**título**.

Um segundo fato do mesmo reteste, que também não é o que se supunha: a saída **não** é preta desde
o primeiro quadro. O FMV de abertura aparece por volta de +80 s e só então a imagem congela — as
capturas em +52/112/142/172 s são byte a byte idênticas (md5 `629192d67bc9…`) enquanto o `PerfLog`
mostra a VM viva a 36,9 fps, quadro 5845. É apresentação que **para**, não apresentação que nunca
começa.

## Por que "estreitar" só pode significar "remover"

A regra é chaveada em API + vendor + arquitetura + modelo + faixa de versão de driver. Nenhum desses
eixos distingue *007* de *10 Pin* — os dois rodam no mesmo aparelho, no mesmo driver, na mesma
sessão. **Não existe estreitamento possível no eixo em que a regra é escrita.** Ou ela cobre os dois
jogos, ou não cobre nenhum.

E o `plano-grafico-mali-convergencia-upstream.md` fecha a outra saída, na seção *"O que
explicitamente NÃO fazer"*:

> Adicionar mais uma condição por **nome de jogo** ou nome de GPU. É a origem do ciclo.

Então não se troca a regra de driver por uma regra de título. Ela sai.

## O que ela custava, e para quem

| | com a regra | sem a regra |
|---|---|---|
| Alcance | **todo** Mali-G52 em driver r38.x | ninguém |
| Caminho gráfico | Vulkan, mesmo sendo o GL o caminho rápido do Mali (`GL_ARM_shader_framebuffer_fetch`) | GL, o caminho rápido |
| Piso de Z de 32 bits do PS2 | **descartado** ([registro](../bugs/open/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md)) | mantido |
| Jogos afetados | **todos**, nesses aparelhos | nenhum |
| Evidência que a sustentava | um jogo, num telefone | — |

Ou seja: ela pagava um custo global e silencioso — inclusive uma mudança de **semântica de
emulação** que ninguém decidiu — para contornar um defeito de um título.

## A consequência, dita em voz alta

**No `auto`, o 007: Everything or Nothing volta a ficar preto nesses aparelhos.** Isso não é efeito
colateral esquecido; é a troca que esta task faz de propósito: um título deixa de funcionar no
padrão, e todo o resto recupera o caminho rápido e a profundidade correta.

A saída para quem joga esse título existe, é por jogo, e **é alcançável com a tela preta** — o
overlay de toque continua desenhado por cima da área de render:

> menu em jogo → **Renderer** → **Vulkan** → reiniciar o jogo

Está em [`EmulationMenuScreen.kt:829-848`](../../platforms/android/app/src/main/java/com/armsx2/ui/emulation/EmulationMenuScreen.kt#L829-L848),
já pede o reinício, e a escolha é gravada **por jogo** — então ela não contamina os outros.

## Escopo

**Entra:**

- `pcsx2/GS/Renderers/Common/GSGPUDriverProfile.cpp` — sai a regra (28 → 27), e no lugar dela fica o
  registro do **porquê**, no mesmo formato do bloco que já existe ali para o r44p1 em GL: uma
  ausência deliberada, documentada com a medição, para ninguém "completar o par" de novo.
- `tests/ctest/core/gs/gs_gpu_driver_profile_tests.cpp` — o teste que pinava a preferência
  `Vulkan` passa a pinar `Default`, com o motivo no comentário. O teste de não-alargamento continua
  valendo e ganha o caso do próprio r38p1.
- Os dois registros de bug, com o link de volta.

**Não entra, e é deliberado:**

- **Remover o mecanismo `AutoRendererPreference`.** Ele fica sem nenhum usuário hoje, e isso é
  aceitável: é a via correta para uma regra futura com evidência de driver de verdade, e removê-lo
  obrigaria a próxima pessoa a reinventá-lo — provavelmente como condição por nome, que é o que se
  quer evitar.
- **Corrigir a tela preta do 007.** Continua aberta, agora com duas pistas novas e melhores: é do
  título, e a apresentação para depois de funcionar.
- **Qualquer regra por nome de jogo.** Proibida pelo plano, com motivo medido.

## Como será validado

1. **Compila** — `GSGPUDriverProfile.cpp.o` na árvore ninja do AGP.
2. **O resolvedor volta ao padrão** — os testes pinam `AutoRendererPreference::Default` para a
   string real de `GL_VERSION` do A12, que é o mesmo caminho que o `AndroidAutoPrefersVulkan` usa.
3. **Rastreabilidade** — `check_traceability.py`.
4. **No aparelho** — no A12, `auto` deve resolver para **OpenGL** e o veredito da
   [TASK-0065](TASK-0065-veredito-do-renderer-em-todo-relato.md) deve sair
   `auto_renderer="OpenGL reason=platform-default"` no resumo de boot. É a primeira vez que dá para
   conferir isso de fora.
