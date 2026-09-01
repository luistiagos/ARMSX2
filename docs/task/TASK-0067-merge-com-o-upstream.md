# TASK-0067: `git merge upstream/master` — 72 commits, incluindo correções de GS que não temos

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum confirmado — ver "O que esta task NÃO promete"
- **Commit:** — (o vínculo é o prefixo `TASK-0067:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

Relato de campo: linhas verticais no 007 no Galaxy A12, terceiro defeito gráfico seguido no mesmo
aparelho. A investigação de 2026-08-31 eliminou, lendo código, seis hipóteses — o workaround de
escalarização do compilador Mali (está **ativo** nos dois backends), push descriptors (já
desligados em Mali por `vendorID`), `BrokenImagelessFramebuffer`/`BrokenDynamicRendering` (os dois
recursos **não são usados** no `GSDeviceVK`), o caminho EGL/superfície (idêntico ao da 1.0.23), a
guarda de `eglSwapInterval` (o upstream tem proteção **a mais**), e a ausência dos dois fixes de GS
do upstream (**já estão** na nossa árvore, linhas 4090 e 2610-2619).

Nenhuma virou causa. Então, em vez de escrever uma quarta correção sobre palpite, aplicou-se a
regra do `CLAUDE.md` que ninguém tinha aplicado nesta cadeia:

> Antes de corrigir qualquer coisa no core, **verifique se o upstream já resolveu**. Na linha
> anterior, três dos cinco defeitos investigados já tinham resposta lá.

**Estamos 72 commits atrás.** E o fork foi desenhado exatamente para que atualizar fosse
`git merge upstream/master`, não portar commit a commit.

## O que o merge custou, medido

`git merge-tree` (não destrutivo) previu **5 arquivos em conflito**, e o merge real confirmou:

| arquivo | blocos | como foi resolvido |
|---|---|---|
| `common/Linux/LnxMisc.cpp` | 3 | **combinado** — ver abaixo |
| `platforms/android/.../cpp/native-lib.cpp` | 1 | nosso método JNI novo + o comentário atualizado deles (o upstream tornou "Performance Cores" o default do affinity) |
| `.../navigation/NavigationDrawer.kt` | 1 | **os dois** — nossa Fila de Download, mais Boot BIOS e Launch Game deles |
| `.../ui/home/HomeViewModel.kt` | 1 | **os dois** — nossos locais de filtro, mais o filtro por categoria deles |
| `.../ui/home/HomeScreen.kt` | 3 | **o nosso, inteiro** — ver "O que ficou de fora" |

**Todos os arquivos de GS fizeram auto-merge limpo.** É a carga útil do merge, e ela entrou sem
intervenção.

`platforms/android/gradle.properties` **não é tocado pelo upstream**: `applicationId`,
`versionCode` e `versionName` sobrevivem intactos, conferido antes e depois.

### `LnxMisc.cpp`: os dois lados tinham razão em metades diferentes

Nossa [TASK-0060](TASK-0060-relogio-de-ticks-quando-cntfrq-le-zero.md) e o `19dec9c211` deles
consertam o mesmo defeito (`CNTFRQ_EL0` lendo 0 no A12). Ficou a **nossa estrutura** —
`ArchTimerFrequency()` nomeada, com o log e os comentários do que foi medido no aparelho — com o
**limiar deles**: `f < 1000000u` em vez do nosso `f == 0`.

O limiar deles é melhor e a diferença não é cosmética: firmware que programa um valor sub-MHz
absurdo deixa o relógio tão inutilizável quanto firmware que não programa nada, e só o limiar
rejeita os dois casos. Devolver `0` mantém os três chamadores no mesmo teste único que já tinham.

## O que ficou de fora, e é decisão a tomar

**`HomeScreen.kt` ficou com a NOSSA versão inteira.** Os dois lados reestruturaram a mesma
`LazyGrid` — nós 508 inserções / 177 remoções (seção de downloads e o resto da tela atual), eles
606 inserções (retrabalho do "Recently Played" e as prateleiras por categoria). Um splice mecânico
produziu chave desbalanceada e a tela não compilou; o defeito real é que **as duas reescritas
ocupam o mesmo espaço** e reconciliá-las é decisão de produto sobre como a biblioteca compõe, não
resolução de conflito.

Como o objetivo deste merge é o **core gráfico**, e não a UI da biblioteca, ficou a nossa tela.
Consequência concreta e conhecida:

- as **prateleiras por categoria** e o **filtro por categoria na lista** do upstream entram no
  `HomeViewModel` (o estado existe e funciona) mas **não têm UI** — recurso dormente, não quebrado;
- nada da tela atual muda para o usuário.

Reconciliar as duas telas é uma task própria, com o dono do produto decidindo o que fica.

## O que esta task NÃO promete

**Não é a correção das linhas verticais nem da tela preta.** Nenhum dos 72 commits foi identificado
como o conserto de um dos dois — os dois candidatos óbvios de GS já estavam na nossa árvore. O que
o merge faz é parar de correr atrás de uma pilha gráfica que é consertada lá, e entregar 72
commits de correções que simplesmente não tínhamos.

Se depois do merge os defeitos continuarem, isso é **informação**: elimina "está consertado lá e a
gente não puxou" de uma vez, em vez de uma hipótese por rodada.

## Como será validado

1. **Kotlin** — `:app:compileGithubDebugKotlin` com `-Pkotlin.incremental=false`. ✅ passou.
2. **Nativo** — build completo do `emucore_4k` em árvore limpa, com as sete pastas do shaderc
   copiadas para o worktree do merge (elas são `gitignore`d e não acompanham worktree novo).
3. **Identidade** — `applicationId`/`versionCode`/`versionName` conferidos. ✅ intactos.
4. **Rastreabilidade** — `check_traceability.py`.
5. **No aparelho** — APK no A12, 007 em Vulkan e em OpenGL. As duas respostas são resultado.

## Nota de processo

O merge foi feito num **worktree isolado** (`D:/projects/play2/ARMSX2-merge`, ramo
`merge/upstream-2026-08-31`) porque a árvore principal tinha trabalho não commitado de outra sessão,
que continuou avançando durante esta. Merge de 72 commits por cima de árvore suja de terceiro não
se faz.
