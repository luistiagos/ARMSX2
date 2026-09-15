# TASK-0095: medir o que o `autoFlush` custa ao God of War em GPU fraca e, se a troca valer, propô-la ao upstream

- **Status:** aberta
- **Criada em:** 2026-09-11
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [god-of-war-lento-no-poco-c75-mali-g52](../bugs/open/armsx2-fork/god-of-war-lento-no-poco-c75-mali-g52_2026-09-11T14-36.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0095:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Resumo em uma linha

Medir, em aparelho, quanto desligar o `autoFlush: 1` do GameDB rende ao God of War I e II numa GPU
tiler fraca e quanto custa de fidelidade contra o renderizador software; se a troca passar na régua
que o próprio upstream já usou, abrir o PR no **overlay mobile do upstream** — nunca editá-lo aqui.

## De onde vem

Um usuário relatou God of War *extremamente lento* num POCO C75 (Helio G81 Ultra, Mali-G52 MC2). O
registro do bug amarra a causa provável ao código em quatro elos. O que interessa aqui:

- os **14 seriais** de God of War carregam `autoFlush: 1` no GameDB;
- o `armsx2_overrides.yaml` — o overlay que o upstream aplica só em tiler — não relaxa nenhum;
- a hipótese nunca foi medida: a auditoria de 2026-08-10 a levantou por leitura de código, e o
  primeiro dos seus "próximos passos" era justamente medir antes de mexer.

## Por que isto NÃO é uma edição local

`bin/resources-overlay/armsx2_overrides.yaml` **é do upstream**: `git diff upstream/master` sobre
ele é vazio, e os últimos oito commits nele são do Brian Degenhardt e do jpolo1224. Editá-lo aqui é
exatamente a divergência que a regra do fork proíbe — *"correção de motor nasce como contribuição
ao upstream, não como edição local"* — e a próxima `git merge upstream/master` cobraria o conflito.

E o upstream **já aceita** esta classe de mudança, com precedente escrito.

## A régua: o que o upstream exigiu para o Rogue Galaxy

`bf65e8604b` (2026-07-30), *"GameDB: drop autoFlush on Rogue Galaxy — a deliberate speed/accuracy
trade"*. É o modelo do PR, e o que ele trouxe é o mínimo que o nosso precisa trazer:

| o que | como o Rogue Galaxy mostrou |
|---|---|
| o jogo é **relatado** como lento | *"the slowest title we track on handhelds and users report it as such"* |
| o ganho, **medido** em aparelho sem folga | Adreno 610: −1,82 ms/quadro, +2,6 fps; render passes −38%, cópias de textura −74% |
| o ganho em aparelho **com** folga | Adreno 650: −1,67 ms guardados como folga, os dois braços já a 100% |
| o custo de fidelidade contra um **oráculo** | renderizador software (o `AutoFlushSW` é outro ajuste, então o SW é modelo exato): erro médio 9,436 contra 2,808 nos pixels disputados |
| **o que** degrada, descrito | a luz que o lampião projeta nas superfícies próximas; os cones de brilho ficam idênticos |
| por que é aceitável | erro limitado a 21–23/255, difuso, e quatro comparações lado a lado em 1:1 não distinguiram |
| todos os seriais, e o caminho de volta | sete seriais; *"revert to level 1 — not 2"* |

## Três razões para a resposta poder ser "não" — escritas antes, para ninguém torcer o resultado

1. **`1` é `SpritesOnly`, o nível mais leve.** O Rogue Galaxy partiu de `2`. Aqui só sprites
   texturizados que leem o próprio alvo quebram o lote (`GSState.cpp`, o filtro
   `prim != GS_SPRITE`). O ganho pode ser pequeno demais para justificar qualquer artefato.
2. **O artefato é de outra natureza.** Os comentários do GameDB são *"sun going through walls"* e
   *"sun occlusion"*: um objeto visível onde não devia estar. O argumento do Rogue Galaxy era
   *difuso e imperceptível*. Se o sol aparece através de parede, esse argumento não existe.
3. **O gargalo pode nem ser o GS.** Num aparelho com dois núcleos grandes a EE pode estar a 100%, e
   aí o `autoFlush` não move o fps. O `PerfLog` (`EE`/`GS`/`GPU`, corrigidos nas TASK-0055 e
   TASK-0060) decide.

**"Não" também fecha a task**, com o resultado registrado. O recurso do usuário continua sendo o
contorno por jogo descrito no bug.

## Escopo

**Entra:**

1. **Desempenho**, os dois jogos separadamente, em dois aparelhos (ver *Como implementar*, §2).
2. **Fidelidade**: localizar uma cena onde o fix do sol importa e comparar contra o software
   (§3). Os commits que introduziram o fix, `758c347258` e `4d43374b31` (2022), **não têm
   mensagem** — a cena não está escrita em lugar nenhum e localizá-la é parte do trabalho.
3. **Decidir** pelos critérios de *Como validar*, **por jogo**: God of War I e II têm fix, comentário
   e evidência próprios, e a resposta pode diferir.
4. Se aprovado: **o PR no `ARMSX2/ARMSX2`**, alterando o overlay para **todos** os seriais do jogo
   aprovado, no formato do `bf65e8604b`, com os números.
5. Registrar aqui o link do PR e, depois do merge lá e do nosso `git merge upstream/master`, a
   conferência de que o fix chegou ao APK.

**NÃO entra:**

- **Qualquer edição local** do `armsx2_overrides.yaml` ou do `GameIndex.yaml`. Se o upstream recusar
  ou demorar, isso volta ao dono do produto como decisão; não vira remendo aqui.
- **`mvuFlag: 0` do God of War II.** Corrige um defeito de jogabilidade (*"enemies attacks turning
  into squares"*), não é candidato a troca.
- **As recomendações de blending e AA1** do GoW II — são da
  [TASK-0092](TASK-0092-gamedb-e-precisao-ee-vu.md), e vão na direção **oposta** (fidelidade, com
  custo).

  > **Restrição explícita, e ela não é um conflito hoje.** O `a0d31aa9f4` entra como
  > *recomendação*: o OSD avisa, nada é forçado, e o critério 4b da TASK-0092 existe justamente
  > para provar que a imagem na configuração padrão não muda. Enquanto for assim, as duas tasks
  > convivem.
  >
  > O conflito nasceria se alguém ligasse **High blending + AA1 por padrão no mobile** — é
  > exatamente o oposto do que esta task persegue no mesmo jogo, que é tirar carga de GPU em
  > aparelho fraco. Quem propuser isso tem de ler as duas tasks antes, e trazer número.
  >
  > Alinhado com a sessão que conduz a FEAT-0003, em 2026-09-15.
- **O `DeviceTier`**, que não reconhece o POCO C75 como fraco (item 4 do bug). Defeito real, outra
  task.
- **Um mecanismo de overlay condicionado à GPU.** Não existe — o overlay entra no APK de todo
  aparelho (`build.gradle.kts:80`). Se os números mostrarem que a troca só compensa em aparelho
  fraco, isso é **achado para o dono do produto**, não coisa a construir aqui.
- **Um seletor "fidelidade × desempenho" no app.** Decisão de produto, proposta na auditoria de
  2026-08-10 e ainda não tomada.
- **A metade aberta** de [ajuste por jogo igual ao global](../bugs/open/armsx2-fork/ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md),
  que é o que faz o contorno óbvio falhar. Esta task a **contorna** no protocolo (§1); não a corrige.

## Como implementar

### 0. Pré-requisitos

- **O APK instalado é o que você acha que é.** Ver a memória do projeto sobre `compile*Kotlin` verde
  que não empacota; conferir pelo `sha256` do `base.apk`, como na TASK-0058.
- **Mesmo APK nos três braços.** O A/B é relativo. Build `debug` custa ~19% a mais de EE que o
  `release` (TASK-0058), então os números absolutos serão piso — registrar qual build foi usado.
- **Samsung: matar o GOS antes** e conferir o clock (protocolo do
  [backlog de clock cortado](../backlog/desempenho-com-clock-cortado-a55.md)). Senão mede-se a
  Samsung.
- **Descartar a primeira passagem** de cada braço: cache de shader.

### 1. Os três braços — e por que não basta desligar o Auto Flush

Desligar só o *Auto Flush* por jogo **não pina**: o default global é `0`, `writeGameSettingsIni`
grava só o que difere do global, e o GameDB reaplica `1`. O braço tem de passar por *Correções de
Hardware Manuais* — e isso também tira `halfPixelOffset`/`alignSprite`/`nativeScaling`, o que
contaminaria o A/B. Daí três braços, não dois:

| braço | ajuste por jogo | o que o GameDB aplica | prova no `emulog.txt` |
|---|---|---|---|
| **A** — padrão | nada | tudo, `autoFlush = 1` | nenhuma linha `Skipping` de `autoFlush` |
| **C** — manual, sprites | *Correções de Hardware Manuais* **on** + *Auto Flush* **Sprites** | nenhum user hack; `autoFlush = 1` vem do usuário | `Manual GS hardware renderer fixes are enabled…` e `Skipping GS Hardware Fix: halfPixelOffset…` |
| **B** — manual, off | *Correções de Hardware Manuais* **on** + *Auto Flush* **Off** | nenhum user hack; `autoFlush = 0` | as duas acima **e** `GameDB: Skipping GS Hardware Fix: autoFlush to [mode=1]` |

- **B contra C** isola o `autoFlush`. É a medida que importa.
- **A contra C** isola o resto do manual. Em 1x deve ser ~zero; se não for, o A/B está
  contaminado e a conclusão muda.
- Sem a linha de prova, **o braço não vale** — pin que não pegou mede a mesma coisa duas vezes.

Fazer os ajustes pelo **menu em jogo** (o caminho que sempre criou o INI) e reiniciar o jogo.

### 2. Desempenho

**Aparelhos:** o `SM-A127M` do laboratório (Exynos 850, **Mali-G52** — a mesma GPU do C75, com CPU
ainda mais fraca: oito A55) como braço **sem folga**; o `moto g86 5G` como braço **com folga** —
ler a GPU dele no próprio aparelho, não assumir.

> ⚠️ **No A12 a EE é o gargalo** (`EE 100%`, medido no backlog). Lá o `autoFlush` pode não mover o
> fps nenhum e ainda assim cortar tempo de GS e de GPU. **Registrar `GS`/`GPU` e ms/quadro, não só
> fps** — o Rogue Galaxy reportou exatamente esses ms "guardados como folga".

**Protocolo:** mesmo savestate, mesma cena pesada (exterior com o sol visível, para servir também
ao §3), 1x nativo, três repetições por braço, alternando a ordem dos braços.

```bash
adb shell "grep PerfLog /storage/emulated/0/Android/data/come.nanodata.armsx2/files/logs/emulog.txt | tail -20"
```

Se até lá um `git merge upstream/master` tiver trazido o `6532d77309` (*"make pcsx2-gsrunner
buildable as an NDK executable"* — está no `upstream/master`, **não** na nossa árvore em
2026-09-11), um GS dump da cena reproduzido no aparelho dá contagem determinística de render passes
e cópias de textura, como no Rogue Galaxy. Opcional; a medição em jogo basta para decidir.

### 3. Fidelidade

1. **Localizar a cena.** O sol visível com geometria na frente, em que o braço B mostre diferença.
   Se depois de uma busca razoável **nenhuma** cena mostrar diferença, registrar isso — é evidência
   a favor da troca, mas mais fraca que um erro medido.
2. **Capturar o mesmo quadro** nos braços B e C e com o renderizador **Software** por jogo
   (`autoFlushSw` tem default `true` na `Settings.kt`, então o SW é o oráculo do mesmo jeito que foi
   para o Rogue Galaxy). Registrar o método de captura usado; a comparação por pixel precisa das
   três imagens na mesma resolução.
3. **Medir**: erro médio contra o SW nos pixels em que B e C diferem, e o erro máximo.
4. **Descrever o que degrada**, em palavras, como o `bf65e8604b` fez.

### 4. Se aprovado: o PR

> 🔴 **Abrir o PR é ação externa e pública. Confirmar com o dono do produto antes**, com a tabela
> de resultado em mãos.

- Partir do `upstream/master` **do dia**, não da nossa árvore.
- O overlay **limpa e substitui** o bloco inteiro de cada campo. A entrada de cada serial tem de
  repetir **todos** os `gsHWFixes` que o GameDB traz naquele momento, mudando só o `autoFlush` — no
  GoW II isso inclui `recommendedBlendingLevel` e `recommendedHWAA1`. Esquecer um é o defeito que
  o comentário do Delta Force, no topo do próprio arquivo, documenta.
- `speedHacks` fica **fora** da entrada: nomeá-lo o colocaria sob a mesma regra e o `mvuFlag: 0`
  sumiria no mobile.
- Mensagem no formato do `bf65e8604b`: o relato, o ganho nos dois aparelhos, o erro contra o SW, o
  que degrada, por que é aceitável, a lista de seriais e o caminho de volta.

## Como validar

| # | critério | aprovado |
|---|---|---|
| 1 | cada braço tem a sua linha de prova no `emulog.txt` | todas presentes |
| 2 | A contra C | diferença dentro do ruído entre repetições; se não, parar e entender |
| 3 | B contra C, aparelho sem folga | ganho de ms/quadro (GS ou GPU) **maior que o dobro** da variação entre repetições |
| 4 | B contra C, aparelho com folga | nenhuma regressão |
| 5 | fidelidade | **nenhum objeto visível onde não devia** — o sol não aparece através de parede na cena localizada; erro contra o SW descrito e com número |

**PR só se 1–5 passarem, por jogo.** Se 3 falhar, a resposta é "não vale". Se 5 falhar, a resposta
é "não vale" mesmo com ganho grande — é o caso que a razão 2 antecipa.

## Antes de fechar

```bash
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

## Resultado

— (a preencher pela sessão que implementar: aparelhos e GPUs, build usado, savestate e cena, a
tabela dos três braços com fps/EE/GS/GPU/ms por repetição, a cena do sol e as imagens, o veredito
por jogo e, se houver, o link do PR)
