# TASK-0081: nenhuma chave de tradução chega crua à tela, e um teste passa a garantir isso

- **Status:** em andamento
- **Criada em:** 2026-09-03
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [chave-de-traducao-crua-na-tela](../bugs/open/armsx2-fork/chave-de-traducao-crua-na-tela_2026-09-03T19-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0081:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Duas chaves aparecem cruas na interface. Corrigir as duas, e deixar um teste que reprova a terceira
— porque `I18n.get` devolve a própria chave quando não encontra, e um fallback silencioso não avisa
ninguém.

## Escopo

**Entra:**

- **`catalog.versions.subtitle` acrescentada ao `BASE_EN`** e ao `pt-BR.json`. O ponto de chamada faz
  `.replace("%1\$d", …)`, então a string precisa conter `%1$d`.
- **O ponto de chamada de `home.saved.exploreCatalog` é corrigido**, não a tradução: passa a pedir
  `home.saved.empty.exploreCatalog`, que é a chave canônica já definida e que combina com as duas
  vizinhas do mesmo `EmptyState`. E a chave de nome errado sai do `pt-BR.json`, trocada pelo nome
  certo — senão fica lixo que ninguém sabe de onde veio.
- **Um teste de unidade** (`I18nKeysTest`) que varre `app/src/main/java` atrás de `str("…")` e
  `I18n.get("…")` com literal e **reprova** qualquer chave que não esteja definida. É o que impede a
  reincidência: sem ele, o próximo `str("chave.que.nao.existe")` volta a passar em silêncio.

**NÃO entra:**

- Traduzir a chave nova para os outros 17 idiomas. Eles caem no `BASE_EN`, que é o desenho do
  sistema — e um texto em inglês é melhor que um identificador em qualquer idioma.
- Fazer o `I18n.get` gritar (log/crash) em chave ausente. Mudaria comportamento de runtime para
  resolver o que um teste resolve na compilação.
- Auditar chaves montadas dinamicamente (`str("prefixo." + x)`). O teste só vê literais, e isso está
  dito nele.

## Como validar

```powershell
cd platforms/android
./gradlew.bat :app:testGithubReleaseUnitTest --tests '*I18nKeysTest*'
```

O teste deve **reprovar** antes da correção e passar depois.

Em aparelho: abrir um cartão do catálogo com o selo `×N` e ver "N versões disponíveis" no lugar de
`catalog.versions.subtitle`.

## Resultado

Preenchido ao concluir.
