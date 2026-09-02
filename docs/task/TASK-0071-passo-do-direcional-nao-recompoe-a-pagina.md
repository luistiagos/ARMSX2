# TASK-0071: um passo do direcional recompõe duas linhas, não a página inteira

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread](../bugs/open/configuracoes-cada-ajuste-reescreve-o-config-inteiro-na-ui-thread_2026-08-31T18-40.md) — **item 2 apenas**
- **Commit:** — (o vínculo é o prefixo `TASK-0071:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Mover o destaque em Configurações deixa de recompor todas as linhas da aba e o cabeçalho da tela.
Medido no SM-A127M, um passo custa hoje **57 ms (10 linhas) a 117 ms (57 linhas)** de p90, com a GPU
em 13 ms — é recomposição, não desenho.

## De onde vem o número

Medição registrada no bug, 2026-08-31, SM-A127M, APK 1.0.24/vc38 **debug**, escopo Global (nenhuma
tecla grava nada). `gfxinfo reset` → 10 × `input keyevent 20` → `gfxinfo`:

| Aba | Linhas | p50 | p90 | Slow UI thread |
|---|---|---|---|---|
| Áudio | 10 | 25 ms | 57 ms | 10 |
| Desempenho | 28 | 40 ms | 97 ms | 24 |
| App | 57 | 101 ms | 117 ms | 9 de 9 |

`framestats` de um quadro: `HandleInput→Animation` 0,005 ms, **`Animation→PerformTraversals`
48,7 ms**, `PerformTraversals→Draw` 0,22 ms.

Duas causas somadas, e a tabela mostra as duas: um **custo por linha** (117 vs 57 ms entre 57 e 10
linhas) e um **piso fixo de ~57 ms** que aparece mesmo com 10 linhas na tela.

## Causa

1. **Por linha.** `controllerFocusable` lê `SettingsControllerNav.isSelected(id)` dentro do próprio
   `Modifier.composed{}` ([`SettingsWidgets.kt:505`]). Isso lê `selectedId`, um `mutableStateOf`
   **global**, de dentro de composição — então **toda** linha registrada vira assinante dele. Um
   passo do direcional invalida as 57, quando só 2 mudaram de cor.
2. **Piso fixo.** `SettingsScreen` lê `SettingsControllerNav.selectedIndex.intValue` como chave de
   um `LaunchedEffect`, **no corpo do composable** ([`SettingsScreen.kt:148`]). A expressão da chave
   é avaliada em composição, então o escopo da tela inteira — `ArmsTopBar`, o seletor de escopo, as
   12 chips de categoria, o painel — recompõe a cada passo.

## Escopo

**Entra:**

- `SettingsWidgets.kt::controllerFocusable` — a linha assina só o **próprio booleano**, via
  `remember(controllerId) { derivedStateOf { SettingsControllerNav.isSelected(controllerId) } }`.
  `derivedStateOf` recalcula quando `selectedId`/`layers` mudam, mas só invalida quem o lê **se o
  resultado mudar** — que é exatamente "as 2 linhas que trocaram de estado".
- `SettingsScreen.kt` — a leitura de `selectedIndex` sai do corpo do composable e vai para dentro do
  `LaunchedEffect`, via `snapshotFlow { … }.collectLatest { … }`. `collectLatest` (e não `collect`)
  porque `LaunchedEffect(chave)` cancelava o bloco anterior a cada mudança, e o bloco chama
  `animateScrollTo` — trocar por `collect` mudaria o comportamento: a animação em curso não seria
  cancelada e o coletor ficaria bloqueado nela.
  O `chipSnapArmed` (o `remember` que pula a primeira execução) vira um `var` local do coroutine:
  `snapshotFlow` emite o valor inicial ao começar a coletar, igual ao `LaunchedEffect`, e o efeito
  não reinicia mais.

**NÃO entra:**

- **O item 1 do bug** (a gravação: 3× `loadGlobal`, 222 chamadas JNI, `writeBackupMirror`, escrita
  do INI). É o trabalho pesado comprovado por leitura, mas **não foi isolado no aparelho** e a
  variante pesada — escopo Jogo com VM rodando — nunca foi exercitada. Medir antes de mexer.
- **O item 3 do bug** (`ControllerAutoScroll` pedindo quadro a 60 Hz com velocidade zero).
- **`EmulationMenuScreen.kt:1508`**, que chama `isSelected` direto e não passa por
  `controllerFocusable` — o menu de pausa tem o mesmo defeito e **não é corrigido aqui**. Fica
  registrado, não silenciado.
- **Tornar a página lazy.** Não é opção: o registro de foco acontece em `SideEffect` de filho
  COMPOSTO, e o comentário em `SettingsScreen.kt::SettingsCategoryBar` documenta que um `LazyRow`
  já quebrou exatamente isso nas chips.
- **Trocar `Modifier.composed{}` por `Modifier.Node`.** É a terceira fonte de custo por linha
  (remateria a cadeia inteira a cada recomposição), mas é refatoração de outra ordem e só vale
  depois de remedir — se as duas mudanças acima já derrubarem o p90, ela pode não ser necessária.

## Como validar

Mesmo protocolo da medição de origem, SM-A127M, escopo Global, aba assentada antes do `reset`:

```bash
adb shell dumpsys gfxinfo come.nanodata.armsx2 reset
adb shell "input keyevent 20 20 20 20 20 20 20 20 20 20"
adb shell dumpsys gfxinfo come.nanodata.armsx2
```

Critérios, nas três abas (Áudio 10, Desempenho 28, App 57 linhas):

1. **p90 < 16,7 ms** — o alvo. Em build de debug; se ficar entre 16,7 e ~35 ms, remedir em release
   antes de chamar de falha (pela TASK-0058 o release é materialmente mais rápido nesta árvore).
2. **O p90 para de escalar com o número de linhas.** É o que prova o mecanismo: se App (57) continuar
   muito acima de Áudio (10), a assinatura por linha não foi cortada.
3. **`Number Slow UI thread` cai** de "um por tecla" para ~0.
4. Comportamento inalterado, à mão: o anel de foco anda linha a linha; a linha selecionada entra em
   vista sozinha (`bringIntoView`); subir até a fila de chips ainda leva a página ao topo; **e a
   primeira abertura da tela NÃO salta para o topo** — é a regressão que o `chipSnapArmed` existe
   para evitar, e é exatamente o que estou mexendo.

## Resultado

**Ganho real, mas o critério 1 NÃO foi atingido, e a medição derrubou metade do meu modelo.**

Mesmo aparelho, mesmo protocolo, APK debug reinstalado com as duas mudanças:

| Aba | Linhas | p50 antes → depois | p90 antes → depois |
|---|---|---|---|
| Áudio | 10 | 25 → **25 ms** | 57 → **57 ms** |
| Desempenho | 28 | 40 → **38 ms** | 97 → **73 ms** (−25 %) |
| App | 57 | 101 → **61 ms** (−40 %) | 117 → **97 ms** (−17 %) |

Decomposição por `framestats`, depois da mudança:

| | recomp | layout | draw | issue |
|---|---|---|---|---|
| App (57), quadro da tecla | **25,4 ms** (era 48,7) | 0,44 | 14,5 | 6,2 |
| App (57), quadros seguintes | 3,2 | 0,35 | 7,8 | 8,1 |
| Áudio (10), todo quadro | **3,5 ms** | 0,33 | 5,8 | 7,5 |

### O que estava certo

A recomposição do quadro da tecla **caiu pela metade** na aba pesada: 48,7 → 25,4 ms. É a mudança A
fazendo o que prometia.

### O que estava errado, e é o achado desta task

**O piso de 57 ms nunca foi recomposição.** Eu atribuí esse piso ao corpo do `SettingsScreen`
recompondo (mudança B) — e a mudança B não moveu a aba Áudio em **nada**: 25/57 antes, 25/57 depois.
A decomposição mostra por quê: com 10 linhas na tela a recomposição é 3,5 ms, e o quadro é
`draw` 5,8 + `issue` 7,5 ≈ **13,3 ms de HWUI**, todo quadro. O gargalo do piso é **desenho da página
inteira**, não Compose.

Isso não invalida a mudança B — ela remove uma invalidação de tela inteira que é real e aparece na
aba App (p50 101 → 61) —, mas invalida a explicação que eu tinha dado para o piso.

**E a recomposição ainda escala com o número de linhas**: 3,5 ms com 10 linhas contra 25,4 ms com 57.
Ou seja, alguma coisa **ainda** invalida por linha num passo do direcional; o `derivedStateOf`
cortou metade, não tudo. Descobrir o que sobrou precisa de contagem de recomposição ou profiler —
não dá para continuar por leitura, que é como eu errei o piso.

### Estado

Fica **em andamento**. O critério 1 (p90 < 16,7 ms) não foi atingido em nenhuma das três abas; o
critério 2 (parar de escalar com as linhas) foi atingido só parcialmente. Os critérios 3 e 4 estão
por conferir à mão.

Próximas alavancas, agora com dado em vez de hipótese:

1. **`draw` + `issue` ≈ 13 ms por quadro mesmo com 10 linhas** — é o custo de redesenhar a página
   não-lazy a cada quadro da animação do `bringIntoView`. É a maior fatia do piso e não tem nada a
   ver com Compose.
2. **Os 25 ms que sobraram na aba App** — achar quem ainda invalida por linha.
3. Os itens 1 e 3 do bug seguem intocados.

## Validação de comportamento (critério 4)

Feita no SM-A127M em 2026-09-01, na build já instalada (a do commit desta task):

| Item | Resultado |
|---|---|
| Anel de foco anda linha a linha | ✅ 8 × Baixo levaram a seleção do topo até a chip de tema "Personalizado" |
| Linha selecionada entra em vista sozinha (`bringIntoView`) | ✅ a página acompanhou a seleção |
| Subir até a fila de chips leva a página ao topo | ✅ depois de rolar para baixo, 12 × Cima trouxeram a barra "Configurações" inteira de volta |
| **Reabrir NÃO salta para o topo** | ✅ a barra de topo fica rolada para fora ao reabrir, ou seja o `animateScrollTo(0)` não disparou — a guarda de primeira emissão sobreviveu à troca de `LaunchedEffect(chave)` por `snapshotFlow` |

### A observação que estava em aberto: RESOLVIDA — não é regressão minha

Ficara registrado que reabrir devolve sempre o **mesmo ponto perto do topo**, não o deslocamento
onde se parou, e que eu não sabia se era meu. O A/B ficou bloqueado na sessão anterior porque a
árvore não compilava (`RecentGamesAccess`, do merge). Com o merge commitado e a árvore limpa,
o A/B foi feito em 2026-09-01:

- ninguém tocou nos dois arquivos depois do meu commit, então `c67fb87bff~1` difere do HEAD
  **exatamente** pela minha mudança — A/B limpo;
- dois APKs, mesmo aparelho, mesmo roteiro (abrir Configurações → rolar → Voltar → reabrir).

Resultado: as três capturas de reabertura — **duas com a mudança e uma sem** — são
**byte-idênticas**, mesmo sha256 `665a571da1ff817e`, 124 239 B. Ou seja, o comportamento é o mesmo
com e sem a mudança: **é anterior, não regressão**. O `SettingsScrollMemory` não devolve o
deslocamento exato, e nunca devolveu; quem quiser isso abre uma task própria.

Com isso o **critério 4 passa inteiro**.

### Remedição pós-merge

Depois do `git merge upstream/master` (TASK-0067), com a mudança, aba App, mesmo protocolo:
**p50 57 ms / p90 85 ms** — contra 101/117 antes da mudança e 61/97 logo depois dela, antes do
merge. O ganho se manteve; o merge não o desfez.

### Armadilha de roteiro, para a próxima medição

O merge acrescentou "BIOS de inicialização" e "Launch Game" à gaveta, e **"Configurações" desceu de
y≈645 para y≈877**. Um `input tap 219 645` agora cai em "Launch Game", que abre o seletor SAF do
Android — o teste sai do app sem avisar e as capturas seguintes são do seletor de arquivos, não do
app. Conferir a tela antes de tocar, em vez de reusar coordenada.
