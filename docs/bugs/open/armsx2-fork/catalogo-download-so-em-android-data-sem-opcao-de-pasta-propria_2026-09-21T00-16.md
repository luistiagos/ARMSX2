# Bug: o catálogo só baixa para `Android/data` e o fork tirou a opção de pasta de download — "Limpar dados" ou reinstalar apaga a biblioteca inteira

- **Detectado em:** 2026-09-21 00:16 (achado ao investigar o relato de cliente de 2026-09-20:
  "os jogos que tinha baixado no app sumiram")
- **Origem:** **delta do fork** — regressão de produto em relação à 1.0.x, que tinha "Diretório de
  Download"; o destino fixo é nosso (`HomeViewModel.romsDir()`)
- **Errors (serviço):** nenhum — não é crash; é o Android apagando a pasta a pedido do usuário
- **Classe:** perda de dados irreversível por desenho; sem contorno disponível ao usuário
- **Reincidência:** a 1.0.x tinha a opção justamente por isto; ela não foi reimplementada no fork
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0099](../../../task/TASK-0099-opcao-pasta-propria-download-e-fragile-user-data.md)
- **Relacionado:**
  [a pasta de download da 1.0.x não é adotada](catalogo-pasta-de-download-da-1-0-x-nao-e-adotada-pelo-fork_2026-09-21T00-16.md)
  — o valor que a opção antiga gravava, e que o fork ignora

> Este é o candidato mais provável para o relato de 2026-09-20: cliente no fork, pasta padrão, e
> **nada no código do fork apaga a pasta `roms/`** (todos os `delete()` do app foram abertos —
> só há apagamento por jogo, com diálogo de confirmação, em
> [`deleteGame`](../../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L662)).
> Confirmar com ele: "na instalação da versão nova deu *app não instalado* e você desinstalou a
> anterior?" ou "tocou em *Limpar dados/armazenamento* nas configurações do Android?".

## Sintoma

Todos os jogos baixados pelo catálogo — dezenas de GB — desaparecem de uma vez, junto com memory
cards e savestates, depois de uma destas ações comuns:

- **desinstalar e instalar de novo** (por exemplo, porque a instalação do APK novo por cima falhou
  com "app não instalado");
- **Configurações do Android → Apps → RetroSystem PS2 → Limpar armazenamento / Limpar dados**
  (a resposta padrão de qualquer usuário a um app que abre em tela preta ou fecha sozinho — que
  são bugs abertos deste fork);
- troca de aparelho: o backup automático restaura as prefs, não os arquivos.

Não há aviso antes, e não há como recuperar depois.

## Evidência

### Onde os downloads ficam, e por que isso é frágil

[`HomeViewModel.romsDir()`](../../../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L481)
= `File(assetCopyRoot(app), "roms")`. Com a escolha padrão de armazenamento, `assetCopyRoot` é
`getExternalFilesDir(null)`
([`MainActivityRuntime.kt:1836`](../../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1836)),
ou seja `/storage/emulated/0/Android/data/come.nanodata.armsx2/files/roms`. A escolha "cartão SD"
leva a `getExternalFilesDirs()[1]`, que é a mesma árvore `Android/data/<pkg>` no outro volume.

`Android/data/<pkg>` é, por contrato do Android, **dado do app**: o sistema a apaga inteira na
desinstalação e em "Limpar armazenamento". O manifesto não declara `android:hasFragileUserData`
([`AndroidManifest.xml:67`](../../../../platforms/android/app/src/main/AndroidManifest.xml#L67)
tem só `allowBackup`), então a desinstalação nem oferece "manter os dados do app".

### A 1.0.x tinha a saída; o fork não tem

- 1.0.x: **Configurações → Diretório de Download** — "Pasta onde os ROMs baixados são salvos.
  Padrão: roms/ dentro da pasta de dados" (`values-pt-rBR/strings.xml:265-269`,
  `SettingsActivity.java:2573-2617` em `feature/handoff-end-to-end`). O usuário podia apontar para
  `/storage/emulated/0/<qualquer pasta>` ou para o SD fora de `Android/data`, e os jogos
  sobreviviam a desinstalar e a "Limpar dados".
- fork: a única escolha é **Armazenamento** em
  [`OnboardingViewModel.selectStorage`](../../../../platforms/android/app/src/main/java/com/armsx2/ui/onboarding/OnboardingViewModel.kt#L72)
  — Interno, Cartão SD (ambos `Android/data`) ou Pasta própria (só no flavor `github`, e move a
  raiz **inteira**: memcards, ini, saves, e só então os downloads junto). Não há como manter os
  dados do emulador no lugar privado e só os ROMs numa pasta do usuário — que era o arranjo da
  1.0.x e é o que faz sentido para 4–8 GB por jogo.

O comentário de `romsDir()` registra a escolha como deliberada — a pasta do usuário "pode estar
num cartão SD via SAF, onde um download de 10 GB com retomada não tem como escrever de forma
confiável". O argumento vale para SAF; não vale para um caminho POSIX com
`MANAGE_EXTERNAL_STORAGE`, que é exatamente o que o flavor `github` já pede
([`github/AndroidManifest.xml:19`](../../../../platforms/android/app/src/github/AndroidManifest.xml#L19))
e o que a 1.0.x usava (`canUseDirectFileAccess`, `DataDirectoryManager.java:599`).

## Impacto

- **Alta** pelo critério do índice ("saves existentes inacessíveis"): aqui somem os saves **e** os
  jogos, de forma irreversível, por uma ação que o próprio suporte costuma recomendar.
- O custo recai no cliente (horas de download, franquia de dados) e no suporte (parece corrupção).
- Não há mitigação disponível hoje: nem aviso, nem opção, nem `hasFragileUserData`.

## Correção proposta

Em ordem de custo:

1. **`android:hasFragileUserData="true"`** no manifesto. Uma linha; a desinstalação passa a
   perguntar "manter dados do app?". Não cobre "Limpar dados".
2. **Devolver "Pasta de download"** como opção separada de "Armazenamento", no flavor `github`
   (caminho POSIX sob `MANAGE_EXTERNAL_STORAGE`, como na 1.0.x). A biblioteca varre essa pasta,
   `romsDir()` grava nela, `markDownloaded` consulta nela — os três pontos que hoje derivam de
   `assetCopyRoot`. É a mesma decisão que fecha o relato irmão da adoção de `download_dir_path`.
3. **Aviso** na primeira vez que o usuário baixa um jogo com a pasta padrão: "os jogos ficam na
   pasta do app e são apagados se você desinstalar ou limpar dados; para mudar, …".

## Como validar

1. Baixar um jogo pequeno com a pasta padrão; desinstalar; reinstalar. **Hoje:** o jogo sumiu.
   **Com o item 1:** o Android pergunta se mantém os dados; respondendo sim, o jogo está lá.
2. Com o item 2: escolher `/storage/emulated/0/RetroSystem` como pasta de download, baixar,
   "Limpar dados" nas configurações do Android, abrir o app: o jogo continua no disco e, depois de
   reapontar a pasta, na biblioteca.
