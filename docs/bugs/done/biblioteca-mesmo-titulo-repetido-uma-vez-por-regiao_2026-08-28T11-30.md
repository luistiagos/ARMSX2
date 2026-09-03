# Bug: a biblioteca mostra o mesmo jogo uma vez por região, com a mesma capa

- **Detectado em:** 2026-08-28 11:30 (relato do usuário, pela **segunda** vez)
- **Origem:** `ui/home/HomeViewModel.kt` — `mergeCatalog` (linha 383) transforma cada entrada do
  catálogo numa célula da grade, e o manifesto tem **uma linha por arquivo**, não por jogo
- **Errors (serviço):** nenhum — não é crash, não gera telemetria. Chega como reclamação
- **Classe:** fail (usabilidade)
- **Reincidência:** segunda vez. A primeira foi respondida ligando o rótulo sob a capa por padrão
  (`GameInfo.GridLabels`), o que tornou as células distinguíveis sem reduzir a repetição
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0047](../../task/TASK-0047-agrupar-versoes-do-mesmo-titulo.md)

> **Registro escrito depois da task.** A TASK-0047 já existia e apontava para este arquivo, que
> nunca tinha sido criado — o validador de rastreabilidade reprovava por isso. O conteúdo abaixo
> foi reconferido contra a árvore, e onde a minha contagem diverge da que está na task eu digo
> qual é qual.

## Sintoma

A grade da biblioteca parece cheia de cartões repetidos: o mesmo jogo aparece uma vez por
região/idioma/revisão/disco, todas com **a mesma arte**, porque o repositório de capas tem uma
imagem por jogo e não por lançamento.

Isto já estava escrito na própria árvore, no comentário de `GridLabels`
([GameInfo.kt:101](../../../platforms/android/app/src/main/java/com/armsx2/GameInfo.kt#L101)):

> *"A grade aqui carrega o catalogo inteiro, e o repositorio de capas tem UMA arte por jogo, nao
> por lancamento: '007 - Nightfire' tem cinco entradas (USA, duas europeias, Japan, Korea) com a
> mesma imagem. Sem o rotulo, a grade parece cheia de cartoes repetidos — **foi exatamente o que
> um usuario reportou**."*

A resposta de então foi ligar o rótulo sob a capa por padrão. Isso torna as células
*distinguíveis* — dá para ler qual é qual —, mas não muda o que incomoda: continuam sendo cinco
células com a mesma arte para um jogo só. O usuário reportou de novo.

## Causa raiz

Não há defeito de código a apontar: `mergeCatalog` faz exatamente o que foi escrito para fazer, e
o manifesto é uma linha por arquivo por construção. O defeito é de **modelo** — a grade adotou a
granularidade do manifesto (arquivo) em vez da granularidade que o usuário tem na cabeça (jogo).

## Prova, recontada do manifesto desta árvore

`platforms/android/app/src/main/assets/catalog_manifest_ps2.txt`, 12.628 linhas (idêntico em
tamanho à cópia da raiz). Agrupando por nome sem extensão e sem os grupos entre
parênteses/colchetes do fim:

| | recontagem | o que a TASK-0047 registrou |
|---|---|---|
| entradas | 12.628 | 12.628 |
| títulos distintos | 6.575 | 6.569 |
| títulos com mais de uma versão | 2.695 | 2.698 |
| células que sairiam da grade | **6.053 (47%)** | 6.059 (48%) |

A diferença de meia dúzia de títulos é da regra: usei uma aproximação posicional para conferir, e a
task usa o `baseTitle` que ela mesma implementa. A ordem de grandeza é a mesma e a conclusão não
muda — **quase metade da grade é repetição**.

Piores casos, idênticos nas duas contagens:

| título | arquivos |
|---|---|
| `Metal Gear Solid 3 - Subsistence` | 25 |
| `SingStar '80s` | 15 |
| `Hitman 2 - Silent Assassin` | 14 |
| `Devil May Cry 2` | 13 |

## Como reproduzir

Abrir a biblioteca com o catálogo carregado e buscar por `Metal Gear Solid 3` ou `Hitman 2`: 25 e
14 células, mesma capa, diferindo só pelo rótulo sob a arte.

## Próximos passos

- [TASK-0047](../../task/TASK-0047-agrupar-versoes-do-mesmo-titulo.md), em andamento: uma célula
  por título entre as linhas de catálogo, e um painel de versões no toque.
- Só as linhas **de catálogo** são agrupadas. Um jogo já no aparelho é um arquivo concreto e
  precisa de célula própria — senão a grade esconde qual deles dá boot.

## Validado em aparelho — 2026-09-03

moto g86 5G, Android 16 (SDK 36), `github/release`, catálogo com **6313 títulos**.

**Uma célula por título, com o número de versões no selo.** A grade mostra `0 Story` `3×`,
`007 - Agent Under Fire` `3×`, `007 - Everything or Nothing` `5×`, `007 - From Russia with Love`
`2×`, `007 - Quantum of Solace` `4×`. O relato descrevia 25 células idênticas para *Metal Gear Solid
3* e 14 para *Hitman 2*, diferindo só pelo rótulo — isso acabou.

**O painel de versões abre no toque**, como a TASK-0047 desenhou. Capturado abrindo um título com
várias versões:

```
A Visual Mix - Ayumi Hamasaki Dome Tour 2001 A
  A Visual Mix … (Japan) (Disc 1) (Alt)           ISO
  A Visual Mix … (Japan) (Disc 1) (SLPM-65086)    ISO
  A Visual Mix … (Japan) (Disc 1) (SLPS-25071)    ISO
  A Visual Mix … (Japan) (Disc 1)                 ISO
  A Visual Mix … (Japan) (Disc 2) (Alt)           …
```

Cada versão mantém o código de região/serial e o formato, que é o que o relato pedia para o usuário
poder escolher — e o `heightIn` segura o painel dentro da tela.

**Um defeito foi encontrado nesta mesma validação**, e tem relatório próprio: o subtítulo do painel
saía escrito `catalog.versions.subtitle`, a chave de tradução crua. Corrigido pela
[TASK-0081](../../task/TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md); ver
[chave-de-traducao-crua-na-tela](../open/armsx2-fork/chave-de-traducao-crua-na-tela_2026-09-03T19-40.md).
Não invalida este fechamento: o agrupamento — que é o que este relatório pede — funciona.

> **Nota de rastreabilidade.** O código deste agrupamento entrou no commit `bf45520833`, cujo
> assunto é `*` e que carrega 114 arquivos. Por isso a TASK-0047 aparecia `em andamento` sem commit
> nenhum ligado a ela: não há prefixo `TASK-0047:` em assunto de commit algum. É exatamente o buraco
> que a [TASK-0011](../../task/TASK-0011-impor-regra-de-commit-mecanicamente.md) fechou hoje — um
> commit assim passa a **reprovar** na checagem `--commits`. Aquele hash ficou registrado
> nominalmente em `LEGACY_SUBJECT_EXCEPTIONS`, porque já estava publicado.
