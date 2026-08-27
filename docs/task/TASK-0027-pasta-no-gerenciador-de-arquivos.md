# TASK-0027: expor a pasta de dados no gerenciador de arquivos do sistema

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0027:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Item do inventário de migração levantado em 2026-08-27, comparando a árvore anterior inteira contra
o fork. `provider/Armsx2DocumentsProvider.java` (398 linhas) é genuinamente nosso — sem cabeçalho de
licença do ARMSX2, ao contrário de `DataDirectoryManager` e `LogcatRecorder`, que **são deles** e por
isso ficaram de fora.

## Por que existe

Desde o scoped storage, `getExternalFilesDir` não aparece na navegação normal do Android. É onde
vivem as ROMs baixadas, os memory cards e os savestates — alcançável só por caminho digitado. Com o
provider registrado, "RetroSystem PS2" vira uma origem no app Arquivos e no seletor de "Abrir com":
o usuário tira um save de lá ou põe uma ROM sem cabo e **sem permissão de todos os arquivos**.

## As duas mudanças de substância no porte

**A raiz.** A árvore anterior a resolvia com `DataDirectoryManager.getDataRoot()`. Aquele arquivo é
do upstream e representa o modelo de pastas **antigo**, que esta árvore já substituiu; usá-lo aqui
apontaria o provider para uma pasta que o core não usa. A raiz agora é
`MainActivityRuntime.assetCopyRoot()` — a mesma que o core lê e onde o catálogo grava.

**O interruptor.** O manifesto consome `@bool/armsx2_documents_provider_enabled` em
`android:enabled`, e `android:enabled` só aceita recurso. A árvore anterior o gerava com `resValue`
no Gradle; aqui isso falha (`defaultConfig contains custom resource values, but the feature is
disabled` — esta árvore desliga `buildFeatures.resValues`). Virou `res/values/bools.xml`, que serve
igual porque o valor é o mesmo em todas as variantes.

## Sobre `exported="true"`

`MANAGE_DOCUMENTS` é permissão de sistema: nenhum app de terceiros a tem. O `exported` abre o
provider para o seletor de arquivos do próprio Android, e para mais ninguém — o que a validação
abaixo demonstra.

## Como validar

No Galaxy A12:

```
$ adb shell dumpsys package providers | grep armsx2
  come.nanodata.armsx2/com.armsx2.provider.Armsx2DocumentsProvider     ← registrado

$ adb shell content query --uri content://come.nanodata.armsx2.documents/root
  SecurityException: ... requires that you obtain access using ACTION_OPEN_DOCUMENT
```

A recusa é o resultado **correto**: o shell (uid 2000) não tem `MANAGE_DOCUMENTS`, então o acesso
tem de passar pelo seletor do sistema. Um provider que respondesse ali estaria aberto demais.

**Não validado:** abrir o app Arquivos e navegar pela origem. Precisa de interação humana com o
seletor, e o aparelho caiu do `adb` antes disso.

## Resultado

Entregue.
