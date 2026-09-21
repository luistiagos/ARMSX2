# Bug: a pasta de ROMs do app é semeada uma vez e não acompanha a troca de armazenamento — jogo baixado depois da troca some ao terminar

- **Detectado em:** 2026-09-21 00:16 (achado ao investigar o relato de cliente de 2026-09-20:
  "os jogos que tinha baixado no app sumiram")
- **Origem:** **delta do fork** — `seedOwnRomsFolder`, `romsDir()` e a tela de armazenamento são
  nossos
- **Errors (serviço):** nenhum — não é crash
- **Classe:** fail silencioso (duas partes do app discordam sobre onde ficam os jogos)
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0097](../../task/TASK-0097-pasta-de-roms-do-app-acompanha-raiz-de-dados.md)
- **Relacionado:**
  [a pasta de download da 1.0.x não é adotada](../open/armsx2-fork/catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork_2026-09-21T00-16.md)
  — mesma raiz: `romsDirs` é uma foto de um caminho, não uma regra

## Sintoma

O usuário troca **Armazenamento** (interno → cartão SD, ou → pasta própria) na tela de
configuração do fork. A partir daí, todo jogo que ele baixa pelo catálogo **termina de baixar e
some**: a fila mostra "concluído", a aba Salvos não ganha o jogo, e no catálogo a linha volta a
oferecer "baixar". Os jogos baixados **antes** da troca continuam aparecendo.

Se a troca for na direção contrária (SD → interno), o efeito é o mesmo para os downloads novos.

## Evidência — a cadeia, verificada elo a elo

1. **`romsDirs` é semeada uma vez, com um caminho absoluto, e só quando está vazia.**
   [`seedOwnRomsFolder`](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L3270):

   ```kotlin
   if (romsDirs.value.isNotEmpty()) return
   val own = java.io.File(assetCopyRoot(this), "roms")
   setRomsDirs(listOf(own.absolutePath))
   ```

   `setRomsDirs` persiste a lista em prefs
   ([`MainActivityRuntime.kt:203`](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L203)).
   Depois disso a lista nunca está vazia, e o `return` da primeira linha é definitivo.

2. **A troca de armazenamento muda `systemDir` e não toca `romsDirs`.**
   [`OnboardingViewModel.selectStorage`](../../../platforms/android/app/src/main/java/com/armsx2/ui/onboarding/OnboardingViewModel.kt#L72)
   e
   [`selectCustomStorage`](../../../platforms/android/app/src/main/java/com/armsx2/ui/onboarding/OnboardingViewModel.kt#L99)
   escrevem `systemDir` e a pref `"systemDir"`; nenhuma das duas menciona `romsDirs`. Não há
   migração de arquivos (a linha anterior tinha `migrateData`; o fork não tem equivalente na tela).

3. **`assetCopyRoot` segue `systemDir`** — é memoizado por essa chave
   ([`MainActivityRuntime.kt:1836`](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1836))
   e invalida sozinho quando ela muda.

4. **O destino do download e a marca de "baixado" seguem `assetCopyRoot`.**
   [`HomeViewModel.romsDir()`](../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L481)
   = `File(assetCopyRoot(app), "roms")`; é o que `queue.setRomsDir(...)` recebe (`:493`) e o que
   `markDownloaded` consulta (`:505`, `:244`).

5. **A biblioteca segue `romsDirs`.**
   [`GameLibraryRepository.scan(directories)`](../../../platforms/android/app/src/main/java/com/armsx2/data/library/GameLibraryRepository.kt#L76)
   varre só o que recebe; quem chama passa `romsDirs.value`.

6. **Um jogo que o catálogo diz "baixado" mas a varredura não achou vira linha de catálogo.**
   `mergeCatalog` constrói toda entrada sem arquivo varrido com `needsDownload = true`
   ([`HomeViewModel.kt:595`](../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L595))
   — sem consultar `entry.isDownloaded` —, `isCatalogOnly` é `needsDownload`
   ([`GameInfo.kt:450`](../../../platforms/android/app/src/main/java/com/armsx2/GameInfo.kt#L450)),
   e a aba Salvos filtra `!game.isCatalogOnly`
   ([`HomeViewModel.kt:414`](../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L414)).

Somando: depois da troca,

```
download grava em      <novo assetCopyRoot>/roms      (4)
biblioteca varre       <antigo assetCopyRoot>/roms    (1, 5)
```

e o jogo recém-baixado não existe para a aba Salvos (6). O `onQueueChanged` até invalida o cache
e revarre (`HomeViewModel.kt:743-758`), mas revarre a pasta errada.

## Por que isto não estava coberto

O comentário de `seedOwnRomsFolder` diz que o `return` protege "quem já escolheu as suas pastas".
Protege — mas trata a pasta **do próprio app** como se fosse uma escolha do usuário, quando ela é
derivada de `assetCopyRoot` e deveria acompanhá-lo. As duas coisas foram guardadas na mesma lista
sem distinção.

## Correção proposta

Separar os dois conceitos: a pasta própria do app é **implícita e derivada** (sempre
`assetCopyRoot/roms`, calculada na hora de varrer), e `romsDirs` guarda só as pastas que o usuário
acrescentou. Concretamente, uma de duas:

- `scan()` recebe `listOf(romsDir()) + romsDirs.value` (dedupe), e `seedOwnRomsFolder` deixa de
  existir; ou
- ao trocar `systemDir`, substituir em `romsDirs` a entrada antiga de `assetCopyRoot/roms` pela
  nova (mais frágil: depende de reconhecer a entrada antiga).

A primeira também fecha metade do relato irmão: uma pasta legada acrescentada a `romsDirs` deixa
de disputar lugar com a pasta própria.

Decisão aberta para o dono: ao trocar de armazenamento, **mover** os jogos já baixados (como a
1.0.x fazia com `migrateData`) ou continuar varrendo a pasta antiga também.

## Como validar

Aparelho com cartão SD:

1. Com armazenamento interno, baixar um jogo pequeno pelo catálogo — aparece em Salvos.
2. Configuração → Armazenamento → cartão SD.
3. Baixar outro jogo pequeno. **Hoje:** a fila conclui e o jogo não aparece em Salvos; o catálogo
   volta a oferecer "baixar". **Esperado:** aparece em Salvos, e o primeiro jogo continua lá.
4. Conferir com o app Arquivos (o fork expõe a pasta pelo
   [`Armsx2DocumentsProvider`](../../../platforms/android/app/src/main/java/com/armsx2/provider/Armsx2DocumentsProvider.java))
   que o segundo arquivo está em `<sd>/Android/data/come.nanodata.armsx2/files/roms`.

## Validação em Aparelho Físico (Moto G86 5G — Android 16, SDK 36)

Executada em 2026-09-21 com o APK de teste gerado pela [TASK-0097](../../task/TASK-0097-pasta-de-roms-do-app-acompanha-raiz-de-dados.md):

1. **Boot Limpo e SharedPreferences Intactas:**
   - Conferido `shared_prefs/ARMSX2.xml` via `run-as` na primeira inicialização: a preferência `romsDirs` não foi criada nem pré-semeada com caminho estático absoluto.
2. **Pasta Implícita Funcional:**
   - Adicionado `Shadow of the Colossus (USA).chd` na pasta do pacote (`/sdcard/Android/data/.../files/roms/`).
   - O jogo apareceu imediatamente na aba "Salvos" (*Total de jogos: 1*) com capa e metadados, sem necessidade de configurar nenhuma pasta em `romsDirs`.
3. **Troca de Raiz de Dados Multi-Volume:**
   - Configurado `systemDir = /sdcard/ARMSX2_Custom` e inserido `Grand Theft Auto - San Andreas (USA).iso` na nova pasta `/sdcard/ARMSX2_Custom/roms/`.
   - Ao recarregar a biblioteca, **ambos os jogos foram reconhecidos simultaneamente** (*Total de jogos: 2*). Jogos baixados antes e depois da troca de armazenamento permanecem visíveis.
4. **Higienização de Instalações Legadas:**
   - Injetada lista em `romsDirs` com a pasta privada do app mais `/sdcard/UserCustomGames`. Na inicialização, a pasta privada foi expurgada automaticamente e apenas a pasta externa do usuário foi mantida.

