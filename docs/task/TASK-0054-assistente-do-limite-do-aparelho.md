# TASK-0054: aviso curto com assistente passo a passo, que cabe na tela deitada e não fecha sozinho

- **Status:** em andamento
- **Criada em:** 2026-08-29
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0054:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

O diálogo da [TASK-0053](TASK-0053-aviso-do-limite-vira-dialogo.md) foi testado no aparelho e
voltou com quatro defeitos, todos reais:

1. **Demora ~1 min para aparecer.** São 20 amostras a cada 3 s.
2. **Não cabe na tela deitada.** O card tem corpo com `heightIn(max = 340.dp)` mais título,
   botões e 48 dp de padding. Deitado, o SM-A127M dá ~288 dp de altura útil (1600×720 a 2.5x) —
   o card é cortado embaixo e **o botão de fechar não aparece**. E jogo roda deitado, sempre.
3. **Fecha sozinho.** Não é temporizador: o scrim do `PadModalHost` tem
   `clickable { PadModals.dismissTop() }`, e com o jogo deitado os dedos do usuário estão nos
   controles na tela — que são "fora do card". Qualquer toque fechava.
4. **Texto demais de uma vez.** Três passos num parágrafo só, para um público que o produto
   assume **leigo**.

## Objetivo

Um aviso curto, que fica na tela até o usuário fechar, cabe deitado, e leva a um assistente que
ensina um passo por vez.

## Escopo

**Entra:**

- **Tempo de detecção: de ~60 s para ~15 s.** Amostra a cada 1 s (era 3 s) e exige 15 amostras
  lentas **consecutivas** (era 20, não consecutivas). Mais rápido e mais firme ao mesmo tempo: um
  respiro de velocidade normal — carregamento de disco, por exemplo — zera a contagem em vez de
  somar para um veredito.
- `ui/common/ThrottleHelp.kt` (novo): o aviso e o assistente, sobre `PadModal`.
  - **`onDismiss = null`**, que o `PadModal` documenta como "insistente": o toque no scrim é
    engolido e não fecha. Sai só pelo botão **Fechar**, que é o que foi pedido.
  - **Responsivo de verdade**: `BoxWithConstraints` mede o espaço, o card recebe
    `heightIn(max = maxHeight - 24.dp)` e `widthIn(max = min(460.dp, maxWidth - 32.dp))`, e o
    corpo leva `weight(1f, fill = false)` com rolagem — os botões ficam presos embaixo e nunca
    saem da tela, deitado ou em pé.
  - **Tela 1**: uma frase com a medição, e dois botões — *Fechar* e *Ver como resolver*.
  - **Passos 1 a 4**: um passo por tela, com "Passo N de 4", *Voltar* e *Avançar*, e *Fechar* no
    último. O passo 1 tem o botão que abre a tela do GOS.
  - Tocar em "abrir a tela" **avança para o passo 2 antes de sair do app**, então quem volta do
    Android encontra a instrução seguinte, e não a que acabou de executar.
- `WindowImpl` monta o `Host()`, ao lado do `GlobalConfirm.Host()`.
- `ThrottleWatcher.warn()` chama `ThrottleHelp.show(pct)`.
- Strings novas em `I18n.kt` e `pt-BR.json`; saem as do diálogo anterior.

**Não entra:**

- Desfazer a rolagem que a TASK-0053 pôs no `ConfirmOverlay`. Ela conserta o mesmo defeito de
  altura para todo prompt do app e continua valendo por si.
- Refazer os outros modais do app que também usam `heightIn` fixo (`GameVersionsModal`,
  `CatalogDownloadModal`). Têm o mesmo risco deitado e merecem task própria.
- Detectar automaticamente que o usuário concluiu os passos. O assistente ensina; quem confere é
  a velocidade do jogo.

## Como validar

No SM-A127M **com o aparelho deitado**, que é o caso que quebrou:

1. Abrir um jogo com o GOS ativo → o aviso aparece em ~15 s, não em ~60 s.
2. O card **cabe inteiro**: título, frase e os dois botões visíveis.
3. **Tocar nos controles do jogo, fora do card, não fecha o aviso.**
4. *Ver como resolver* → passo 1 de 4; *Avançar*/*Voltar* percorrem; o passo 1 abre a tela do GOS
   e, ao voltar, o assistente está no passo 2.
5. *Fechar* fecha.

## Resultado

Preenchido ao concluir.
