# TASK-0019: trazer o mecanismo de atualização pelo nosso canal para o fork

- **Status:** em andamento
- **Criada em:** 2026-08-26
- **Concluída em:** —
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0019:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

Trazer o `AppUpdateManager` — o mecanismo que lê o nosso `version.json`, baixa com resume,
**verifica o SHA-256** e instala pelo `PackageInstaller` — para o fork, com o build e o manifesto
ligados. Etapa 5 do [plano do fork](../plano-fork-sobre-upstream.md).

> **Esta task entrega o MECANISMO, não a ligação com a UI.** O motivo está na seção
> "O que falta, e por que não foi decidido aqui". Isso é deliberado e segue o mesmo padrão da
> TASK-0002, que trouxe o banco de drivers "desligado, só logando", até haver base para ligá-lo.

## Por que este é o item que bloqueia qualquer publicação

Um usuário instalado que atualize para um build sem este mecanismo **nunca mais recebe
atualização**. Não há sintoma, não há erro: o app simplesmente para de se atualizar, e a recuperação
é o usuário reinstalar à mão a partir de um link que ele não tem.

## O que o upstream tem, e por que não serve como está

O flavor `github` deles tem um updater completo (`UpdaterEntry.kt`, 478 linhas), com UI em Compose,
diálogo, barra de progresso e strings traduzidas. Mas ele busca a **API de releases do GitHub deles**
e escolhe o APK por marcadores de nome (`-sdk26`, `-sdk30`, `-sdk33`, `-sdk35`).

E há uma diferença que não é de endereço: **o deles não verifica hash nenhum**. O nosso verifica o
SHA-256 declarado no `version.json`, e isso não é zelo abstrato — o `CLAUDE.md` da linha anterior
registra que a URL canônica do APK serve os bytes da versão *anterior* por um tempo depois do upload,
ignorando `Cache-Control`. Sem a verificação, o updater instala o APK errado sem perceber.

## Escopo

**Entra:**
- `com.armsx2.updates.{AppUpdateManager,UpdateInstallReceiver}` — 475 linhas, em
  `src/github/java/`. **No flavor github, não em `main`**, seguindo o desenho deles: um app que se
  auto-atualiza é violação de política do Play, e o build do play não pode nem conter o código.
- `buildConfigField` `APP_UPDATE_ENDPOINT` e `APP_UPDATE_CHANNEL` nos dois flavors — valor real no
  github, vazio no play. Sobrescrevíveis por `-Parmsx2.updateEndpoint` / `-Parmsx2.updateChannel`
  para testar sem mexer no arquivo que os clientes leem.
- Registro do `UpdateInstallReceiver` no manifesto do flavor github.

**Adaptações necessárias, e o que cada uma evita:**

| Mudança | Por quê |
|---|---|
| `getCacheDir()` → `getExternalCacheDir()` | é o **único** caminho que o FileProvider deles publica (`update_paths.xml` → `<external-cache-path>`). Com o cache interno, o `getUriForFile` do fallback lançaria `IllegalArgumentException("Failed to find configured root")` — e só no fallback, ou seja, só depois de o `PackageInstaller` já ter falhado. |
| autoridade `.fileprovider` → `.updateprovider` | é a que o manifesto do flavor github declara. Reusar a deles evita declarar um segundo provider e reduz a superfície de merge. |

**NÃO entra:**
- Trocar as duas funções (`checkForUpdate`, `downloadAndInstall`) que a UI Compose deles chama.
  Ver abaixo.
- Remover o updater do GitHub deles. Enquanto o seam não for trocado, ele continua sendo o que roda.

## O que falta, e por que não foi decidido aqui

O seam é limpo e já está identificado: a UI Compose deles chama exatamente duas funções privadas —
`checkForUpdate(includeNightly, ...): UpdateState` e `downloadAndInstall(context, info, onProgress)`
— e os tipos mapeiam quase 1:1 nas nossas `CheckCallback` / `InstallCallback`. Trocar as duas
preserva a UI inteira deles e a nossa verificação de hash.

**O que trava é uma decisão de produto, não a mecânica: o toggle "incluir builds nightly".**

A UI deles tem um switch persistido (`update.includeNightly`) e um texto que diz ao usuário que ele
está num build nightly, apoiados na convenção de que nightly usa `versionCode` = segundos Unix. O
nosso `version.json` tem **um canal só** (`default`), e o nosso `versionCode` é a série 38, 39…

São três saídas, e nenhuma é obviamente certa:

1. **Publicar um segundo canal** (`nightly`) no R2 e manter o toggle. Exige infraestrutura de
   publicação que hoje não existe.
2. **Remover o toggle** da UI deles. Honesto, mas mexe na Compose deles e aumenta a superfície de
   merge.
3. **Deixar o toggle inerte.** Rejeitada: um controle que não faz nada é exatamente o defeito que
   este projeto já catalogou (`isNativeInitializationSucceeded` sem consumidor, o toggle de log
   morto do `RecordAndroidLog`).

## Como validar

1. O APK de release compila com as classes novas presentes e o receiver registrado.
2. Depois do seam trocado: o app detecta uma versão nova no `version.json` real, baixa, **rejeita um
   SHA-256 que não bate**, e instala quando bate.

A validação 2 depende do seam e de aparelho.

## Resultado

O código está na árvore, o build passa, o manifesto empacotado está correto — e **o mecanismo não
está no APK**. Isso não é uma ressalva de rodapé; é o resultado.

### O R8 remove o que ninguém referencia, e isso quase virou um registro falso

Verificado no DEX do APK de release:

| Símbolo | No DEX? | Por quê |
|---|---|---|
| `UpdateInstallReceiver` | **presente** | citado no manifesto, e o R8 mantém o que o manifesto cita |
| `AppUpdateManager` | **AUSENTE** | nenhum código o referencia (o seam não foi trocado), então o R8 o removeu inteiro |
| `versions.digitalstoregames.com/.../version.json` | **AUSENTE** | era constante dentro da classe removida |

O app antigo tinha `minifyEnabled false`, então lá "compilou" e "está no APK" eram a mesma coisa.
**Aqui não são.** Um commit dizendo "mecanismo entregue" com o build verde seria verdadeiro sobre a
árvore e falso sobre o binário — exatamente a classe de mentira que este processo existe para
impedir, e ela só apareceu porque a verificação foi feita no DEX e não no log do Gradle.

Fica registrado como regra: **nesta árvore, verificar no APK. Build verde não prova entrega.**

### O que isso implica para a etapa

O `buildConfigField`, o registro do receiver e as duas adaptações (externalCacheDir e a autoridade
`.updateprovider`) são reais e ficam. O `AppUpdateManager` só passa a existir no binário quando
alguém o chamar — ou seja, quando o seam da UI for trocado, que é o passo travado pela decisão do
canal nightly descrita acima.

**Enquanto isso, o updater que roda no app é o do GitHub deles**, apontando para o repositório
deles. Nenhum build desta branch pode ser publicado nesse estado.
