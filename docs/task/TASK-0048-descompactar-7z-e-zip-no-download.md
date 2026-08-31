# TASK-0048: descompactar `.7z` e `.zip` depois do download

- **Status:** em andamento
- **Onde parou:** código pronto e verificado (testes + R8); o commit espera a TASK-0047
  fechar, porque ela deixou a árvore sem compilar — ver a nota no fim
- **Criada em:** 2026-08-28
- **Concluída em:** —
- **Feature:** nenhuma
- **Backlog de migração:** [MIG-0005](../backlog/migracao/MIG-0005-descompactacao-roms-7z-zip.md)
- **Documento de referência:** [TASK-0045](TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md)
- **Bugs que resolve:** — (amplia cobertura; não corrige defeito relatado)
- **Commit:** — (o vínculo é o prefixo `TASK-0048:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

A [TASK-0045](TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md) fechou o download em
`chd, iso, cso, zso, gz, bin, img, mdf, nrg, dump` — a lista exata que o `GetFileReader` de
[`pcsx2/CDVD/InputIsoFile.cpp:40`](../../pcsx2/CDVD/InputIsoFile.cpp) sabe abrir — e passou a
**recusar** `.7z` e `.zip`. Era a decisão certa naquele momento: o app não descompactava nada, e
aceitar um link comprimido significava gastar 2,3 GB da franquia do usuário para produzir um
arquivo que fica no disco sem rodar.

O preço é que centenas de títulos do manifesto de 12.628 linhas **só existem comprimidos na web**
(5 de 10 na amostra do bug original). Para eles, o resultado hoje é a falha imediata que a própria
TASK-0045 descreve como esperada.

Esta task paga o preço na outra ponta: aceita o comprimido, e descompacta.

## Objetivo

Um título que só existe em `.7z` ou `.zip` passa a ser baixável e jogável, sem que o usuário
precise saber que houve um passo de extração — e sem que o formato comprimido volte a ser
preferido quando existe um formato direto do mesmo jogo.

## Escopo

**Entra:**

- `gradle/libs.versions.toml`, `app/build.gradle.kts` — `org.apache.commons:commons-compress:1.28.0`
  e `org.tukaani:xz:1.12`.
  - O `xz` é **obrigatório na prática**: sem ele o commons-compress lê o container 7z e falha no
    codec, porque LZMA/LZMA2 é o que praticamente todo `.7z` usa. É `optional` no POM do
    commons-compress, então **não vem sozinho** — precisa ser declarado.
  - `commons-compress` arrasta `commons-io`, `commons-codec` e `commons-lang3` (dependências
    normais, não opcionais, do POM 1.28.0). Verificado: o jar é bytecode Java 8 (major 52) e as
    APIs `java.nio.file` que ele usa existem a partir da API 26, que é o nosso `minSdk`.
  - `packaging.resources` exclui `META-INF/versions/9/module-info.class` — o jar é multi-release e
    esse arquivo não tem o que fazer num APK.
- `app/proguard-rules.pro` — `-dontwarn` para os codecs **opcionais** que não trazemos (zstd-jni,
  brotli, asm). Sem isso o R8 do release aborta em referência não resolvida a classe que nunca vai
  ser chamada. É release-only: a falha não aparece em debug.
- `catalog/RomArchiveExtractor.java` (**novo**) — abre o `.7z`/`.zip`, escolhe **a maior entrada de
  dentro cuja extensão o CDVD abre**, extrai para o `.part` da entrada e renomeia para
  `<nome do manifesto sem extensão>.<extensão de dentro>`. Apaga o comprimido no fim.
  - Grava no `.part` e não direto no nome final de propósito: um processo morto no meio da extração
    deixaria um `.iso` truncado, e `markDownloaded` casa **por nome sem extensão** — a biblioteca
    passaria a mostrar como baixado um arquivo pela metade. O `.part` já é ignorado por ela e já é
    apagado por `DownloadQueueManager.remove`.
  - Renomeia para o nome do manifesto em vez de manter o nome de dentro do arquivo pelo mesmo
    motivo: `markDownloaded` casa pelo nome sem extensão, e o nome interno costuma divergir
    (`10.000 Bullets (Europe) (En,Fr,De,Es,It).iso` no manifesto, `10,000 Bullets (Europe).iso`
    dentro do 7z).
- `catalog/RomDownloadManager.java`
  - `isArchive` + `ARCHIVE_EXTENSIONS = { 7z, zip }`, e `localFileName` passa a gravar também com
    a extensão de um comprimido.
  - **O comprimido é último recurso, nunca preferência.** A cascata de resolução continua
    devolvendo o primeiro link de formato direto que encontrar, em qualquer um dos cinco passos; o
    primeiro comprimido visto fica guardado e só é usado se a cascata inteira não achou formato
    direto nenhum. Sem isso, um `.7z` do passo 2 ganharia do `.chd` do passo 3 — exatamente o
    defeito que a TASK-0045 corrigiu ao tirar `.7z` da frente de `VARIANT_EXTENSIONS`.
  - O comprimido também ganha do palpite do HuggingFace, que é URL construída e 404 na maioria
    destas entradas (é o passo que a validação 3 da TASK-0045 mediu).
  - `VARIANT_EXTENSIONS` ganha `.7z` e `.zip` **no fim**: duas consultas a mais, e só no caso em
    que nada direto foi encontrado.
  - `DownloadCallback.onExtracting(bytesExtraídos, total)`.
  - **Espaço em disco, duas vezes.** Antes de escrever o primeiro byte, com o `Content-Length`:
    para um comprimido exige o dobro (arquivo + extraído, com razão de compressão 1 como piso).
    Antes de extrair, com o tamanho descomprimido que o cabeçalho do arquivo declara — aí é exato.
    A checagem é feita fora do `catch (IOException)` do laço de download: dentro dele viraria três
    tentativas e dois `sleep` para um erro que não muda de resposta.
  - **Falha na extração apaga o comprimido.** Deixá-lo no disco faz `markDownloaded` marcar a
    entrada como baixada — e o emulador não abre aquilo. É o defeito da TASK-0045 de volta, por
    outra porta.
- `catalog/DownloadQueueManager.java` — estado `EXTRACTING`, tratamento de `onExtracting`, e
  `pause()` que não pausa durante a extração (não há como pausar um `InputStream` de LZMA no meio
  sem guardar o estado do decodificador).
- `catalog/DownloadForegroundService.java` — a notificação diz "Extracting…" em vez de repetir
  "Download queued…" durante os minutos da extração.
- `ui/catalog/DownloadQueueSection.kt` — linha de status e barra para `EXTRACTING`; sem botão de
  pausar nesse estado, só cancelar.
- `ui/catalog/CatalogDownloadModal.kt` — em `EXTRACTING` o modal oferece cancelar, e não "Download"
  (o `else` atual ofereceria baixar de novo).
- `ui/home/HomeScreen.kt` — a tarja da capa mostra `⚙` durante a extração, e não o `↓` de
  "não baixado" que o `else` daria.
- `i18n/I18n.kt` — `catalog.queue.extracting`, `catalog.queue.extracting.starting`,
  `catalog.queue.notification.extracting`. Só em inglês, como as demais chaves de catálogo.
- `app/src/test/java/com/armsx2/catalog/RomArchiveExtractorTest.kt` (**novo**) — zip e 7z reais,
  montados no teste.

**NÃO entra:**

- **`.rar`.** O commons-compress **não descompacta** RAR (só detecta o formato); seria mais uma
  dependência (junrar) e outro conjunto de casos. Fica fora, e `isArchive` não o aceita.
- **Arquivo com senha.** `SevenZFile` aceita senha; não há de onde tirá-la.
- **Arquivos multi-parte** (`.7z.001`, `.z01`). Cada parte é uma URL, e a resolução devolve uma.
- **Conjuntos `.bin` + `.cue`.** Só a maior entrada jogável é extraída, e ela é **renomeada** — um
  `.cue` extraído junto apontaria para o nome antigo do `.bin` e teria de ser reescrito. Para PS2 o
  `.bin` de faixa única abre direto no `FlatFileReader`, então o caso comum funciona sem o `.cue`.
- **Pausar/retomar a extração.** Cancelar funciona; pausar não aparece.
- **Reaproveitar um comprimido já baixado numa nova tentativa.** A falha apaga o arquivo, então
  retentar rebaixa. Trocar isso exige um estado "baixado, falta extrair" que hoje não existe.
- **`markDownloaded` ignorar comprimidos soltos na pasta.** Este fluxo nunca deixa um: apaga no
  sucesso e na falha. Um `.7z` que o usuário colocou à mão continua contando como baixado — é o
  comportamento de hoje, e mudá-lo é decisão sobre dado do usuário.
- `ui/catalog/GameVersionsModal.kt` — arquivo da TASK-0047, ainda não commitado. A linha de versão
  fica sem rótulo durante a extração (`else -> null`); acerto quando aquela task fechar.
- Qualquer edição em `pcsx2/` ou `common/`. O delta no core continua zero.

## Como validar

1. `./gradlew :app:testGithubDebugUnitTest` — os testes novos de extração passam.
2. `./gradlew :app:assembleGithubRelease` compila **e o R8 não aborta** (a parte que só o release
   exercita).
3. Aparelho: baixar `10.000 Bullets (Europe) (En,Fr,De,Es,It).iso` — a entrada que a TASK-0045
   registrou como "só existe em `.7z`". Esperado: o download acontece, a fila e a notificação
   passam por "Extracting…", e sobra em `files/roms/` **um** arquivo
   `10.000 Bullets (Europe) (En,Fr,De,Es,It).iso` (ou `.bin`), sem o `.7z` ao lado.
4. Aparelho: o jogo dá boot.
5. Aparelho: cancelar durante a extração não deixa nem o comprimido nem o `.part` para trás.
6. Aparelho: um título que existe em `.chd` **e** em `.7z` continua baixando o `.chd` — a não
   regressão da TASK-0045.

## Resultado

Código entregue e verificado na máquina, **sem commit** — ver a nota abaixo.

- `:app:testGithubDebugUnitTest --tests "com.armsx2.catalog.*"` → **13 casos, 0 falhas**:
  os 5 da TASK-0045 continuam passando e os 8 novos de
  `RomArchiveExtractorTest` passam. Os arquivos comprimidos dos testes são **reais**, montados no
  próprio teste — o caso do `.7z` usa LZMA2, então ele é também a prova de que a dependência
  `org.tukaani:xz` está de fato no classpath. Com ela faltando o teste falha aqui, e não no
  aparelho do usuário.
- `:app:minifyGithubReleaseWithR8` → **BUILD SUCCESSFUL**. Era o passo de risco: `-dontwarn` que
  faltasse aborta o R8, e isso não aparece em nenhum build de debug. No `mapping.txt` do release
  sobreviveram `SevenZFile`, `ZipFile`, `Coders`, `SevenZMethod` e `LZMA2InputStream` — o caminho
  do 7z está inteiro depois da minificação.

As validações 3 a 6 **dependem do aparelho** e seguem pendentes: exigem instalar o build e refazer
um download real de um título que só existe comprimido.

### Duas notas sobre o ambiente e a árvore

1. **Sem commit, e a árvore volta a compilar.** Quando esta task começou, a árvore **não
   compilava**: a TASK-0047 já tinha o `HomeScreen.kt:1577` chamando `VersionCountBadge`, um
   composable que não existia em lugar nenhum. Ele foi escrito — é trabalho da TASK-0047, que já o
   listava no escopo ("desenha o selo de contagem sobre a capa") —, e a verificação acima é do
   código real, sem stub nenhum na árvore. O commit desta task ainda espera a TASK-0047 fechar,
   porque as duas dividem `HomeScreen.kt`. Os arquivos **desta** task são:
   `RomArchiveExtractor.java`, `RomArchiveExtractorTest.kt`, `RomDownloadManager.java`,
   `DownloadQueueManager.java`, `DownloadForegroundService.java`, `DownloadQueueSection.kt`,
   `CatalogDownloadModal.kt`, `I18n.kt`, `build.gradle.kts`, `libs.versions.toml`,
   `proguard-rules.pro` e **três linhas** de `HomeScreen.kt` (o ramo `EXTRACTING` da tarja) — o
   `VersionCountBadge` não é uma delas.
2. **O Gradle desta máquina escolhe o JDK errado sozinho.** O `~/.gradle/gradle.properties` aponta
   `org.gradle.java.home=D:/DevCaches/jdk-17`, e como `gradle/gradle-daemon-jvm.properties` exige
   Java 21 o Gradle ignora aquilo e auto-detecta — caindo num **JRE** de extensão do editor
   (`.antigravity-ide/extensions/redhat.java-.../jre/21.0.11`), que não tem `jlink`. O
   `compileGithubDebugJavaWithJavac` falha com *"jlink executable ... does not exist"*, o que não
   tem nada a ver com o código. Contorno usado:

   ```
   ./gradlew.bat --stop
   ./gradlew.bat <tarefa> \
     -Dorg.gradle.java.installations.auto-detect=false \
     "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
   ```

   O `--stop` não é opcional: um daemon já vivo é reaproveitado com o JVM errado, e a
   segunda tentativa falha idêntica à primeira.
