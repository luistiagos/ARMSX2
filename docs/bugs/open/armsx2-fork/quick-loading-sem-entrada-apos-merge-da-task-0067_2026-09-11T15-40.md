# Bug: o Quick Loading do upstream está no binário, mas o merge da TASK-0067 perdeu a entrada dele

- **Detectado em:** 2026-09-11 15:40 (validação em aparelho da TASK-0090, SM-A127M)
- **Origem:** **merge** — o recurso é do upstream; a perda é nossa, no merge `e047ce36fe`
  (TASK-0067), que manteve o nosso `HomeScreen.kt` inteiro
- **Errors (serviço):** nenhum — não é crash, é ausência; não gera telemetria
- **Classe:** fail (recurso inalcançável)
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0096](../../../task/TASK-0096-devolver-a-entrada-do-quick-loading.md)
- **Relacionado:** [TASK-0090](../../../task/TASK-0090-duas-correcoes-de-campo-do-upstream.md) —
  onde o defeito apareceu, porque a correção `c2bea2f029` dela não tem como ser exercitada

## Sintoma

No RetroSystem PS2 não existe nenhum jeito de disparar o **Quick Loading** — o recurso que o
upstream lançou na 2.6.7 (*"Host ELF method now functional on Android with automatic disc file
extraction"*). No aparelho, o toque longo em *God of War II* (`.iso`, 4,27 GB — exatamente onde o
upstream oferece a opção) abre uma folha com **Jogar, Configurações, BIOS por jogo, Região de
cobertura, Memory Cards, Adicionar à tela inicial, Remover dos reproduzidos recentemente, Ocultar da
biblioteca, Deletar jogo**, e nenhuma delas é "⚡ Quick Loading". O menu lateral também não tem.

Nenhum usuário reclamou — e não teria como: não se sente falta de uma opção que nunca apareceu.

## Causa — provada pelo call-site, não pelo nome

1. `com.armsx2.QuickLoadSetup` existe e compila (`QuickLoadSetup.kt:29`, `run()` na linha 92). Ele é
   o **único** chamador de `NativeApp.extractIsoToHostfs`.
2. `QuickLoadSetup` **não tem nenhum chamador** em `platforms/android/app/src/`, em nenhum source set
   (`git grep QuickLoadSetup HEAD`, conferido em 2026-09-11).
3. No `upstream/master` ele é chamado de `ui/home/HomeScreen.kt`, e só de lá:

   | Linha (upstream) | O que é |
   |---|---|
   | 176–199 | estado (`quickLoadIso`, `…Busy`, `…Result`, `…Confirm`, `…Remove`) e o `quickLoadElfPicker` (SAF), que roda `QuickLoadSetup.run` em `Dispatchers.IO` e dá `viewModel.refresh()` |
   | 1119–1130 | ação **⚡ `games.quickLoad`** no menu do jogo — **só para discos**, nunca para `.elf` |
   | 1143–1148 | ação **🧹 `games.quickLoad.remove`**, só quando `QuickLoadSetup.isInstalledElf(game)` |
   | 1167–1213 | diálogo de confirmação **antes** do seletor: espaço necessário × livre, aviso com folga de 1,15× |
   | 1215–1252 | diálogo de remoção |
   | 1254–1280 | indicador de "trabalhando" e diálogo com o resultado |

4. O merge `e047ce36fe` (TASK-0067; segundo pai `6a86b38ebf`) registrou que **"`HomeScreen.kt` ficou
   com a NOSSA versão inteira"** e anotou a perda das prateleiras por categoria. **Não** percebeu que
   a entrada do Quick Loading morava no mesmo arquivo. O `QuickLoadSetup.kt` entrou por auto-merge,
   como arquivo novo, com o único chamador descartado.

## O que continua funcionando, e reduz o tamanho do conserto

- **A biblioteca ainda varre o `hostfs`** (`GameLibraryRepository.kt:74`, idêntico ao upstream), então
  o ELF extraído apareceria como entrada normal assim que a extração existir.
- **As 14 chaves `games.quickLoad*` já estão no `I18n.kt`** — entraram junto com o merge. **Nenhuma
  está no `assets/i18n/pt-BR.json`**: o usuário pt-BR veria o fluxo inteiro em inglês.
- A extração nativa (`extractIsoToHostfs`) já tem a correção de page cache da TASK-0090.

## Por que importa além do recurso

A correção `c2bea2f029` (TASK-0090) entrou no produto e **nunca rodou num aparelho nosso**. Ela é
inofensiva enquanto dormente, mas "correta para nós" ainda não foi provado — e só pode ser quando
esta entrada voltar.

E há uma lição de processo que vale para todo merge futuro: **um merge que mantém "a nossa versão
inteira" de um arquivo perde, em silêncio, todo recurso do upstream cuja entrada morava nele.** O
`QuickLoadSetup.kt` continuou compilando, o que dá a impressão de que o recurso veio.
