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
