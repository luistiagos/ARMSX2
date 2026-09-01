# TASK-0063: o fundo 2D da biblioteca para de animar

- **Status:** concluída
- **Criada em:** 2026-08-31
- **Concluída em:** 2026-08-31 (medida no A12: 0,94 → 0,15 de um núcleo)
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** item 2 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md) — **fecha o item**
- **Commit:** — (o vínculo é o prefixo `TASK-0063:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## A decisão, e de quem é

Do dono do produto, em 2026-08-31, depois de ver os números da
[TASK-0057](TASK-0057-limitar-a-taxa-do-fundo-2d-da-biblioteca.md):

> "Essa animação de ondas apenas eleva o processamento inutilmente em dispositivos fracos. Vamos
> removê-la, e colocar um background com a mesma paleta de cor. Na versão antiga branch: version1
> não tínhamos isso, acredito que não seja útil para nós, sacrificar desempenho por algo de enfeite
> apenas."

A TASK-0057 tinha levantado três alavancas e dito que todas **mudam o que o usuário vê**, portanto
eram decisão de produto. Esta é a decisão: nenhuma delas — a animação sai inteira.

## O que estava custando

Biblioteca parada, sem jogo, Galaxy A12 (Mali-G52), medido na TASK-0057:

| thread | % de um núcleo |
|---|---|
| `RenderThread` | 41 |
| main | 25 |
| `hwuiTask0` / `hwuiTask1` | 10 cada |
| `mali-cmar-backend` | 8 |
| **total** | **~0,94 núcleo, contínuo enquanto a tela está aberta** |

Com ~69 desses pontos em rasterização e preenchimento — quatro faixas em alpha cobrindo ~72% do
painel cada.

## O objetivo, e por que não custa o visual

**Não é preciso inventar um fundo novo.** O HWUI só produz quadro quando alguma coisa invalida a
árvore de desenho; hoje quem invalida é a escrita em `timeSec`, uma vez por callback do
choreographer. Removendo o relógio, o `Canvas` é desenhado **uma vez** e o display list é
reaproveitado — nenhum quadro é pedido, e a `RenderThread` fica ociosa.

Ou seja: mesma cena, mesma paleta, mesmo gradiente, mesmas faixas e mesmos glifos — **parados**. O
usuário perde o movimento e nada mais.

## Escopo

**Entra:**

- `LibraryWaveBackground.kt` — sai o `LaunchedEffect` com `withInfiniteAnimationFrameNanos`, sai o
  `timeSec`, e a cena passa a ser desenhada num instante fixo. Some junto todo o maquinário que só
  existia por causa do custo por quadro: o `WaveScratch` (cache de gradientes), o reuso de `Path`,
  o limite de taxa. Nada disso faz sentido quando se desenha uma vez.
- A cor continua vindo de `LibraryBackgroundColorPreferences`, então o seletor de cor da biblioteca
  segue funcionando — que é o "mesma paleta de cor" do pedido.

**NÃO entra:**

- **O `XmbGlView`** (a onda XMB em GLES3, o caminho dos aparelhos onde o GL sobe). O mesmo argumento
  se aplica a ele — é uma thread EGL desenhando a 30 fps continuamente —, mas ele não foi medido, é
  o caminho de aparelhos mais capazes, e derrubá-lo junto seria decidir por conta própria uma coisa
  que não me foi pedida. **Fica anotado como pergunta**, não como pendência silenciosa.
- **O `SaverGlView`** (Flurry e os Really Slick). É opt-in explícito, desligado por padrão, e quem
  liga sabe o que está ligando.
- **Remover a preferência `animated2D`.** Ela deixa de ter efeito visível de movimento, mas continua
  escolhendo *este* fundo em vez do `XmbGlView` — o que ainda é uma escolha real, e mais barata.

## Efeito colateral que precisa ser dito

O **ciclo RGB** (`LibraryBackgroundColorPreferences.rgbCycle`) é, por definição, uma animação: ele
varre a roda de matiz ao longo de ~28 s. Com a cena parada ele não tem como variar, então neste
caminho a chave passa a render **uma cor fixa** em vez de um ciclo.

Não é regressão silenciosa por acaso — é consequência direta da decisão, e está aqui para que
ninguém a descubra como bug. Se incomodar, as saídas são desligar a chave na tela de ajustes quando
o caminho 2D está ativo, ou aceitar a cor fixa.

## O resultado

Galaxy A12, biblioteca aberta e parada, sem jogo, GOS morto, duas amostras com 30 s de
assentamento entre elas:

| | antes | depois |
|---|---|---|
| quadros desenhados em 15 s | 453 (**30 fps**) | **0** e **14** (≈0 fps) |
| `RenderThread` | 41 | **ausente da amostra** |
| main | 25 | 15 |
| `hwuiTask0` / `hwuiTask1` | 10 / 10 | **ausentes** |
| `mali-cmar-backend` | 8 | **ausente** |
| **total** | **~0,94 núcleo** | **~0,15 núcleo** |

**`RenderThread`, `hwui` e `mali` somem inteiramente da lista.** É o mecanismo previsto: sem nada
invalidando a árvore de desenho, nenhum quadro é pedido, e o caminho de rasterização inteiro fica
ocioso. O critério (1) — quadros ≈ 0 — é o que prova isso, e ele passou nas duas amostras.

Visualmente a cena está igual: mesmo gradiente, mesmas faixas, mesmos glifos, só parada.

### Os 15% que sobram na main **não** são o fundo

Com zero quadros desenhados, o `Canvas` não está sendo redesenhado — logo esses 15% de um núcleo na
UI thread são outra coisa, contínua, numa tela parada. Não foi investigado aqui porque está fora do
escopo desta task, mas **fica registrado**: é o próximo alvo se alguém quiser levar esta tela a
custo zero de verdade.

## Como validar


Com o app aberto **na biblioteca**, parado, sem jogo, e o GOS morto:

```bash
adb shell "sh /data/local/tmp/tsample.sh 15"     # consumo por thread
adb shell "sh /data/local/tmp/fps.sh 15"         # quadros realmente desenhados
```

Critério, contra a linha de base de ~0,94 núcleo:

1. **`fps` desenhados ≈ 0** parado (só quadros de interação). Hoje são 30/s.
2. **Total por thread bem abaixo de 0,2 núcleo** com a tela aberta e o dedo longe.
3. Captura de tela antes/depois: a cena deve estar **igual, só parada** — mesmo gradiente, mesmas
   faixas, mesmos glifos.

O critério (1) é o que prova o mecanismo: se ainda houver quadros sendo desenhados parado, alguma
outra coisa está invalidando a árvore e o ganho não vai aparecer.
