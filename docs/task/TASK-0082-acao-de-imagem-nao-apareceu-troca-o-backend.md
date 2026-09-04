# TASK-0082: ação "a imagem não apareceu" troca o backend por jogo e reinicia

- **Status:** em andamento
- **Criada em:** 2026-09-04
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [renderer-automatico-sem-rede-de-seguranca-no-fork](../bugs/open/armsx2-fork/renderer-automatico-sem-rede-de-seguranca-no-fork_2026-08-31T20-00.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0082:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

A [TASK-0066](TASK-0066-rede-de-seguranca-do-renderer-automatico.md) fechou **uma** das duas classes
do bug: a sessão que **morre antes de apresentar um quadro**. O marcador em `EmuFolders::Cache`
existe, é armado em `GetPreferredRenderer`, aposentado em 600 quadros ou no `Host::OnVMDestroyed`, e
limpo quando o usuário escolhe renderer à mão. Confirmado lendo o código, não a triagem.

A outra classe continua descoberta e é a que o A12 exibe todo dia: **apresenta quadros que ninguém
vê**. `Host::BeginPresentFrame` é chamado normalmente, a VM e o áudio seguem, e do lado de dentro do
emulador a sessão preta é indistinguível de uma boa. O próprio relatório do bug e o plano gráfico
proíbem a única forma de distinguir por dentro — amostrar pixels, com número medido: *38 falsos
positivos em 6 modelos*, e o registro do
[`GraphicsHealthMonitor`](../bugs/open/legado-version1/graphicshealthmonitor-falso-positivo-cenas-escuras_2026-08-23T13-57.md)
mostra o falso positivo em cena escura.

A saída que sobra, e que o relatório já anotava como decisão de produto pendente, é **perguntar em
vez de adivinhar**: uma ação no menu que já existe, do tipo *"a imagem não apareceu"*, que troca o
backend **por jogo** e reinicia. Custa um toque e não custa nenhum falso positivo.

## O que já existe, e por que não basta

O contorno documentado no bug da tela preta é: *menu em jogo → Renderer → Vulkan → reiniciar*. Ele
funciona — o overlay de toque fica **por cima** da área de render, então o menu é alcançável mesmo
com a tela preta — e já grava por jogo, porque `InGameOverlay.saveSettings` persiste em
`SettingsScope.Game` quando há serial.

O que falta não é mecanismo, é **caminho**: são três telas, o nome do problema ("tela preta") não
aparece em lugar nenhum, e quem não sabe o que é "backend gráfico" não chega lá. É exatamente a
mesma crítica que o bug faz à saída do crash-loop: *"a saída existe, mas exige que o usuário saiba
que ela existe"*.

## Escopo

**Entra:**

- `runtime/RendererRecovery.kt` (novo) — função **pura** que decide o próximo backend a partir do
  que está gravado para o jogo e do veredito do `auto`. Pura para poder ser testada sem aparelho.
- `ui/emulation/EmulationMenuViewModel.kt` — estado do pedido + confirmação, e a aplicação
  (grava no escopo do jogo e reinicia a VM).
- `ui/emulation/EmulationMenuScreen.kt` — a ação na **primeira aba** do menu (Sessão), que é a que
  abre por padrão, mais uma entrada na aba Renderer, onde quem já sabe do que se trata vai procurar.
- `i18n/I18n.kt` + `assets/i18n/pt-BR.json` — as chaves novas.
- `app/src/test/.../RendererRecoveryTest.kt` — a escada, inclusive o caso do veredito ausente.

**Não entra:**

- **Qualquer detecção automática de tela preta.** É a proibição central do relatório.
- **Amostragem de pixel.** Idem.
- Mexer no marcador da TASK-0066 ou no `GSUtil.cpp`. Nada em `pcsx2/` — a ação usa a superfície JNI
  que já existe (`renderVulkan` / `renderOpenGL` / `renderSoftware`, via `applyRendererPrefs`).
- Perguntar sozinho ("parece que ficou preto, quer trocar?"). Isso é classificar por outro nome.

## A escada, e por que ela é derivada e não guardada

O próximo backend sai de duas coisas que já existem: o `renderer` **gravado para este jogo** e o
veredito do `auto` (`NativeApp.getAutoRendererVerdict()`, TASK-0065). Sem contador novo, sem estado
novo em disco:

| gravado | próximo | por quê |
|---|---|---|
| `auto` | o **oposto** do que o `auto` escolheu | o que falhou foi a escolha do `auto` |
| igual ao que o `auto` escolheria | o oposto | o usuário fixou o mesmo; trocar é o passo útil |
| oposto ao do `auto` | `software` | a troca de backend já foi tentada e não resolveu |
| `software` | `auto` | fecha o ciclo e devolve a escolha automática |

Chega ao software em no máximo dois toques e volta ao começo no terceiro. O diálogo **diz qual
backend vai usar** antes de reiniciar, então nada disso precisa ser adivinhado pelo usuário.

**Limite conhecido, e ele fica escrito:** `getAutoRendererVerdict()` devolve o que
`AndroidAutoPrefersVulkan` decidiu no arranque do app, e **não** reflete a virada que o marcador da
TASK-0066 faz dentro de `GetPreferredRenderer`. Se um bloqueio estiver ativo, o renderizador em uso
é o oposto do veredito, e o primeiro toque a partir de `auto` escolheria justamente o que já está
rodando. O segundo toque corrige (o gravado deixa de ser `auto`). Corrigir de vez exigiria expor o
renderizador efetivo pelo JNI, o que é build nativo de ~14 min por um caso que precisa de crash-loop
**e** tela preta no mesmo aparelho. Fica registrado, não fica escondido.

## Como será validado

1. **Teste de unidade** — `:app:testGithubDebugUnitTest`, cobrindo as quatro linhas da tabela e o
   veredito vazio.
2. **No aparelho (`SM-A127M`, Mali-G52 r38)** — 007: Everything or Nothing com `renderer=auto`
   reproduz a tela preta em OpenGL. Abrir o menu, usar a ação, e comprovar por captura de tela que
   a imagem aparece depois do reinício, com o log dizendo o backend novo.
3. **O que está gravado é por jogo** — ler `shared_prefs/ARMSX2.xml` antes e depois e mostrar que só
   a chave `config.game.SLUS-20751` mudou, preservando os outros campos já fixados nela.
