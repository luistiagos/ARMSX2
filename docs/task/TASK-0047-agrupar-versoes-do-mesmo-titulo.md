# TASK-0047: agrupar versões do mesmo título e escolher a versão num painel

- **Status:** concluída
- **Criada em:** 2026-08-28
- **Concluída em:** 2026-09-03
- **Feature:** nenhuma
- **Bugs que resolve:** [biblioteca-mesmo-titulo-repetido-uma-vez-por-regiao](../bugs/done/biblioteca-mesmo-titulo-repetido-uma-vez-por-regiao_2026-08-28T11-30.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0047:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

A biblioteca é uma grade só e carrega o manifesto inteiro. O manifesto tem **uma linha por
arquivo**, não por jogo, então o mesmo título ocupa uma célula por região/idioma/revisão/disco.

Isto já está documentado nesta árvore, em `GameInfo.GridLabels`:

> *"A grade aqui carrega o catalogo inteiro, e o repositorio de capas tem UMA arte por jogo, nao por
> lancamento: '007 - Nightfire' tem cinco entradas (USA, duas europeias, Japan, Korea) com a mesma
> imagem. Sem o rotulo, a grade parece cheia de cartoes repetidos — **foi exatamente o que um
> usuario reportou**."*

A resposta na época foi ligar o rótulo sob a capa por padrão. Isso torna as células
*distinguíveis*, mas não resolve o que foi reportado: continuam sendo cinco células com a mesma
arte para um jogo só, e o usuário reportou de novo.

Medido no `catalog_manifest_ps2.txt` desta árvore (12.628 linhas):

| | |
|---|---|
| entradas | 12.628 |
| títulos distintos | 6.569 |
| títulos com mais de uma versão | 2.698 |
| células que somem da grade | **6.059 (48%)** |

Piores casos: `Metal Gear Solid 3 - Subsistence` (25 arquivos), `SingStar '80s` (15),
`Hitman 2 - Silent Assassin` (14).

## Objetivo

Uma célula por título entre as linhas **de catálogo**. Tocar num título com mais de uma versão
abre um painel listando os arquivos — nome completo, formato, estado — e escolher um cai no painel
de download que já existe, que é quem pergunta antes de gastar 1–10 GB.

## Escopo

**Entra:**

- `catalog/CatalogParser.java` — `baseTitle(String)`: nome sem extensão e sem os grupos entre
  parênteses/colchetes do fim. Os 639 grupos distintos do manifesto são **todos** metadado —
  região, idioma, `(v1.03)`, `(Disc 1)`, `(Shokai Genteiban)` —, nenhum é parte do nome de um jogo,
  então a regra é posicional e não uma lista de sufixos conhecidos que envelheceria.
- `catalog/CatalogLibrary.kt` — índice `baseTitle -> versões`, e `variantsFor(key)`.
- `GameInfo.kt` — `catalogGroupKey` e `catalogVariantCount` na linha da grade.
- `ui/home/HomeViewModel.kt` — `mergeCatalog` colapsa as linhas **de catálogo** por título;
  `launch` desvia para o painel de versões quando a linha representa mais de uma; a busca casa
  também nos nomes das versões.
- `ui/catalog/GameVersionsModal.kt` (novo) — `PadModal`, como o `CatalogDownloadModal`.
- `ui/home/HomeScreen.kt` — hospeda o painel e desenha o selo de contagem sobre a capa.
- `i18n/I18n.kt` (inglês, fonte da verdade) e `assets/i18n/pt-BR.json`.

**NÃO entra:**

- **Agrupar o que já está no aparelho.** Um jogo baixado é um arquivo concreto, e `mergeCatalog`
  o emite como linha própria antes de qualquer agrupamento. Se o usuário baixou a versão USA e a
  japonesa, são duas células — juntá-las esconderia qual delas dá boot. Só as linhas
  `needsDownload` são colapsadas.
- **Mexer no manifesto.** Nenhuma linha acrescentada, removida ou reordenada.
- **Nomes URL-encoded.** 21 entradas têm `%20`/`%28` no nome; decodificar melhoraria o título
  exibido, mas só uma delas passaria a agrupar, e o nome do arquivo é o que resolve a URL de
  download. Fica para uma limpeza do manifesto.
- **Reabrir o padrão do `GridLabels`.** O rótulo continua ligado; ele agora identifica um jogo em
  vez de desempatar cinco cópias, que é o papel que sempre devia ter tido.

## Como validar

```powershell
cd platforms\android
.\gradlew.bat assembleDebug
```

No aparelho:

1. A biblioteca mostra **um** "007 - Nightfire", com selo de contagem, não cinco.
2. Tocar nele abre o painel com as 5 versões (USA/CHD, duas europeias, Japan, Korea), cada uma com
   nome completo e formato.
3. Escolher uma cai no painel de download **de confirmação** já existente — não começa a baixar
   sozinho.
4. Um título de versão única continua abrindo o painel de download direto, sem passo extra.
5. Buscar `Korea` continua achando os jogos que têm versão coreana (a busca casa nos nomes das
   versões, não só no título do grupo).
6. Um jogo já baixado continua com célula própria e dá boot num toque.

## Resultado

Implementado e **validado em aparelho** em 2026-09-03 — moto g86 5G, Android 16 (SDK 36),
`github/release`, catálogo com 6313 títulos:

- uma célula por título, com o número de versões no selo (`3×`, `5×`, `2×`, `4×` observados na
  primeira tela da grade);
- o painel de versões abre no toque, listando cada variante com região/serial e formato;
- o `heightIn(max = 340.dp)` segura o painel dentro da tela, que era o motivo de ele existir.

**Um defeito apareceu nesta validação:** o subtítulo do painel saía como `catalog.versions.subtitle`
— a chave de tradução crua, porque ela nunca foi definida. Corrigido pela
[TASK-0081](TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md), que também deixou um teste
para a próxima chave ausente.

**Nota de rastreabilidade, e ela dói:** o código desta task entrou no commit `bf45520833`, assunto
`*`, 114 arquivos. Não existe nenhum commit com assunto `TASK-0047:`, e é por isso que esta task
ficou `em andamento` com o trabalho pronto — o índice não tinha como saber. É o buraco que a
[TASK-0011](TASK-0011-impor-regra-de-commit-mecanicamente.md) fechou no mesmo dia: daqui em diante
um commit assim reprova. O hash ficou em `LEGACY_SUBJECT_EXCEPTIONS` porque já estava publicado.
