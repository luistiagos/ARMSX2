# TASK-0037: gerir as pastas de ROM numa tela, não no assistente de primeira execução

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0037:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Relato do usuário: *"ao clicar no menu hambúrguer e ir em alterar pastas, vai para a tela padrão de
configuração inicial do ARMSX2 oficial, com o botão próximo e voltar. Isto não existe no nosso.
Podemos deixar esta funcionalidade, porém precisa ser um menu igual às outras opções, que podemos
entrar e sair, e não um wizard inicial."*

## O que acontecia

A linha "Configurar/alterar pastas" da gaveta chamava `MainActivityRuntime.reopenSetup()`, que abre
o **assistente de primeira execução**: cinco páginas com "Próximo" e "Voltar", pedindo local dos
dados e BIOS antes de deixar sair. Um caminho de mão única para quem só queria acrescentar uma pasta.

A [TASK-0022](TASK-0022-primeira-impressao-do-app.md) já havia tirado o assistente do arranque. Esta
tira dele o último lugar que ainda o abria — e, com isso, o assistente deixa de aparecer sozinho em
qualquer fluxo normal.

## O que existe agora

`AppRoute.RomFolders` → `ui/folders/RomFoldersScreen.kt`: uma tela como as vizinhas (Memory Cards,
Controles, Packs de Texturas). Barra com seta de voltar, a explicação do que é aceito, a lista de
pastas com **Remover** em cada uma, e **Adicionar Outra Pasta** no fim. Entra, mexe, sai.

## A lógica não foi reescrita

Adicionar e remover continuam sendo `OnboardingViewModel.addGameFolder` e `removeGameFolder` — a
tela nova reusa o mesmo view model. Duplicar isso criaria **duas verdades sobre a mesma lista**, e a
que esquecesse `takePersistableUriPermission` perderia o acesso à pasta no próximo arranque, em
silêncio. O assistente continua existindo e continua correto; só não é mais o caminho de todo dia.

Duas escolhas de detalhe, cada uma por um motivo:

- **`Column` + `verticalScroll`, nunca `LazyColumn`** — a navegação por controle registra cada linha
  num `SideEffect` quando ela compõe, e o `Lazy` não compõe o que está fora da tela: a seleção
  emperraria no meio da lista. É a mesma razão que a `BiosManagerScreen` documenta.
- **Sem subtítulo na barra.** A primeira versão punha ali a contagem de pastas reusando o rótulo
  "Biblioteca", e na tela lia-se "Biblioteca: 1" — que parece contagem de **jogos**. A lista abaixo
  já mostra quantas são.

## Como validar

No Galaxy A12: gaveta → "Configurar/alterar pastas" abre

```
←  Selecione a pasta de ROMs
   Escolha uma ou mais pastas onde você guarda seus jogos de PS2. Suporta ISO, CHD, BIN, IMG, MDF e GZ.
   ▦ roms                                    Remover
   Adicionar Outra Pasta
```

Sem "Próximo"/"Voltar". A seta devolve à biblioteca (`Total de jogos: 12628`).

**Não exercitado:** adicionar uma pasta de verdade pelo seletor do sistema — exige interação humana
com o `OpenDocumentTree`. O caminho é o mesmo que o assistente já usava.

## Resultado

Entregue.
