# TASK-0096: devolver à biblioteca a entrada do Quick Loading, e medir lá o page cache da extração

- **Status:** aberta
- **Criada em:** 2026-09-11
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:**
  [quick-loading-sem-entrada-apos-merge-da-task-0067](../bugs/open/armsx2-fork/quick-loading-sem-entrada-apos-merge-da-task-0067_2026-09-11T15-40.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0096:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

> **Fora da sequência serializada da FEAT-0003.** Nasceu do fechamento da
> [TASK-0090](TASK-0090-duas-correcoes-de-campo-do-upstream.md) (saída 3, decisão do usuário em
> 2026-09-11), mas não trava nenhum dos blocos 2–5 e não é travada por eles. **Não rodar em paralelo
> com um bloco em andamento que também instale APK no aparelho** — os dois disputariam o "antes".

## Objetivo

Devolver ao menu do jogo as ações **⚡ Quick Loading** e **🧹 remover**, que o upstream tem e o merge
da TASK-0067 perdeu; e, com a extração enfim alcançável, **validar no aparelho** a correção de page
cache (`c2bea2f029`) que a TASK-0090 trouxe e não pôde exercitar.

## O que já foi estabelecido (2026-09-11) — não refaça, confira

Tudo abaixo está com evidência no bug e no Resultado da TASK-0090.

| Fato | Onde |
|---|---|
| `QuickLoadSetup` existe e **não tem chamador** na nossa árvore | `QuickLoadSetup.kt:29`; `git grep QuickLoadSetup HEAD` |
| O upstream chama só de `ui/home/HomeScreen.kt` (6 blocos, linhas 176–1280) | tabela do bug |
| A biblioteca **já varre o `hostfs`**, então o ELF extraído aparece sozinho | `GameLibraryRepository.kt:74` |
| As 14 chaves `games.quickLoad*` estão no `I18n.kt` e **nenhuma** no `pt-BR.json` | `grep -c` |
| O nosso menu do jogo é um `PadModal` com `GameMenuAction`, igual em forma ao do upstream | nosso `HomeScreen.kt:773-843` |
| `vm.dirty_bytes = 104857600` (100 MB **absolutos**) no SM-A127M | `/proc/sys/vm/`, TASK-0090 |
| Os 4 DVDs do aparelho são "um arquivo só" (God of War 2: `PART1.PAK` de 4.065 MB) | TASK-0090 |

## Escopo

**Entra:**

1. **Portar do upstream, para o nosso `HomeScreen.kt`, os seis blocos do Quick Loading** (estado +
   seletor, ação ⚡ só para discos, ação 🧹 só para ELF instalado, confirmação com espaço antes do
   seletor, remoção, "trabalhando" + resultado). **Portar, não reescrever**: a lógica deles fica, só
   se adapta ao que o nosso arquivo tem em volta.
2. **Traduzir as 14 chaves `games.quickLoad*` para `assets/i18n/pt-BR.json`.**
3. **Validar no aparelho o fluxo inteiro e o Defeito 2 da TASK-0090**, com o critério corrigido
   abaixo — não com o 2a original.

**NÃO entra:**

- **Reescrever o nosso `HomeScreen.kt` para ficar igual ao do upstream**, nem trazer outras coisas
  que o merge da TASK-0067 também deixou de fora (prateleiras por categoria etc.). Cada uma é decisão
  de produto própria.
- **Mexer em `QuickLoadSetup.kt` ou em `extractIsoToHostfs`.** São do upstream e já estão aqui. Se
  algo estiver errado neles, é contribuição ao upstream (regra do `CLAUDE.md`).
- **Otimizar a velocidade da extração.** O custo em tempo é aceito pela TASK-0090.
- Traduzir para as outras 14 línguas.

## Como implementar

1. **Abrir os dois lados antes de escrever** — regra do projeto. No mínimo:

```bash
F=platforms/android/app/src/main/java/com/armsx2/ui/home/HomeScreen.kt
git show upstream/master:$F | sed -n '170,200p;1116,1150p;1160,1285p'
sed -n '150,210p;770,900p' $F
```

   Conferir que cada símbolo que o trecho portado usa **existe no nosso arquivo com o mesmo nome**:
   `viewModel.refresh()`, `PadModal`, `GameMenuAction`, `rememberCoroutineScope`, o `context` em
   escopo, `str(...)`. Onde o nome for outro, adaptar; onde não existir, **parar e registrar** — não
   inventar um helper que "deveria existir".

2. **A condição da ação ⚡ é "só discos"** — o upstream testa `.elf` pelo URI **e** pela extensão. Não
   simplificar.

3. **Compilar** — esta task toca `HomeScreen.kt` e, se tocar `I18n.kt`, exige
   `-Pkotlin.incremental=false` (armadilha do `LSFG_EN`). O contorno de JDK 21 é obrigatório:

```bash
cd platforms/android && ./gradlew.bat --stop
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:installGithubDebug \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

   `compile*Kotlin` verde **não** põe nada no aparelho. O `versionCode` do build vem de
   `platforms/android/gradle.properties`, que no working tree do usuário estava em **2005**; o
   commitado era 2000 e seria recusado como downgrade. **Não reverter nem commitar esse arquivo.**

4. **Dirigir o aparelho** (aprendido na TASK-0090): no Git Bash, `MSYS_NO_PATHCONV=1` antes de passar
   caminho `/storage/...`; `uiautomator dump` não funciona neste app (a animação de fundo nunca
   assenta); esperar ~5 s e tirar screenshot antes de cada toque — a tela de Configurações leva mais
   de 3 s para abrir no SM-A127M, e um toque adiantado já mudou um ajuste por engano.

## Como validar

### Critério 1 — a entrada existe e só onde deve

- **1a:** toque longo num `.iso` → aparece **⚡ Quick Loading**.
- **1b:** toque longo num `.elf` → **não** aparece ⚡.
- **1c:** tudo em pt-BR, **nenhuma chave crua**.

### Critério 2 — o fluxo completo funciona

- **2a:** a confirmação mostra o espaço necessário e o livre **antes** do seletor; "cancelar" não
  escreve nada.
- **2b:** escolhido o ELF modificado, a extração roda, o indicador aparece, e o resultado é mostrado.
- **2c:** **o ELF extraído aparece na biblioteca** sem rescan manual, e **dá boot**.
- **2d:** a ação 🧹 aparece **só** nesse ELF; remover apaga a pasta em `hostfs/<serial>/` e a entrada
  some.

> O Quick Loading precisa de um **ELF modificado** para o jogo (o fluxo "host ELF" do upstream). Se
> não houver um disponível para nenhum dos DVDs do aparelho, **pare e pergunte ao usuário** — sem ELF
> o fluxo não passa do seletor, e isso não é defeito do código.

### Critério 3 — o page cache, com um critério que discrimina

O 2a original da TASK-0090 ("`Dirty` cresce rumo aos GB no antes") **não discrimina neste aparelho**:
o Samsung prende `Dirty` em 100 MB absolutos, e os DVDs são um arquivo gigante cada — o
`fsync`+`DONTNEED` só age no **fim** de cada arquivo. O que discrimina:

- **Ao fim da extração**, ler `/proc/meminfo`: **depois** da correção, `Dirty` perto de zero e
  `Cached` **caindo** na ordem do tamanho dos arquivos extraídos (o `DONTNEED` descartando páginas
  limpas); **antes**, `Cached` alto e retendo o disco.
- Preferir **um disco com vários arquivos médios** a um "arquivo só", porque é onde a diferença entre
  descartar por arquivo e não descartar aparece durante a extração, e não só no fim.
- **Precisa de par.** O "antes" é um APK **sem** `c2bea2f029` e **com** esta entrada — por
  construção ele não existe pronto. Construir um exige reverter `native-lib.cpp` localmente: **isso
  precisa de autorização explícita do usuário antes**, e deve ser feito sem tocar nos arquivos
  alheios do working tree. Se não houver autorização, medir só o "depois" e **registrar o critério
  como sem par** — não inventar um substituto.

### Critério 4 — o app sobrevive ao depois

Terminada a extração, usar o app por ~1 minuto (boot do ELF, menu, voltar). Nenhum `lmkd` "device is
not responding", nenhum `signal 9` do `come.nanodata.armsx2` no `adb logcat -d`.

### Critério 5 — custo

Tempo da extração, registrado. É esperado que a correção o aumente; é informação, não reprovação.

## Antes de fechar

```bash
python scripts/check_traceability.py --fix
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

Ao concluir: mover o bug para `docs/bugs/done/armsx2-fork/` e atualizar o Resultado da TASK-0090 com
uma linha apontando para o veredito do Defeito 2 aqui.

## Resultado

— (a preencher pela sessão que implementar)
