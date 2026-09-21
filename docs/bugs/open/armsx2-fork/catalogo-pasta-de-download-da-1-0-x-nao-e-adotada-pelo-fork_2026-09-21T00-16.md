# Bug: a pasta de download escolhida na 1.0.x não é adotada — ao instalar o fork por cima, os jogos baixados somem da biblioteca

- **Detectado em:** 2026-09-21 00:16 (achado ao investigar o relato de cliente de 2026-09-20:
  "os jogos que tinha baixado no app sumiram")
- **Origem:** **delta do fork** — `adoptLegacyDataRoot` é nosso; a
  [TASK-0030](../../../task/TASK-0030-adotar-pasta-de-dados-da-versao-anterior.md) cobriu uma das
  duas chaves de pasta da linha anterior e deixou a outra
- **Errors (serviço):** nenhum — não é crash; a biblioteca abre vazia e o catálogo oferece baixar de novo
- **Classe:** incompatibilidade fork ↔ version1; perda silenciosa (os arquivos continuam no disco)
- **Reincidência:** é a mesma classe da TASK-0030 — "as duas linhas guardam a mesma decisão em
  lugares que não se enxergam" — numa chave que aquela task não listou
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda
- **Relacionado:**
  [o catálogo só baixa para `Android/data` e o fork tirou a opção de pasta própria](catalogo-download-so-em-android-data-sem-opcao-de-pasta-propria_2026-09-21T00-16.md)
  — é a opção da 1.0.x cujo valor este relato deixa de adotar

> ⚠️ **O cliente do relato de 2026-09-20 NÃO caiu neste defeito.** Perguntado, ele respondeu que
> está no fork e que **nunca mexeu em "Diretório de Download"** na versão anterior — logo a pasta
> dele é a padrão, que é a mesma nas duas linhas. O defeito é real e atinge quem escolheu a pasta;
> a causa do relato dele continua em investigação (ver os relatos irmãos abertos na mesma data).

## Sintoma

Usuário da 1.0.x que escolheu uma pasta própria em **Configurações → Diretório de Download**
(por exemplo, o cartão SD, para caber os jogos de 4–8 GB) instala o APK do fork por cima. O app
abre, a biblioteca **não mostra nenhum dos jogos baixados**, e no catálogo cada um deles volta a
aparecer como "baixar". Nada foi apagado: os arquivos estão na pasta que ele escolheu.

## Evidência — o que foi observado no código, elo a elo

### A linha anterior tinha DUAS pastas, e a biblioteca era a segunda

`DataDirectoryManager.java` de `feature/handoff-end-to-end` guarda duas escolhas independentes no
arquivo de SharedPreferences `armsx2`:

| chave | o que é | quem grava |
|---|---|---|
| `data_dir_path` (`:46`) | raiz de dados (memcards, ini, saves) | onboarding e Configurações |
| `download_dir_path` (`:49`) | **pasta onde os ROMs baixados são gravados** | Configurações → Diretório de Download |

`getDownloadDir` (`:126-138`) dá **prioridade à segunda**, e só cai em `<raiz>/roms` quando ela
não existe:

```java
String custom = prefs.getString(KEY_DOWNLOAD_PATH, null);
if (!TextUtils.isEmpty(custom)) { ... return dir; }
File defaultDir = new File(getDataRoot(context), "roms");
```

A opção era exposta ao usuário, em português (`values-pt-rBR/strings.xml:265-269`): *"Diretório de
Download — Pasta onde os ROMs baixados são salvos. Padrão: roms/ dentro da pasta de dados"*, com os
botões "Escolher pasta de download" e "Restaurar padrão" (`SettingsActivity.java:2573-2617`).

E **a biblioteca da 1.0.x era literalmente essa pasta**: `HomeActivity.java:106` faz
`romsDir = DataDirectoryManager.getDownloadDir(this)`, `:111` entrega a mesma pasta à fila de
download e `:195`/`:424` fazem `CatalogParser.markDownloaded(allEntries, romsDir)`. Não havia outra
fonte de "jogos salvos".

### O fork adota só a primeira chave

[`adoptLegacyDataRoot`](../../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L3250)
lê exatamente uma chave da versão anterior:

```kotlin
val LEGACY_PREFS = "armsx2"
val LEGACY_DATA_ROOT_KEY = "data_dir_path"
```

`grep -rn download_dir_path platforms/android/app/src/main/java/` devolve **zero** ocorrências.

### Onde o fork procura os jogos

- A biblioteca varre só
  [`romsDirs`](../../../../platforms/android/app/src/main/java/com/armsx2/data/library/GameLibraryRepository.kt#L76),
  semeada por
  [`seedOwnRomsFolder`](../../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L3270)
  com `assetCopyRoot/roms` — e `romsDirs` nasce vazia numa instalação por cima, porque o fork lê
  outro arquivo de prefs (`"ARMSX2"`,
  [`MainActivityRuntime.kt:2531`](../../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L2531)).
- O catálogo marca "baixado" olhando
  [`romsDir()`](../../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L481)
  = `assetCopyRoot/roms`, o mesmo caminho.

Logo, para quem tinha `download_dir_path`:

```
1.0.x   biblioteca = <download_dir_path>                      (os arquivos estão aqui)
fork    biblioteca = <assetCopyRoot>/roms                    (vazia)
```

Sem `download_dir_path`, os dois lados caem em `getExternalFilesDir(null)/roms` — a atualização
preserva os jogos. Foi esse o ramo que a TASK-0030 verificou no Galaxy A12.

## Segunda fragilidade no mesmo caminho: a sonda de escrita trava com arquivo órfão

[`validateSystemDirWritable`](../../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L379),
que decide se a pasta legada é adotada e se `assetCopyRoot` a usa, faz:

```kotlin
val probe = File(dir, ".armsx2-write-probe")
val ok = probe.createNewFile()
if (ok) probe.delete()
ok
```

`createNewFile()` devolve **false quando o arquivo já existe**. Um `.armsx2-write-probe` que ficou
para trás (processo morto entre o `createNewFile` e o `delete`) faz a função responder "não
gravável" **para sempre** naquela pasta — e aí `adoptLegacyDataRoot` recusa a pasta legada e
`assetCopyRoot` cai no padrão, que é justamente o cenário "biblioteca vazia, memory cards e
savestates sumidos" que o comentário da própria função diz existir para evitar. A linha anterior
sondava com `FileOutputStream` (`DataDirectoryManager.java:599-620`), que sobrescreve; é regressão.

Só atinge quem tem pasta customizada (`systemDir` nulo pula a sonda), mas o efeito é o mesmo deste
relato e a correção cabe na mesma task.

## Impacto

- Perda de acesso a todos os jogos baixados — dezenas de GB — sem aviso, sem erro, sem log.
- O catálogo oferece rebaixar cada um; quem aceita duplica o consumo de disco e de rede.
- Parece corrupção para o cliente, e é o pior tipo de defeito para o suporte diagnosticar.

## Correção proposta

Na mesma janela do `onCreate` em que `adoptLegacyDataRoot()` roda
([`MainActivityRuntime.kt:2613`](../../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L2613)),
ler `download_dir_path` do arquivo `armsx2`; se existir, não estiver vazia e for legível:

1. acrescentá-la a `romsDirs` se ainda não estiver lá (a biblioteca passa a **ver** os jogos);
2. **decidir** se ela vira também o destino de download e a pasta que `markDownloaded` consulta —
   isso é a opção que o relato irmão pede de volta. Sem essa decisão, o mínimo que fecha este
   relato é o item 1, e o catálogo continuará marcando esses jogos como "não baixados".

E em `validateSystemDirWritable`, tratar "o arquivo de sonda já existe" como gravável (apagá-lo e
tentar de novo, ou sondar com `FileOutputStream` como a linha anterior fazia).

## Como validar

Precisa de um aparelho com estado legado real — o mesmo pré-requisito que a TASK-0030 deixou
pendente:

1. Instalar a 1.0.23, em Configurações escolher uma pasta de download fora de `Android/data`
   (por exemplo `/storage/emulated/0/RetroSystem`), baixar um jogo pequeno pelo catálogo.
2. Instalar o APK do fork por cima (mesmo `applicationId`, mesmo certificado — TASK-0075).
3. Esperado depois da correção: o jogo aparece na aba Salvos, o catálogo o marca como baixado, e o
   logcat mostra a adoção da pasta. Hoje: aba Salvos vazia, catálogo oferecendo baixar.
4. Para a sonda: criar `.armsx2-write-probe` vazio na pasta de dados customizada e reabrir o app; a
   pasta tem de continuar sendo adotada.
