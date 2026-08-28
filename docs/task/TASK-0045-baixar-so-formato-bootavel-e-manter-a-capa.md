# TASK-0045: baixar só formato que o emulador abre, e não perder a capa do catálogo

- **Status:** concluída
- **Criada em:** 2026-08-28
- **Concluída em:** 2026-08-28
- **Feature:** nenhuma
- **Bugs que resolve:**
  [download entrega formato não bootável](../bugs/open/catalogo-download-entrega-formato-nao-bootavel_2026-08-28T10-27.md),
  [jogo baixado perde a capa](../bugs/open/biblioteca-jogo-baixado-perde-a-capa_2026-08-28T10-27.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0045:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

O que o catálogo baixa passa a rodar, ou a falhar antes de consumir a franquia de dados do usuário.
E um jogo baixado nunca fica pior do que estava no catálogo: se a capa aparecia lá, continua
aparecendo aqui.

## Escopo

**Entra:**

- `catalog/RomDownloadManager.java`
  - **Só fonte que o CDVD abre.** Um link é candidato apenas se a extensão dele estiver em
    `chd, iso, cso, zso, gz, bin, img, mdf, nrg, dump`. `.7z`, `.zip` e `.rar` deixam de ser
    aceitos por qualquer um dos três endpoints — o app não descompacta nada, então baixar 2,3 GB
    de 7z é gastar dados para produzir um arquivo inútil.
  - **Ordem das variantes vira `.chd`, `.iso`, `.cso`, `.zso`.** Hoje é `.7z` PRIMEIRO, o que faz o
    app preferir o formato que não roda mesmo quando existe um `.chd` do mesmo jogo.
  - **O `.part` só é retomado se veio da mesma URL**, anotada num arquivo `.part.src` ao lado.
    O resume manda `Range: bytes=<tamanho do .part>-` para a fonte que a resolução devolver
    **agora** — e ela mudou para muita entrada justamente por causa desta task. Sem a checagem, a
    segunda metade de um arquivo entra na primeira metade de outro e o resultado tem o tamanho
    certo, nenhum erro no caminho e nenhum jogo dentro. `DownloadQueueManager.remove` passa a
    apagar os dois arquivos.
  - **404 não é retentado.** Uma entrada sem fonte utilizável termina no fallback do HuggingFace,
    que não a tem; hoje isso custa três tentativas e dois `sleep` antes de desistir. 404 é resposta
    definitiva — falha na primeira.
  - **O arquivo é gravado com a extensão do que chegou.** `007 - Quantum of Solace (Europe…).iso`
    resolvido para um `.chd` é salvo `…(Europe…).chd`. O `.part` continua com o nome do manifesto,
    para o resume e a limpeza de `DownloadQueueManager.remove` seguirem funcionando iguais; a troca
    de extensão acontece no rename final.
- `catalog/CatalogParser.java` — `markDownloaded` passa a casar **pelo nome sem extensão**, e a
  fazer uma leitura do diretório em vez de um `exists()` por entrada (12.628 `stat()` a cada
  varredura). Sem isso, um jogo salvo como `.chd` a partir de uma linha `.iso` ficaria eternamente
  "não baixado".
- `ui/home/HomeViewModel.kt` — `mergeCatalog` casa pelo mesmo critério (nome sem extensão) e passa
  a levar `catalogCoverUrl` para a linha local. É a correção do segundo bug: capa do manifesto como
  rede de proteção para todo jogo sem serial sondável, não só para os deste relato.

**NÃO entra:**

- **Descompactar `.7z`.** Precisaria de commons-compress + XZ no APK e do dobro do espaço livre
  durante a extração. É a única forma de recuperar os títulos que só existem comprimidos (5 de 10
  na amostra do bug) e está fora deste commit por tamanho.
- **Limpar o manifesto.** As ~9.077 entradas `.iso` continuam no catálogo; a maioria não tem fonte
  utilizável e agora falha cedo em vez de baixar lixo.
- **Mostrar o motivo da falha na tela.** `onError` já descarta a mensagem hoje: o item fica
  "pausado" e o porquê só existe no log. Propagar exige mexer em `CatalogEntry`,
  `DownloadQueueItem`, i18n e na tela de downloads — outra task.
- **Apagar os arquivos ruins que já estão no aparelho.** É dado do usuário; a decisão é dele.
- Qualquer edição em `pcsx2/` ou `common/`. O delta no core continua zero.

## Como validar

1. `assembleGithubRelease` (ou debug) compila.
2. Aparelho: apagar `007 - Quantum of Solace (Europe, Australia) (En,Fr,De,Es,It).iso` (o CHD com
   nome errado) e baixar de novo pelo catálogo. Esperado: o arquivo aparece como `.chd`, a
   biblioteca o marca como baixado (uma linha só, não duas) e ele **dá boot**.
3. Aparelho: tentar baixar `10.000 Bullets (Europe) (En,Fr,De,Es,It).iso` — só existe em `.7z`.
   Esperado: falha imediata (404 do HuggingFace, sem retentativa), sem gigabytes transferidos e sem
   arquivo novo em `files/roms/`.
4. Capa: com um jogo baixado cujo serial a sonda não devolve, a capa do manifesto tem de continuar
   na tela. Verificável hoje com os quatro 7z que já estão no aparelho — depois desta task eles
   voltam a mostrar capa, mesmo continuando sem rodar.

## Resultado

Entregue. `assembleGithubRelease` compila e os testes novos passam
(`app/src/test/java/com/armsx2/catalog/DownloadFormatTest.kt`, 5 casos): extração da extensão da
URL, a lista do que o CDVD abre, o nome local do CHD entregue para uma linha `.iso`, o descarte de
um `.part` com a anotação de origem, e o `markDownloaded` reconhecendo o jogo salvo em outro
formato.

As validações 2, 3 e 4 **dependem do aparelho** e seguem pendentes — exigem instalar o build por
cima do 1.0.24 e refazer um download real.

Os arquivos já baixados errado continuam no aparelho (~9,5 GB): quatro 7z e um CHD, todos com nome
`.iso`. Depois desta task eles voltam a mostrar capa, e continuam sem rodar — apagá-los é decisão
do usuário, não do app.
