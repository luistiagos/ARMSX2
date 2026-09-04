# Bug: duas chaves de tradução chegam cruas à tela — `catalog.versions.subtitle` e `home.saved.exploreCatalog`

- **Detectado em:** 2026-09-03 19:40 (moto g86 5G, Android 16 SDK 36, `github/release`, ao validar o
  agrupamento de versões do catálogo)
- **Origem:** `com/armsx2/ui/catalog/GameVersionsModal.kt` e `com/armsx2/ui/home/HomeScreen.kt`
- **Errors (serviço):** nenhum — não lança, não trava; imprime o identificador da chave no lugar do texto
- **Classe:** fail
- **Reincidência:** primeira vez
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0081](../../task/TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md)

## Sintoma

O painel de versões de um título do catálogo — o que a TASK-0047 criou para acabar com a mesma capa
repetida por região — mostra, sob o nome do jogo:

```
A Visual Mix - Ayumi Hamasaki Dome Tour 2001 A
catalog.versions.subtitle          <- isto
─────────────────────────────────────
  A Visual Mix … (Japan) (Disc 1) (Alt)      ISO
  A Visual Mix … (Japan) (Disc 1) (SLPM-65086)  ISO
  …
```

Capturado em tela no aparelho. O identificador interno da chave aparece para o usuário onde deveria
estar "5 versões disponíveis".

## Causa raiz

São **dois defeitos diferentes**, com o mesmo sintoma.

### 1. `catalog.versions.subtitle` não existe em lugar nenhum

[`GameVersionsModal.kt:82`](../../../platforms/android/app/src/main/java/com/armsx2/ui/catalog/GameVersionsModal.kt#L82):

```kotlin
str("catalog.versions.subtitle").replace("%1\$d", variants.size.toString())
```

A chave não está no `BASE_EN` de `I18n.kt` nem em **nenhum** dos 19 arquivos de
`assets/i18n/*.json`. `I18n.get` devolve a própria chave quando não encontra — que é o
comportamento certo para um fallback, e é o que se vê na tela.

### 2. `home.saved.exploreCatalog` é erro de digitação no ponto de chamada

[`HomeScreen.kt:1242`](../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeScreen.kt#L1242)
pede `home.saved.exploreCatalog`. A chave canônica, definida no `BASE_EN`, é
`home.saved.empty.exploreCatalog` — **falta o `.empty.`**. As duas linhas vizinhas do mesmo
`EmptyState` (`home.saved.empty.title`, `home.saved.empty.body`) usam o nome certo.

## Por que passou despercebido

Este é o ponto que vale registrar.

| idioma | tem `home.saved.exploreCatalog` (errada) | tem `home.saved.empty.exploreCatalog` (certa) |
|---|---|---|
| **pt-BR** | **sim** | não |
| os outros 18 | não | não |

Alguém, ao traduzir, encontrou a chave crua na tela e **adicionou ao `pt-BR.json` a chave com o nome
errado** em vez de corrigir o ponto de chamada. O sintoma sumiu no único idioma em que o aparelho de
desenvolvimento roda, e continuou existindo em inglês e nos outros 17.

É o modo de falha clássico de um fallback silencioso: ele não avisa, então a correção acontece no
lugar onde o sintoma foi visto, e não onde o defeito está.

## Alcance

Uma auditoria de todas as chaves usadas com literal (`str("…")` / `I18n.get("…")`) contra as
definidas encontrou **exatamente estas duas**, de 1.301 usos contra 1.684 chaves definidas. O resto
está consistente.

## Como reproduzir

```bash
adb shell am start -n come.nanodata.armsx2/com.armsx2.MainActivity
# tocar num cartão do catálogo que traga o selo "×N" (várias versões)
```

O painel abre com `catalog.versions.subtitle` sob o título.

Para o segundo: trocar o idioma do app para inglês e abrir a aba "Salvos" sem nenhum jogo — o botão
do estado vazio sai escrito `home.saved.exploreCatalog`.

## Próximos passos

Ver [TASK-0081](../../task/TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md). Em resumo:
acrescentar a chave que falta, corrigir o ponto de chamada (e não a tradução), renomear a chave
errada no `pt-BR.json`, e — o que impede a reincidência — **um teste que reprova qualquer chave
usada e não definida**, já que o fallback nunca vai reclamar sozinho.

## Corrigido e validado em aparelho — 2026-09-03 ([TASK-0081](../../task/TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md))

Galaxy A12 `SM-A127M`, Android 13 (SDK 33), `githubDebug`. Mesmo painel, mesmo caminho:

```
007 - Everything or Nothing
5 versões disponíveis                     <- era `catalog.versions.subtitle`
─────────────────────────────────────
  007 - Everything or Nothing (USA)                    CHD · Baixado
  007 - Everything or Nothing (Europe) (En,Es,It,Nl,Sv)  ISO
  007 - Everything or Nothing (Europe) (Fr,De)           ISO
  007 - Everything or Nothing (Japan)                    ISO
  007 - Everything or Nothing (Korea)                    ISO
```

As três correções:

1. `catalog.versions.subtitle` acrescentada ao `BASE_EN` (`"%1$d versions available"`) e ao
   `pt-BR.json` (`"%1$d versões disponíveis"`).
2. O **ponto de chamada** de `home.saved.exploreCatalog` passou a pedir
   `home.saved.empty.exploreCatalog`, que é a chave canônica. A tradução não foi tocada para
   acomodar o erro — foi o erro que saiu.
3. A chave de nome errado saiu do `pt-BR.json`, para não ficar lixo que ninguém sabe de onde veio.

### O que impede a reincidência

`I18nKeysTest` lê `src/main/java` atrás de `str("…")` e `I18n.get("…")` com literal e reprova
qualquer chave não definida. Ele **falha** contra o erro de digitação — verificado reintroduzindo-o
de propósito — e a mensagem de falha diz o que fazer:

> Corrija o PONTO DE CHAMADA se for erro de digitacao; acrescente a chave ao BASE_EN se ela
> realmente faltar. Nao adicione a chave errada a um JSON de traducao — isso esconde o defeito no
> idioma que voce testa.

Essa última frase é a lição deste relatório escrita onde ela vai ser lida: dentro da falha do teste,
por quem estiver prestes a repetir o erro.
