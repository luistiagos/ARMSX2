# TASK-0053: o aviso do limite vira um diálogo com os passos, e não um banner que some

- **Status:** em andamento
- **Criada em:** 2026-08-29
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [gos-samsung-limita-clock-a-metade-em-jogo](../bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0053:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

O aviso entregue pelas TASK-0050/0052 usa o `WelcomeBanner`: uma faixa de texto no canto superior
esquerdo que **some sozinha em 2,6 s**. O relato de quem testou:

> *"a mensagem aparece muito sutil e depois some, não mostra na mensagem como desabilitar isso"*

E está certo. O banner foi escolhido porque é o mecanismo que alcança a tela por cima de um jogo em
execução, e nisso ele funciona — mas ele é para **nota passageira**, do tipo "bateria em 20%". Aqui
o conteúdo é uma instrução de três passos que o usuário precisa **ler e executar**, e um texto que
se apaga sozinho não serve para isso. Pior: o texto atual só nomeia o culpado, sem dizer o que
fazer, enquanto a instrução verdadeira estava escondida numa linha de Configurações → App que
ninguém tem motivo para abrir enquanto o jogo está lento.

## Objetivo

Quando o corte for medido, aparece um diálogo que **fica na tela até ser fechado**, com a medição,
os passos concretos para desarmar o corte, um botão que leva direto à tela onde se faz isso, e um
botão de fechar.

## Escopo

**Entra:**

- `ThrottleWatcher.warn()` passa a chamar `GlobalConfirm.ask(...)` no lugar de
  `WelcomeBanner.show(...)`. É o prompt do próprio app: `PadModal` por baixo, montado uma vez em
  `WindowImpl`, desenhado acima de qualquer superfície — inclusive de um jogo rodando — e sem
  temporizador.
- `GlobalConfirm.ask` ganha `dismissLabel` opcional, repassado ao `ConfirmOverlay` que já o
  aceita. É o que troca "Cancelar" por **Fechar**: aqui não há nada a cancelar.
- Strings novas: título, corpo com os três passos na variante Samsung, corpo genérico para quando
  o GOS não está ativo, e o rótulo do botão de ação.

**Não entra:**

- `Dialog`/`AlertDialog`. O cabeçalho do `PadModal.kt` proíbe, e a razão é dura: cada um deles é
  uma *janela* Android própria e engole os KeyEvents do gamepad antes do `dispatchKeyEvent` da
  Activity, onde vivem todas as rotas de D-pad deste app. Ficaria perfeito no toque e morto no
  controle.
- Tirar a linha de Configurações → App. Ela continua sendo o caminho de quem quer resolver fora
  do momento do aviso.
- Mexer na frequência do aviso. Continua um por sessão de emulação, como a
  [TASK-0052](TASK-0052-avisar-do-limite-a-cada-sessao.md) definiu.

## Como validar

No SM-A127M, com o GOS ativo:

1. Abrir um jogo e esperar o corte ser medido → aparece um **diálogo**, com título, a porcentagem
   medida, os passos numerados, e os botões **Fechar** e **Abrir Game Optimizing Service**.
2. O diálogo **não some sozinho**: continua na tela depois de mais de 10 s.
3. O botão de ação abre a página do Game Optimizing Service.
4. **Fechar** fecha, e o jogo volta.

## Resultado

Preenchido ao concluir.
