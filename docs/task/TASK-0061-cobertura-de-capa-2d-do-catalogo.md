# TASK-0061: medir a capa 2D que aparece, e não o campo preenchido

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum registrado — o defeito foi encontrado pela medição desta task
- **Commit:** — (o vínculo é o prefixo `TASK-0061:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

O manifesto tem **12.305 linhas e todas as 12.305 têm uma URL de capa**. Contado assim, o
catálogo estava em 100%.

Não estava. Uma URL preenchida não é uma capa: ela pode apontar para um arquivo que não existe.
Cruzando o manifesto com a listagem real dos dois repositórios de arte:

| origem da URL | linhas | apontam para arquivo ausente |
|---|---:|---:|
| `xlenore/ps2-covers` (`covers/default`) | 2.978 | **268** |
| `libretro-thumbnails/Sony_-_PlayStation_2` | 5.318 | **190** |
| dubsgamer, IGDB, hfsplay e outros | 2.653 únicas | **14** |

**472 linhas tinham uma URL morta**, e para 469 delas o serial também não tinha arte no repo —
ou seja, nenhuma das duas pernas do fallback de `GameCoverArt` respondia e o cartão caía no
`GameCoverPlaceholder`. A cobertura real, no começo desta task:

- **96,19%** por linha do manifesto;
- **93,36%** por célula da grade, que é o que o usuário vê — `CatalogParser.groupKey` junta as
  cinco linhas de "007 - Nightfire" numa célula só, então as 12.305 linhas viram 6.311 células.

A segunda é a medida que importa e era a que estava **abaixo do piso de 95%**.

## Objetivo

Levar a cobertura de capa 2D dos **jogos** acima de 95%, e deixar a medição repetível — a
conta que dava 100% era o próprio defeito.

## Escopo

**Entra:**

- `scripts/check_cover_coverage.py` — **novo.** Reproduz a cadeia de fallback do app no modo 2D
  (URL do manifesto → arte do repo pelo serial) e conta o que responde, não o que está
  preenchido. A liveness dos dois repositórios grandes sai de **uma** listagem da API do GitHub
  cada — travessia de árvore, porque `?recursive=1` no `ps2-covers` estoura o limite e volta 500
  —, e só as ~2,6 mil URLs de outros domínios vão para a rede. Separa jogo de disco-que-não-é-jogo
  e aceita `--min` para virar portão.
- `catalog_manifest_ps2.txt` e a cópia em `platforms/android/app/src/main/assets/` — **76 linhas**
  ganham uma URL que responde, no lugar de uma que não respondia. As duas cópias continuam
  byte-idênticas. De onde saiu cada capa:

  | como foi resolvida | linhas |
  |---|---:|
  | lançamento equivalente em outra região (mesmo `name-en` no `GameIndex.yaml` → serial com arte) | 40 |
  | título casado por conjunto de tokens, com os números batendo | 18 |
  | irmão do próprio manifesto com o mesmo título normalizado | 9 |
  | nome exato no `libretro-thumbnails` | 7 |
  | serial → outro nome Redump do MESMO serial, presente no `libretro-thumbnails` | 2 |

  As 40 da primeira linha são o caso dos lançamentos coreanos e japoneses: `SLKA-25056` não tem
  arte, mas o `name-en` dele ("Disney/Pixar Finding Nemo") leva ao serial ocidental que tem.
- `GameInfo.kt` — `repoCoverUrlFor(serial, platform)` e `catalogRepoCoverUrl(fileName)` viram
  funções de topo. A URL do repositório era remontada à mão em cada tela, e **a tela que
  esquecia ficava sem a rede de proteção do serial**.
- `ui/catalog/DownloadQueueSection.kt` — a capa da fila de download tinha `AsyncImage` sem
  `error`: uma URL morta virava o glifo `↓` enquanto o cartão do mesmo jogo, na grade, continuava
  mostrando a capa. Passa a ter a cadeia inteira (manifesto → repo → glifo) e, de quebra, passa a
  seguir a troca 2D↔3D, que antes só chegava ali no próximo `republish`.
- `ui/catalog/GameVersionsModal.kt` — o mesmo buraco no painel de escolha de versão: `error`
  desenhava um retângulo cinza em vez de tentar a outra URL. As duas fontes falham por motivos
  independentes, então a que sobrou costuma responder.
- `ui/home/HomeViewModel.kt` — ao carimbar um jogo baixado com a capa do catálogo, o fallback
  passa a ser a capa do **grupo**, não só a da linha. Baixar uma variante sem capa própria
  trocava a célula do catálogo (que mostra a arte da primeira variante que tem uma) por um
  arquivo local sem arte nenhuma: o jogo piorava ao ser baixado, que é exatamente o que a
  TASK-0045 tinha ido consertar para o caso do serial.

**NÃO entra:**

- **Retirar do catálogo os 222 discos que não são jogos.** São discos de cheat (Action Replay,
  CodeBreaker, Max Drive), coletâneas de revista (Dengeki, GamePro, Play-Pre 2, Offizielle
  PlayStation 2 Magazin), atualização de firmware do DESR, desbloqueador de região de DVD e
  navegador. Só 20,27% deles têm arte, e é o que segura a conta de TODAS as células em 94,30%.
  Retirá-los é decisão de produto — quem já tinha retirado 323 do mesmo tipo nesta mesma árvore
  não terminou o trabalho —, e não é a mesma coisa que "incluir capa".
- **Achar arte para os 183 jogos que continuam sem.** São lançamentos coreanos (`Jin Samguk
  Mussang`, `Kaido Battle`), demos japonesas, quizzes austríacos e hacks PT-BR sem base
  identificável. Nenhuma das duas fontes públicas tem box art para eles, e o
  `missing_covers.txt` do próprio `xlenore/ps2-covers` confirma a ausência. Fechar essa lista
  exige uma fonte com chave de API (IGDB/TheGamesDB), o que é outra task.
- **Apagar as ~399 URLs mortas que sobraram no manifesto.** Não há substituta para elas, e com
  o `error` corrigido nas três telas o app já cai no fallback sozinho. O custo é uma requisição
  que falha por cartão; o risco de apagar é perder uma URL que só estava fora do ar.
- Qualquer edição em `pcsx2/` ou `common/`. O delta no core continua zero.

## Como validar

1. `python scripts/check_cover_coverage.py --min 95` — sai 0 e imprime as três contagens.
   Medido em 2026-08-31, depois desta task:

   ```
   manifesto: 12305 linhas, 6311 titulos
   copia em assets/: identica
   indice de capas: 10429 no ps2-covers, 8503 no libretro-thumbnails
   POR LINHA   : 11912/12305 = 96.81%
   POR CELULA  : 5951/6311 = 94.30%
     JOGOS     : 5906/6089 = 96.99%
     nao-jogos : 45/222 = 20.27%
   ```

2. `:app:compileGithubDebugKotlin` compila.
3. `:app:testGithubDebugUnitTest` passa.
4. **Aparelho, pendente.** As três pernas do fluxo com o mesmo jogo, um cuja URL de manifesto
   esteja morta (por exemplo `Bully (Legendado PT-BR) (NTSC) [PS2].iso`, cujo link do dubsgamer
   responde 404): a célula da grade, a linha da fila enquanto baixa, e o cartão depois de salvo.
   Esperado: a mesma capa nas três. Antes desta task a do meio era um `↓`.

## Resultado

Entregue nos itens 1 a 3. A cobertura de capa 2D dos jogos é **96,99%** (5.906 de 6.089
títulos), acima do piso de 95% pedido. A contagem sobre TODAS as células é 94,30%, e a
diferença inteira são os 222 discos que não são jogos.

### O classificador jogo/não-jogo estava errado, e o número de cima é o corrigido

A primeira versão marcava um título como não-jogo quando **qualquer** variante dele casava
com a lista de termos. Como o manifesto tem uma linha por arquivo, bastava existir um
Taikenban japonês para o jogo inteiro sair da conta: "Ace Combat 04 - Shattered Skies" e
"Armored Core - Last Raven" estavam entre os 521. Passou a exigir que **todas** as variantes
casem, e saíram dois termos largos demais -- `(Unl)` sozinho, que homebrew e tradução também
carregam, e as edições promocionais de montadora (Gran Turismo "Nissan Micra Edition",
"Subaru Driving Simulator"), que são builds jogáveis do GT.

O efeito: 521 células viraram 222, e a cobertura de jogos caiu de 97,89% (inflada, porque
299 jogos com capa estavam sendo contados do outro lado) para **96,99%**, que continua acima
do piso. A separação segue sendo heurística: na lista dos 183 sem capa ainda há disco de
código (`Ultimate Codes for Use with...`, `X-Port`, `Xtreme FM`) que a lista de termos não
pega, ou seja, o número real de JOGOS é um pouco melhor que 96,99%, nunca pior.

A validação 4 depende do aparelho e segue pendente.
