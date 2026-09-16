# TASK-0092: trazer as entradas de GameDB e a precisão de EE FPU / divisão da VU

- **Status:** em andamento
- **Criada em:** 2026-09-08
- **Concluída em:** —
- **Feature:** [FEAT-0003](../features/FEAT-0003-colheita-upstream-setembro-2026.md)
- **Bugs que resolve:** —
- **Commit:** — (o vínculo é o prefixo `TASK-0092:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

> **Bloco 3 de 5 da FEAT-0003.** Só começar depois que a
> [TASK-0091](TASK-0091-adotar-remocao-da-captura-de-video.md) estiver commitada e validada.

## Resumo em uma linha

Oito commits que corrigem travamento em Jak X, cores em Sly 3, texto esticado em Mortal Kombat:
Shaolin Monks e névoa/HUD em God of War II — quatro deles em GameDB, quatro na aritmética de EE FPU
e da unidade de divisão da VU.

## A armadilha desta task, e leia antes de qualquer coisa

**Dois dos commits de GameDB são revertidos por um terceiro, e a ordem importa.**

- `6baf7d138d` — *"Mortal Kombat - Shaolin Monks divides toward positive infinity"* (adiciona a
  entrada nas três versões: SLUS-21087, SLES-53524, SLES-53525)
- `d40b290604` — *"say what the Shaolin Monks division rounding fixes"* (ajusta o comentário)
- `7986c0bb4c` — *"drop the Shaolin Monks divide rounding mode"* — **reverte os dois acima**

Por quê: arredondar as divisões da EE para +∞ levava os quocientes de `log2` do jogo **por cima** do
inteiro sob o qual eles ficam a um ULP de distância, e era isso que dimensionava os painéis de
diálogo uma potência de dois pequenos demais. A correção **de verdade** é `efd403fb39` (o mode 3
roda a recorrência da própria unidade nessas linhas). Com ela, as três entradas ficam só com o clamp
completo de EE que já tinham.

**Consequência prática:** pegar `6baf7d138d` sem `7986c0bb4c` introduz um defeito que o upstream já
removeu. Ou se pega o trio inteiro (e o resultado líquido em GameDB para Shaolin Monks é **zero**),
ou não se pega nenhum dos três — **mas então `efd403fb39` continua sendo o que corrige o jogo**.

**Recomendação: pular os três.** O resultado é idêntico e o histórico fica mais legível.

## Os commits

### GameDB — `bin/resources/GameIndex.yaml`

| Commit | O que faz | Linhas |
|---|---|---|
| **`b69fbed079`** | **Jak X: trava para sempre em "Saving Data"** | +34 |
| **`a0d31aa9f4`** | God of War II recomenda blending High e AA1 | +22 |
| **`181ccef45c`** | Entradas faltantes: S.L.A.I. e Syphon Filter: Logan's Shadow | +6 |
| ~~`6baf7d138d`~~ + ~~`d40b290604`~~ + ~~`7986c0bb4c`~~ | Shaolin Monks — **soma zero, pular** | 0 |

**Jak X** vale o detalhe, porque é o defeito mais visível da lista. O emulador está correto o tempo
todo: as duas CPUs em suas threads ociosas de kernel, SIO2 parado com FIFOs vazias, e a escrita do
cartão **já concluída com sucesso**. Quem para é o jogo. O gerenciador de menu do lobby cria um
filho de auto-save; o filho escreve, avisa o pai e sai. Mas o handler do lobby só avança se nenhum
filho estiver ocupado — e o base-menu responde "ocupado" incondicionalmente enquanto anima. No
caminho de **sucesso** o lobby não registra nada, então um sucesso que chegue dentro dessa janela é
**descartado**, e não pode vir um segundo aviso porque o processo de auto-save já morreu. Só o
caminho de **falha** guarda o byte de retry em `+0x180`, que a máquina de estados repete a cada
quadro até pegar. **Sucesso é dispara-uma-vez, falha é repetida — essa assimetria é o bug inteiro.**

**God of War II** são **recomendações, não imposições**: o OSD avisa o jogador e nada é forçado, de
modo que a configuração padrão renderiza exatamente como antes. O desenho de névoa de tela cheia
mistura por alpha de destino (A=Cs B=Cd C=Ad D=Cd); o GS escala Ad por 1/128 e a unidade de blend do
hardware por 1/255, então abaixo de High o caminho de hardware aplica **metade** da névoa. Dígitos
de HUD e avisos de menu são desenhados com AA1 e perdem a linha de borda meio-coberta quando ele não
é emulado.

### Precisão — EE FPU e unidade de divisão da VU

| Commit | O que faz | Arquivos | Linhas |
|---|---|---|---|
| **`813ab87460`** | A unidade de divisão da VU passa a **condicionar o teto do console ao clamp mode** | `iCOP2-arm64.cpp`, `microVU-arm64.h`, `microVU_Lower-arm64.inl` + 9 de teste | +274 −72 |
| **`efd403fb39`** | EE FPU: **unidade de divisão** no mode 3, onde o ULP alcança um inteiro | `iFPUd-arm64.cpp` + teste | +360 −8 |
| **`c4bc7d5935`** | EE FPU: **raiz e raiz recíproca** no mode 3, mesma condição | `iFPUd-arm64.cpp` + teste | +467 −5 |
| **`1ac2c52a07`** | EE rec: mover as ilhas de guarda da FPU para a **arena fria** (desempenho) | `iFPUd-arm64.cpp`, `iR5900-arm64.{cpp,h}` + teste | +113 −12 |

**`813ab87460` conserta Sly 3.** Dois commits anteriores deles (`f1a5733b50`, `e2b541b7d8`) tinham
dado a `recCOP2_VDIV`/`VRSQRT` e a `mVU_DIV`/`mVU_RSQRT` o `0x7FFFFFFF` que o console deixa num
divisor zero — **em todo clamp mode**. Sly 3 divide por zero **por vértice** na VU1, e a entrada de
GameDB dele deixa o clamp no padrão: o protagonista era desenhado em preto e branco. Agora o
microVU monta a palavra do console a partir de `vuClampMode 3`, o caminho macro fica em
`vuClampMode 4`, e `mVU_DIV`/`mVU_RSQRT` voltam a carregar o par signbit/maxvals nos modes 0–2.

> ⚠️ **`813ab87460` move a versão da ABI do microVU.** Isso invalida cache de programa do microVU.
> Não é problema — é o mecanismo funcionando — mas explica qualquer primeira execução mais lenta
> depois de instalar.

## O que já foi verificado nesta árvore (2026-09-08)

| Verificação | Resultado |
|---|---|
| Todos os arquivos que os 4 commits de precisão tocam existem aqui | ✅ (o único ausente, `vu_saturated_q_consumer_tests.cpp`, é **criado** pelo commit) |
| `pcsx2/arm64/` está no nosso delta? | ❌ **Não.** Nenhum conflito esperado nesses quatro. |
| `bin/resources/GameIndex.yaml` está no nosso delta? | ❌ Não. |
| `pcsx2/GameDatabase.cpp` está no nosso delta? | ⚠️ **Sim** — mas nenhum destes oito commits o toca. |

**Esta é a task de menor risco de conflito das cinco.**

## Escopo

**Entra:**

1. `git cherry-pick b69fbed079 a0d31aa9f4 181ccef45c` — as três entradas de GameDB que valem.
2. `git cherry-pick 813ab87460 efd403fb39 c4bc7d5935 1ac2c52a07` — **nesta ordem**, que é a
   cronológica; `1ac2c52a07` depende das ilhas que os dois anteriores criam.
3. Os arquivos de teste que vêm junto. **Não descartar os testes** para "encurtar o patch": eles são
   a única coisa nesta task que verifica aritmética, e o resto da validação é visual.

**NÃO entra:**

- **Os três commits de Shaolin Monks** (`6baf7d138d`, `d40b290604`, `7986c0bb4c`). Somam zero. O
  jogo é corrigido por `efd403fb39`, que **entra**.
- Alterar a nossa `GameDatabase.cpp` — os oito commits não a tocam, e o nosso delta ali é de outra
  task.
- Qualquer outro commit de GameDB do upstream não listado aqui.
- Os demais blocos da FEAT-0003.

## Como implementar

### 1. Estado da árvore

Confirme que a TASK-0091 está commitada e que não há alteração pendente que não seja sua. Commite
por caminho explícito, nunca `git add -A`. **Não use `git stash` puro** — a pilha é compartilhada
entre worktrees.

### 2. Aplicar

```bash
git fetch upstream --prune
git cherry-pick b69fbed079 a0d31aa9f4 181ccef45c
git cherry-pick 813ab87460 efd403fb39 c4bc7d5935 1ac2c52a07
```

Se algum conflitar, é porque a árvore andou — resolva mantendo a lógica deles.

### 3. Compilar

O lado C++ mudou em `pcsx2/arm64/`, que é código de recompilador. Objetos isolados servem para o
"compila?":

```bash
cd platforms/android/app/.cxx/Debug/<hash>/arm64-v8a
"D:/DevCaches/Android/Sdk/cmake/3.31.6/bin/ninja.exe" -j 4 <alvos de iFPUd/iCOP2/microVU>
```

Use `ninja -t targets all | grep -E "iFPUd|iCOP2|microVU|iR5900"` para achar os nomes exatos.
`-j 4` obrigatório.

Depois, o build completo (ver TASK-0091, seção 3) antes de gerar APK.

### 4. A questão dos testes de ctest — leia antes de prometer

Os quatro commits de precisão trazem **~1.200 linhas de teste** em `tests/ctest/core/recompilers/`.
Esse harness é diferencial: carrega um programa MIPS curto, roda pelo JIT **e** pelo interpretador, e
faz gtest-diff do estado arquitetural inteiro.

**Não havia árvore de build de teste configurada nesta máquina em 2026-09-08** (`CMakePresets.json`
tem presets `clang-*`, nenhum configurado). E há uma dificuldade real: o código sob teste é o
recompilador **ARM64**, então rodá-lo exige um host ARM64 — ou rodar o binário de teste **no
aparelho**, que é justamente o que o upstream passou a fazer (`6532d77309` torna o `pcsx2-gsrunner`
construível como executável NDK).

**O que fazer:**

1. **Tentar** configurar e rodar: `cmake --preset clang-debug` e `ctest`. Se funcionar, ótimo — é a
   validação mais forte que esta task pode ter.
2. **Se não funcionar**, **não invente que rodou.** Registre no Resultado o que foi tentado e qual
   foi a barreira, e apoie a validação nos critérios de jogo abaixo. Uma task que diz "testes
   verdes" sem os ter rodado é pior que uma que admite não ter conseguido.

### 5. APK e aparelho

```bash
cd platforms/android
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:installGithubDebug \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

Confirmar que o APK instalado é o novo (procedimento na TASK-0090, seção 4).

## Como validar

Esta task se valida **em jogos concretos**. Cada critério nomeia o jogo, a cena e o que olhar.

### Critério 1 — Jak X (`SCUS-97429` / equivalente PAL)

O teste é: **salvar o perfil e sair da tela de "Saving Data"**.

1. Entrar no jogo, chegar ao lobby, disparar um auto-save de perfil.
2. Observar a tela *"Saving Data / please do not remove the memory card"*.

**Aprovado:** a tela sai sozinha e o jogo continua. **Reprovado:** trava para sempre.

> Repetir **pelo menos 5 vezes**. O defeito é uma corrida — o sucesso só se perde se chegar
> enquanto o base-menu anima. Uma única passagem não prova nada.

### Critério 2 — Sly 3 (`SCUS-97464` / equivalente)

Entrar numa cena com o protagonista visível.

**Aprovado:** o personagem é renderizado **em cores**. **Reprovado:** preto e branco.

> Este é o critério de `813ab87460`. Vale conferir também que o `vuClampMode` do jogo continua no
> **padrão** — a correção existe justamente para o caso em que a entrada de GameDB não força clamp.

### Critério 3 — Mortal Kombat: Shaolin Monks (`SLUS-21087`)

Ir à **tela de menu com texto**.

**Aprovado:** o texto e os painéis de diálogo têm o tamanho certo. **Reprovado:** texto esticado /
painéis uma potência de dois pequenos demais.

> Este é o critério de `efd403fb39`, e é a razão de os três commits de GameDB de Shaolin Monks terem
> sido descartados. Se este critério **falhar**, a conclusão **não** é "então traga os commits de
> GameDB de volta" — é que `efd403fb39` não foi aplicado corretamente. Investigar, não remendar.

### Critério 4 — God of War II (`SLUS-21288`)

1. **Aprovado (4a):** o OSD **avisa** as recomendações (High blending, AA1) ao dar boot.
2. **Aprovado (4b):** com a configuração **padrão**, a imagem é **idêntica à de antes** — são
   recomendações, nada é forçado. Comparar screenshot antes/depois da mesma cena.
3. **Aprovado (4c):** ligando High blending + AA1 à mão, a névoa de tela cheia fica com intensidade
   plena e os dígitos do HUD ganham de volta a linha de borda.

### Critério 5 — não regredir o que já funcionava

A aritmética de FPU e da VU é usada por **todo** jogo. Rodar **três jogos que hoje funcionam bem**,
5 minutos cada, e confirmar que nada mudou visualmente nem em desempenho. Escolher jogos que usem
VU pesadamente (God of War, Shadow of the Colossus) — e **registrar quais foram**, para a próxima
sessão poder repetir o mesmo conjunto.

### Critério 6 — desempenho não regrediu

`1ac2c52a07` move as ilhas de guarda da FPU para a arena fria, o que **deveria** melhorar. Medir fps
médio num jogo pesado, antes e depois, mesmo save state e mesma cena.

> Lembrar: `813ab87460` **move a versão da ABI do microVU**, então a primeira execução depois de
> instalar recompila o cache. Descartar a primeira passagem da medição.

## Antes de fechar

```bash
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

Preencher `## Resultado` com: o veredito de cada um dos seis critérios, **com o nome e o serial dos
jogos usados**, quantas repetições no Jak X, os números de fps do critério 6, e — se os ctest não
rodaram — exatamente o que impediu.

## Resultado

> **Código commitado e três dos seis critérios provados no aparelho. A task NÃO fecha:** o critério
> 1 tem 3 das 5 repetições pedidas, o 4c não foi medido, e o 6 perdeu o par de comparação e depende
> de decisão do usuário. O que falta está dito abaixo, com o custo de cada parte.

**Aparelho:** SM-A127M (`RX8R90G1D6E`), Android 13, Helio P35. **Build medido:** o APK instalado em
2026-09-11 22:23 (versionCode 2005), que é **pós-TASK-0092 e pré-TASK-0093** — provado assim: o
`assets/resources/GameIndex.yaml` extraído do `base.apk` contém `3091E6FB` (o patch do Jak X), que
**não existe** na versão anterior à task, e `UsbRumble` está **ausente** dos `classes*.dex`, ou seja,
o trabalho de controle do Bloco 4 não está nele. É Bloco 3 puro, que é o que se queria medir.

### Veredito por critério

| Critério | Veredito | Evidência |
|---|---|---|
| **1** — Jak X salva sem travar | ⚠️ **3 de 5** repetições, **nenhum travamento** | abaixo |
| **2** — Sly 3 em cores | ✅ **passou** | abaixo |
| **3** — Shaolin Monks com texto correto | ✅ **passou** | abaixo |
| **4a** — OSD avisa as recomendações | ❌ **critério inválido para o nosso fork** | abaixo — não é defeito |
| **4b** — imagem padrão inalterada | ✅ **provado pelo código**, mais forte que screenshot | abaixo |
| **4c** — High blending + AA1 à mão | ⛔ **não medido** | custo alto, valor baixo depois do 4b |
| **5** — não regressão | ⚠️ **parcial** | 4 jogos rodados, nenhum crash |
| **6** — desempenho antes/depois | ⛔ **sem par** | decisão pendente do usuário |

### Critério 1 — Jak X (`SCUS-97429`, CRC `3091E6FB`)

**A pré-condição foi provada antes do teste**, e ela era o risco real da task: o patch é chaveado por
CRC, e a cópia disponível é a v2.00. O log do boot resolveu a dúvida:

```
Serial: SCUS-97429
CRC: 3091E6FB
Found 1 game patches in GameDB.
Enabled patch
```

**A ROM bate com a chave e o patch carregou.** Sem isso, um travamento seria ambíguo entre "a
correção falhou" e "o patch nem se aplica a esta cópia".

Três salvamentos de perfil observados um a um, cada um terminando com o **retorno ao menu**:

1. perfil novo `Aa` criado e salvo num slot vazio → aviso de salvamento → Continue → menu principal;
2. sobrescrita do mesmo `Aa` (o slot apareceu na lista, provando que o passe 1 persistiu) → menu;
3. nova sobrescrita → menu.

**O log de gravação do cartão não vale como prova sozinho**, e isto está registrado de propósito: o
próprio relatório do upstream diz que a escrita conclui **mesmo quando o jogo trava**. A prova é a
tela voltar ao menu, e por isso cada passe terminou numa captura.

**O que estes três passes provam, e o que não provam:** provam que **com** o patch o jogo não trava.
**Não** provam que travaria sem ele neste aparelho — não existe build "antes" para comparar, e a
causalidade vem da análise do upstream, não desta medição.

**Por que parou em 3:** o roteiro automatizado dessincronizou. Depois do retorno ao menu o jogo
**reposiciona o cursor em "Adventure"**, não em "Profile", e o passe seguinte entrou no modo aventura
em vez de salvar. Não foi travamento — foi comportamento do jogo que o roteiro não previa.

**Custo de completar:** o jogo roda a **9–15 % da velocidade** neste aparelho, e a abertura leva ~13
minutos. Refazer do zero é caro; o caminho barato é um **save state no menu principal**, e então cada
repetição custa ~3 minutos.

### Critério 2 — Sly 3 (`SCUS-97464`, CRC `8BC95883`)

**A condição do critério foi conferida no GameDB antes:** a entrada força `vu0ClampMode: 3` e
**deixa a VU1 no padrão** — exatamente o que o commit `ad9f54ae0f` descreve, já que a divisão por
zero acontece **por vértice na VU1**.

O protagonista aparece **totalmente colorido** em quatro capturas do binocucom (boné azul, máscara,
pelagem). E os retratos **mudam de pose entre capturas** — cabeça virada, boca aberta —, o que prova
que são **modelo 3D em tempo real**, não textura estática; é onde o defeito de preto e branco
apareceria. `PerfLog` confirma a VU ativa (`VU 24–31 %`).

**O que não foi feito:** não cheguei ao modelo no mundo aberto. O diálogo de abertura é longo a 7–8 %
da velocidade. O veredito se apoia no modelo 3D do retrato, e isso está dito em vez de escondido.

### Critério 3 — Mortal Kombat: Shaolin Monks (`SLUS-21087`, CRC `455DD546`)

**A entrada do GameDB está no estado que o upstream deixou:** `eeClampMode: 3` (clamp completo de EE)
e **nenhum modo de arredondamento de divisão** — prova de que os três commits que se cancelam
(`6baf7d138d`, `d40b290604`, `7986c0bb4c`) ficaram corretamente fora, como a task mandava.

A tela de menu do jogo renderiza **correta**: o painel *Character Stats* com as duas colunas
alinhadas, o mapa dentro da moldura, o cabeçalho *Main / Moves* e a barra *Toggle Map / Exit /
Navigate*. **Nada de texto esticado nem de painel dimensionado uma potência de dois pequeno demais**
— que é o sintoma que `efd403fb39` corrige.

### Critério 4 — God of War II (`SCUS-97481`, CRC `2F123FD8`)

As duas entradas de `3302dfed36` **são lidas e aplicadas**:

```
GameDB: Enabled GS Hardware Fix: recommendedBlendingLevel to [mode=3]
GameDB: Enabled GS Hardware Fix: recommendedHWAA1 to [mode=1]
```

**O critério 4a, como foi escrito, não vale para o nosso fork — e isso não é defeito.** Nenhum aviso
de OSD sobre as recomendações aparece, e **não deve mesmo aparecer**: a
[TASK-0043](TASK-0043-aviso-anti-revenda-do-upstream.md) removeu esses avisos de propósito. O código
prova: `GameDatabase.cpp:1094-1117`, os `case RecommendedBlendingLevel`, `RecommendedAccurateAlphaTest`
e `RecommendedHWAA1` **só chamam `Host::RemoveKeyedOSDMessage(...)`**. O comentário de lá diz o
porquê — os avisos disparavam numa **instalação limpa**, sem ação do usuário, porque o padrão móvel é
menor que o recomendado no GameDB, e todo jogo afetado abria com uma tarja explicando uma
configuração que o usuário não mexeu.

**Eu escrevi o 4a a partir da mensagem do commit deles ("the OSD tells the player") sem conferir o
nosso delta.** É a regra do `CLAUDE.md` outra vez: provar pelo call-site, não pelo nome.

**O critério 4b fica provado pelo código, o que é mais forte que comparar screenshots:** aqueles
`case`s **não escrevem em `config`**. Logo a recomendação **não pode** alterar a imagem no padrão —
não por observação, mas por construção.

**4c não foi medido.** Ligar High blending + AA1 à mão exige achar o AA1 nas Configurações completas,
na seção recolhida "Blending e Avançado", e **reiniciar o jogo** — duas execuções a 8 % da
velocidade. Depois do 4b, o valor informativo é baixo: sabe-se que a entrada não força nada.

### Critério 5 — não regressão (parcial)

Quatro jogos booted e rodados nesta sessão — **Jak X**, **Sly 3**, **Shaolin Monks** e **God of War
II** —, somando mais de uma hora de execução. **Nenhum crash, nenhuma anomalia visual observada.** O
que a task pedia era "três jogos, 5 minutos cada, com atenção"; o que houve foi observação enquanto
se navegava para outros critérios. Conta como evidência, não como o protocolo escrito.

### Critério 6 — desempenho: o par não existe mais

O "antes" precisaria de um APK **sem** a TASK-0092, e o que está no aparelho **já a tem**. Construir
um exigiria uma worktree separada em `ebe4a88253`, e a `CLAUDE.md` avisa que uma worktree nova **não
vem com as dependências do shaderc** (7 pastas, 264 MB, `gitignore`d). **Não fiz isso por conta
própria; é decisão do usuário** se o número vale o custo.

### Um incidente de método, e a correção

Durante o critério 2, um lote de toques automatizados continuou enviando eventos **depois que o
emulador saiu do primeiro plano**, e uma captura registrou a tela do aplicativo de Mensagens do
usuário. **A captura foi apagada imediatamente e não foi transmitida a lugar nenhum**, e o usuário
foi avisado para conferir se ficou rascunho no compositor de SMS.

**A causa é o método, não o aparelho:** `adb shell input tap` entrega o evento a quem estiver em
foco. A correção, aplicada no resto da sessão: **cada lote confere o app em foco antes de cada
toque** (`dumpsys window | grep mCurrentFocus`) e aborta se não for `come.nanodata.armsx2`. A regra
entrou no [`AGENTS.md`](../../AGENTS.md).

Um segundo detalhe do mesmo tipo: a 8–15 % da velocidade, **um `input tap` curto se perde entre
quadros** (o quadro dura ~116 ms). Os toques passaram a ser `input swipe x y x y 450`, que segura o
botão tempo suficiente.

### O que falta para fechar

1. **Critério 1**: 2 repetições, via save state no menu principal (~3 min cada).
2. **Critério 4c**: dois boots do God of War II (~20 min), valor baixo.
3. **Critério 6**: decisão do usuário sobre construir o APK "antes".
4. **Critério 5**: se o protocolo escrito for exigido à risca, 3 jogos × 5 min com atenção.
