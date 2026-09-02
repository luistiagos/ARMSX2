# TASK-0075: o fork publica em trilha própria, sem tocar nos clientes da linha antiga

- **Status:** concluída
- **Criada em:** 2026-09-02
- **Concluída em:** 2026-09-02
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Commit:** 6bb695c0c3 (o vínculo é o prefixo `TASK-0075:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## O que estava armado para dar errado

Esta branch nunca publicou nada. Mesmo assim, no dia 2026-09-02 ela estava configurada para
**atualizar todos os clientes da linha antiga para o fork** na primeira publicação. Três fatos, cada
um verificado:

| | valor medido | onde |
|---|---|---|
| o que está no ar em `rgs/ps2/` | `versionCode 37`, `1.0.23` | `GET https://versions.digitalstoregames.com/rgs/ps2/version.json` |
| quem publicou isso | `TASK-0009` de **`feature/handoff-end-to-end`** | `git log --all -S"1.0.23"` → `0bc7e826d0` |
| o que esta branch ia publicar | `versionCode 38`, `1.0.24` | `platforms/android/gradle.properties:39` |

`38` é exatamente o próximo número da série da linha antiga. E
`platforms/android/app/build.gradle.kts:286` fixava o endpoint de atualização em
`rgs/ps2/version.json` — a pasta **deles**.

Somando: um `assembleGithubRelease` seguido do script de publicação da linha antiga teria escrito
o APK do fork por cima de `rgs/ps2/retrosystem-ps2.apk` e anunciado `38 > 37`, e todo aparelho com
1.0.23 instalado teria baixado o fork sozinho, sem ninguém pedir.

O Android não teria barrado nada. As duas linhas compartilham `applicationId`
(`come.nanodata.armsx2`) **e o mesmo certificado de release** — `retrosystem_release.jks`, alias
`retrosystem`, SHA-256 `d34a788ab0f4fb5b467be5839c4317d66a46525397dfeebdeb40ba4b97c0745a`,
conferido com `keytool -list -v` nas duas árvores. Para o sistema é o mesmo app, e a instalação
seria um upgrade in-place.

Não é hipótese: o APK que estava em `app/build/outputs/apk/github/release/app-github-release.apk`
carrega, no `classes.dex`, a string
`https://versions.digitalstoregames.com/rgs/ps2/version.json`. Publicá-lo teria feito **os
usuários do fork consultarem o `version.json` da linha antiga** — a mesma confusão na direção
oposta, e permanente, porque o endpoint é compilado dentro do APK e só sai com um novo APK.

## A decisão, e de quem é

Do dono do produto, em 2026-09-02: *"esta ARMSX2-fork é uma versão totalmente diferente da outra
que está no branch version1, ambas têm que ter um caminho diferente para a geração de versão, sem
poder misturar (...) esta app não pode gerar atualização para quem baixou outra, só quem baixou o
APK desta versão terá as atualizações futuras dela."*

Três escolhas foram levadas a ele e respondidas na mesma conversa:

1. **Série de versão:** `versionCode 2000` / `versionName 2.0.0`. As duas séries deixam de poder se
   cruzar — a antiga segue em 38, 39, 40… e levaria mais de mil releases para alcançar. A
   alternativa (continuar em 38) colidiria em ~7 releases, com dois APKs diferentes carregando o
   mesmo package, o mesmo `versionCode` e o mesmo certificado.
2. **`applicationId`:** **continua `come.nanodata.armsx2`**, respeitando a restrição de identidade
   do `CLAUDE.md`. Consequência aceita: um aparelho tem um dos dois, nunca os dois. Quem instalar o
   APK do fork por cima da linha antiga faz upgrade in-place e herda os dados.
3. **Pasta no R2:** `rgs/ps2fork/`, vizinha de `rgs/ps2/`. O **nome do arquivo APK continua
   `retrosystem-ps2.apk`** nos dois lados, como pedido.

## Por que a separação de endpoint é o que realmente resolve

A atualização automática é o **único** caminho pelo qual uma linha alcança o aparelho de alguém sem
ação humana, e ela consulta exatamente uma URL: a que o `AppUpdateManager` lê de
`BuildConfig.APP_UPDATE_ENDPOINT` (`AppUpdateManager.java:156`). Essa URL é compilada dentro do
APK. Logo:

- um APK do fork só olha `rgs/ps2fork/version.json` — pasta que o script da linha antiga nunca
  escreve;
- um APK da linha antiga só olha `rgs/ps2/version.json` — pasta que este script nunca escreve.

O `channel` é a segunda tranca, e independente da primeira: `AppUpdateManager.java:181-185` recusa
um `version.json` cujo `channel` não bata com `BuildConfig.APP_UPDATE_CHANNEL`. O fork passa a usar
`fork`; a linha antiga usa `default`. Se algum dia os dois arquivos forem trocados de lugar por
engano, o app recusa em vez de instalar.

Ambos os valores viram **default de projeto** no `build.gradle.kts`, não flag de linha de comando —
pela mesma razão já escrita no `gradle.properties` sobre `applicationId`/`versionCode`: esquecer uma
flag publica um APK errado, e este erro específico é irreversível na prática.

## Escopo

**Entra:**

- `platforms/android/gradle.properties` — `armsx2.versionCode` 38 → **2000**, `armsx2.versionName`
  1.0.24 → **2.0.0**, com o comentário explicando por que as duas séries são disjuntas.
- `platforms/android/app/build.gradle.kts` — default de `APP_UPDATE_ENDPOINT` passa a
  `.../rgs/ps2fork/version.json` e o de `APP_UPDATE_CHANNEL` passa a `fork`. Só o flavor `github`;
  o `play` continua com os dois campos vazios.
- `scripts/publish_fork_r2.ps1` — **novo**. Build + verificação + publicação em `rgs/ps2fork/`.
- `.gitignore` — `dist/`, `r2-config.json` e `build.properties`, que o script produz ou lê e que
  carregam credenciais ou artefatos.

**Não entra, deliberadamente:**

- **Qualquer alteração em `rgs/ps2/`.** A linha antiga fica exatamente como está, servindo 1.0.23
  aos clientes dela. Este script tem um guard que aborta se o destino resolver para aquela pasta.
- **Mudar o `applicationId`.** Foi perguntado e recusado (item 2 acima).
- **Trocar o certificado de release.** As duas linhas continuam assinadas com a mesma chave; é o
  que permite o upgrade in-place manual, e trocar quebraria as instalações do fork no futuro.
- **Um script de publicação para a linha antiga.** O `build-and-upload.ps1` dela continua vivo na
  branch dela e não é tocado.
- **Migração de dados entre as linhas.** Fora do escopo de publicação.

## Os gates que este script tem e o da linha antiga não tinha

O script da linha antiga verificava assinatura e lia a versão do APK. Este verifica mais quatro
coisas, e cada uma nasceu de um defeito **medido**, não imaginado:

| gate | o que exige | o que pega |
|---|---|---|
| 4c | `applicationId` == `come.nanodata.armsx2` e `versionName` no padrão X.Y.Z | build de teste com `applicationIdSuffix` |
| 4d | versão do APK == versão do `gradle.properties` | APK sobrando em `dist/`, ou build com override |
| 4e | `versionCode` ≥ 2000 | número da série da linha antiga entrando na pasta do fork |
| 4f | endpoint do fork **presente** no dex e o da linha antiga **ausente** | APK que consultaria a trilha errada |

O **4f é o que decide**. É o único jeito de provar que o APK que está subindo vai consultar a pasta
certa pelo resto da vida dele: ler o `build.gradle.kts` não prova nada, porque o APK em `outputs/`
pode ser de um build anterior à mudança — foi exatamente o que custou uma investigação inteira na
[TASK-0073](TASK-0073-lancamento-externo-entrega-file-uri-cru-ao-core.md).

Funciona porque as strings do dex são MUTF-8 gravadas literalmente, e o R8 inlina a constante
`static final String` no ponto de uso sem tirá-la do pool. Decodificar em ISO-8859-1 preserva
byte-por-char, então um `IndexOf` ordinal acha exatamente o que está nos bytes.
(`[System.Text.Encoding]::Latin1` **não existe** no .NET Framework do PowerShell 5.1;
`GetEncoding(28591)` é o mesmo codec e existe nos dois.)

### O 4c e o 4d saíram do teste, não do plano

O plano tinha só o 4e e o 4f. Ao rodar o script contra o APK que estava em
`app/build/outputs/apk/github/release/`, ele acusou o endpoint da linha antiga — como esperado — mas
a saída mostrou de passagem o que aquele APK era:

```
Pacote:  come.nanodata.armsx2.perf
Versao:  0.0.0-perftest (code 9001)
```

Um build de **perf-test**. Ele passou pela assinatura (é o mesmo certificado) e passou pelo piso da
série, porque `9001 ≥ 2000`. Só o gate do endpoint o barrou — e se ele estivesse certo, aquele APK
teria sido publicado. Duas consequências, as duas ruins:

- `come.nanodata.armsx2.perf` instala como **outro app**. Ninguém que tem o fork receberia, e o
  updater dele não alcançaria ninguém.
- `9001` queimaria a série. Depois de um aparelho instalar 9001, um `2001` seria recusado pelo
  Android **para sempre** naquele aparelho.

Daí os gates 4c e 4d. O 4d é o mais forte dos dois porque não depende de lista de sufixos
conhecidos: ancora o artefato no repositório, exigindo que o APK carregue exatamente a versão que o
`gradle.properties` declara.

## A prova de não-interferência, dentro do próprio script

O requisito do produto é *"esta app não pode gerar atualização para quem baixou a outra"*. O passo 1
tira uma foto do `version.json` **deles** e o passo 10 re-lê e exige que esteja idêntica. Ou seja: o
script **mede** o requisito ao fim de cada publicação, em vez de confiar que os guards bastaram.

## Já validado

- **Sintaxe** — `[Parser]::ParseFile` sem erros (4.074 tokens).
- **Guards do passo 1** — rodaram e imprimiram a trilha certa; a foto da linha antiga leu
  `1.0.23 (code 37)`.
- **Gate 4a** — aceitou o certificado oficial.
- **Gate 4c** — abortou com `APPLICATION ID ERRADO` no APK `come.nanodata.armsx2.perf`.
- **Gate 4f** — abortou com `ENDPOINT DA LINHA ANTIGA DENTRO DO APK` no APK pré-TASK-0075.
- **Bump** — exercitado a seco contra o `gradle.properties` real: altera exatamente duas linhas
  (`2000 → 2001`, `2.0.0 → 2.0.1`), preserva CRLF, tamanho do arquivo inalterado.
- **`check_traceability.py`** — `OK -- 75 task(s), 2 feature(s)`.

## Falta validar (precisa de um build de ~15 min)

- O **caminho feliz** do gate 4f: um APK construído depois desta task tem de conter
  `.../rgs/ps2fork/version.json` e **não** conter o endpoint da linha antiga. Hoje só o lado
  negativo foi exercitado.
- Os passos 5–10 contra o R2 de verdade.

Ordem sugerida:

1. `.\scripts\publish_fork_r2.ps1 -NoBump -DryRun` — build + todos os gates, sem escrever no R2.
   `-NoBump` porque o bump roda **antes** do build: sem ele a primeira versão no ar seria 2.0.1 e o
   2.0.0 nunca existiria.
2. Conferir na saída: `come.nanodata.armsx2`, `2.0.0 (code 2000)`, `canal fork`, destino
   `rgs/ps2fork`.
3. `.\scripts\publish_fork_r2.ps1 -NoBump` para publicar de fato.
4. `curl https://versions.digitalstoregames.com/rgs/ps2/version.json` — tem de continuar
   respondendo `37` / `1.0.23`.
