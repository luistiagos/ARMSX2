# TASK-0081: nenhuma chave de tradução chega crua à tela, e um teste passa a garantir isso

- **Status:** concluída
- **Criada em:** 2026-09-03
- **Concluída em:** 2026-09-03
- **Feature:** nenhuma
- **Bugs que resolve:** [chave-de-traducao-crua-na-tela](../bugs/done/chave-de-traducao-crua-na-tela_2026-09-03T19-40.md)
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

As três correções entraram e o teste existe.

**Validado em aparelho** — Galaxy A12 `SM-A127M`, Android 13 (SDK 33), `githubDebug`: o painel de
versões agora abre com **"5 versões disponíveis"** onde saía `catalog.versions.subtitle`.

**O teste prova o defeito, não a implementação:** reintroduzindo o erro de digitação de
`home.saved.exploreCatalog`, `:app:testGithubDebugUnitTest --tests '*I18nKeysTest*'` **falha**.
Restaurada a correção, passa. A auditoria completa é 1.301 usos com literal contra 1.684 chaves
definidas, e exatamente as duas do relatório faltavam.

**Uma decisão que merece registro:** a mensagem de falha do teste diz *"não adicione a chave errada
a um JSON de tradução — isso esconde o defeito no idioma que você testa"*. É a lição deste bug
escrita no único lugar onde ela será lida: na frente de quem está prestes a repeti-lo.

**Nota sobre o nome da task da validação:** a task foi verificada no `githubDebug` porque a release
não instala neste aparelho (assinatura de debug), e uma desinstalação levaria 14 GB de ROMs junto.
