# AGENTS.md — como agentes trabalham neste repositório

> Este arquivo é **nosso**. O `AGENTS.md` que o PCSX2 tinha foi apagado pelo upstream em
> `97d95b0ce5`, então não há colisão. As regras de produto e de build estão no
> [`CLAUDE.md`](CLAUDE.md); aqui fica **como o trabalho é dividido entre agentes**.

## A regra

> **Trabalho longo tem uma sessão mestre que orquestra, subsessões que codificam e uma que audita.
> A sessão mestre é a única que carrega o contexto inteiro, e é ela quem testa.**

Três papéis, e eles não se misturam:

| Papel | Quem é | O que carrega | O que faz |
|---|---|---|---|
| **Orquestradora** | uma sessão só, do começo ao fim da iniciativa | o contexto **geral**: o plano, o estado de cada bloco, o histórico das decisões, o estado do aparelho | decide, escreve as tasks, briefa as subsessões, **testa no aparelho**, integra, fala com o usuário |
| **Desenvolvedora** | uma subsessão **por unidade de trabalho** | só o contexto **pontual** daquela unidade | escreve o código, compila, commita, devolve um relatório curto |
| **Auditora** | uma subsessão, depois do código pronto | o diff e os critérios | audita o que foi gerado: corretude, escopo, regressão, aderência às regras do projeto |

## Por que existe

Medido nesta árvore em 2026-09-11 e 2026-09-15, na FEAT-0003: **quatro subsessões morreram por
limite de uso**, consumindo entre 130 mil e 460 mil tokens cada. Boa parte disso não foi trabalho —
foi cada sessão nova **reconstruindo o contexto** que a anterior já tinha: reler a task, remedir a
superfície de conflito, redescobrir o estado do aparelho, redescobrir as armadilhas de build.

Duas consequências, e as duas doem:

1. **Custo.** Contexto reconstruído é contexto pago duas vezes.
2. **Efetividade.** Uma sessão que cresce demais perde o fio; e uma que morre no meio deixa trabalho
   pela metade, às vezes em estado difícil de retomar — um APK instalado que destrói a linha de
   base da medição seguinte, por exemplo.

## O que cabe a cada papel

### A sessão orquestradora

- **Guarda o contexto geral e nunca o delega.** O plano, o que já foi decidido e por quê, o que
  falhou antes, o estado do aparelho, quais medições estão em curso.
- **Faz os testes ela mesma.** Bateria manual em aparelho, leitura de log, comparação de
  screenshots, medição de fps — isso **não** vai para subsessão. É trabalho de observação, não de
  código, e delegá-lo custa um contexto inteiro para devolver seis linhas de veredito.
- **Escreve a task antes** (regra do `CLAUDE.md`) e **briefa** a subsessão com o que ela precisa:
  hashes exatos, caminhos já abertos e conferidos, superfície de conflito medida, armadilhas de
  build desta máquina, e o que **não** entra no escopo.
- **Integra e fala com o usuário.** Subsessão não negocia decisão de produto.
- **Arbitra recursos exclusivos.** O aparelho é um só. Quem está medindo tem posse; quem quiser
  instalar pede antes. Avisar quando devolver.

### A subsessão desenvolvedora

- Recebe **uma** unidade de trabalho e o briefing. Não lê o plano inteiro, não decide escopo.
- Escreve o código, compila, commita com o prefixo da task, roda o validador.
- Devolve um relatório **curto**: o que commitou, o que não fez, o que descobriu que contradiz o
  briefing.
- **Não instala APK nem mede no aparelho sem autorização explícita da orquestradora** — pode estar
  destruindo uma linha de base.

### A subsessão auditora

- Roda **depois** do código pronto, sobre o diff.
- Procura: defeito de corretude, escopo estourado, regressão silenciosa, símbolo usado sem ter sido
  aberto, e desvio das regras do `CLAUDE.md` (rastreabilidade, identidade, `cherry-pick -x`).
- Não corrige: **relata**. A correção volta para a orquestradora decidir.

## Regras que vieram de erro real

1. **Não encerre o turno esperando um aviso que não está armado.** Três subsessões pararam
   esperando a notificação de um build que não era filho delas. Trabalho longo roda com
   `run_in_background` **pela própria ferramenta da sessão**, ou com um laço `until` que termina
   quando a condição é verdadeira.
2. **Prove pelo call-site, não pelo nome.** Uma correção do upstream entrou no binário e era
   **inalcançável**, porque o único chamador tinha sido descartado num merge — e a task só havia
   conferido que a função *existia*. Antes de dar um commit por entregue, ache quem o usa nesta
   árvore.
3. **Adote commit de upstream com `git cherry-pick -x`.** Preserva a autoria e deixa a linha
   `(cherry picked from commit …)`, que é o que faz o próximo `git merge upstream/master`
   reconhecer o que já veio. Um commit único assinado por nós teve de ser refeito por causa disso.
4. **Não escreva hash à mão.** Um `cherry picked from` inventado passa despercebido; o validador
   reprova hash órfão, mas só o que está nos documentos de task.
5. **Meça o "antes" antes de instalar o "depois".** Instalar primeiro destrói o único par
   disponível, e reconstruir a linha de base custa caro — às vezes é impossível.
6. **Arquivo alheio não se toca.** `git add <caminho>` explícito, nunca `git add -A`; **nunca**
   `git stash` puro, porque a pilha é compartilhada entre worktrees.

## Quando **não** abrir subsessão

- Bateria de teste manual (é da orquestradora).
- Tarefa de poucos minutos que a orquestradora já tem contexto para fazer.
- Qualquer coisa cujo briefing seria maior que o próprio trabalho.
