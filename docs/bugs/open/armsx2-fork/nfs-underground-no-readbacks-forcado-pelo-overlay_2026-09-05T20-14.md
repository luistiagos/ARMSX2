# Bug: NFS Underground 1 e 2 nascem com "No Readbacks" forçado pelo nosso overlay

- **Detectado em:** 2026-09-05 20:14 (relato de cliente, Motorola G06)
- **Origem:** **delta do fork** — a chave não existe no upstream, é nossa
- **Errors (serviço):** nenhum — não é crash, não gera telemetria
- **Classe:** fail (corrupção de imagem)
- **Reincidência:** não
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda; ver *Próximos passos*
- **Relacionado:** [ajuste por jogo não chega à camada lida pelo core](ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md)

> ⚠️ **Leia primeiro a seção *Medição em aparelho* no fim.** A hipótese que dá nome a este
> relatório — o nosso `hwDownloadMode: 2` como causa da corrupção — **foi testada em aparelho e não
> se sustenta como causa suficiente**: com a chave ligada, no mesmo jogo e na mesma cópia, a imagem
> sai correta. O que está descrito abaixo continua verdadeiro como *mecanismo* e como delta nosso
> sobre o upstream; o que caiu foi a explicação do sintoma do cliente.

## Sintoma relatado

Motorola G06. *Need for Speed Underground 2* desenha o menu principal por cima de uma cena 3D
**corrompida**: geometria cisalhada, cena em tons de cinza onde deveria haver cor, e um
quadrilátero grande com padrão pontilhado cobrindo parte da tela. A camada de HUD/menu (logotipo,
"Main Menu", "Career") renderiza **correta**. O cliente relata o mesmo tipo de defeito no *Need for
Speed Underground* (o primeiro).

O cliente diz ter trocado o renderizador entre OpenGL, Vulkan e software, "e não resolveu, às vezes
até piorou".

Um segundo jogo, *Jackass*, **não** apresenta esse defeito mas trava na tela de loading. Ver
*O que este relatório não explica*.

## O que está verificado no código

Tudo nesta seção foi lido no código desta árvore. A medição em aparelho veio depois e está no
fim do arquivo — ela **confirma** que a chave está instalada e ativa, e **derruba** a conclusão de
que ela explica o sintoma.

### 1. Somos nós que ligamos "No Readbacks" nesses dois jogos

[`bin/resources-overlay/armsx2_overrides.yaml`](../../../../bin/resources-overlay/armsx2_overrides.yaml):

```yaml
SLUS-20811:                    # NFS Underground (NTSC-U)
  gsHWFixes:
    hwDownloadMode: 2 # No Readbacks — NFS Underground perf (community request)
SLUS-21065:                    # NFS Underground 2 (NTSC-U)
  gsHWFixes:
    hwDownloadMode: 2 # No Readbacks — NFS Underground 2 perf (community request)
```

O upstream **não** pede isso. As entradas correspondentes em `bin/resources/GameIndex.yaml`
(`:68509` e `:70076`) trazem apenas `halfPixelOffset`, `nativeScaling`, `drawBuffering`,
`recommendedBlendingLevel` — todas cosméticas de upscaling — e, no caso do NFSU1, o game fix
`EETimingHack`. O `hwDownloadMode` é **delta nosso**, adicionado por desempenho.

Duas verificações que poderiam ter derrubado isto e **não** derrubaram:

- **O valor 2 é mesmo `NoReadbacks`.** [`Config.h:437`](../../../../pcsx2/Config.h#L437) —
  `Enabled, EnabledForceFull, NoReadbacks, …`. O comentário do overlay está correto. Valia
  conferir: o próprio enum avisa que **deixou de ser ordenado**, porque `Asynchronous` foi
  acrescentado no fim por compatibilidade de fio.
- **O `EETimingHack` do NFSU1 não foi derrubado pelo overlay.** Campo ausente no override é
  herdado: o `clear()` de `gameFixes` está guardado por `if (node.has_child("gameFixes") && …)`
  ([GameDatabase.cpp:204](../../../../pcsx2/GameDatabase.cpp#L204)).

### 2. É o default de fábrica, não uma escolha do usuário

`applyGSHardwareFixes` só aplica o `hwDownloadMode` do banco quando o valor corrente ainda é o
default ([GameDatabase.cpp:918](../../../../pcsx2/GameDatabase.cpp#L918)):

```cpp
if (config.HWDownloadMode == GSHardwareDownloadMode::Enabled && …)
    config.HWDownloadMode = static_cast<GSHardwareDownloadMode>(value);
```

O default do app é `hardwareDownloadMode: Int = 0`
([Settings.kt:594](../../../../platforms/android/app/src/main/java/com/armsx2/config/Settings.kt#L594)),
que é `Enabled`. Logo, **numa instalação limpa a condição é verdadeira e o modo vira `NoReadbacks`**.

### 3. O que "No Readbacks" desliga

`IsHardwareDownloadReadbackEnabled(NoReadbacks)` é **falso**
([Config.h:452](../../../../pcsx2/Config.h#L452)), e é esse predicado que guarda a cópia GPU→CPU do
render target de volta para a memória local do PS2
([GSTextureCache.cpp:5131](../../../../pcsx2/GS/Renderers/HW/GSTextureCache.cpp#L5131) e
[:5401](../../../../pcsx2/GS/Renderers/HW/GSTextureCache.cpp#L5401)). Com a cópia suprimida, tudo
que o jogo desenha na GPU e depois **relê** pela EE encontra memória local velha.

Isso é coerente com o sintoma em dois pontos: a corrupção é da **cena**, não da camada de HUD (que
é desenhada e nunca relida), e é **igual em OpenGL e em Vulkan**, porque a decisão é do GS
independentemente do backend.

### 4. As cópias PAL dos mesmos dois jogos NÃO recebem a chave

| serial | jogo | região | `hwDownloadMode` no nosso overlay |
|---|---|---|---|
| `SLUS-20811` | NFS Underground | NTSC-U | **2 (No Readbacks)** |
| `SLES-51967` | NFS Underground | PAL | — ausente |
| `SLUS-21065` | NFS Underground 2 | NTSC-U | **2 (No Readbacks)** |
| `SLES-52725` | NFS Underground 2 | PAL | — ausente |

A assimetria não tem justificativa escrita em lugar nenhum. Ela é útil: dá um A/B **de graça**, e
torna a região da cópia do cliente um dado necessário antes de qualquer conclusão.

### 5. Sob o renderizador por software a chave não vale

`is_sw_renderer` em `applyGSHardwareFixes` é usado **apenas** para silenciar o aviso na tela
([GameDatabase.cpp:851](../../../../pcsx2/GameDatabase.cpp#L851) e
[:1163](../../../../pcsx2/GameDatabase.cpp#L1163)); os consumidores do `HWDownloadMode` estão todos
sob `UseHardwareRenderer()`.

**Consequência direta, e é o teste que decide este relatório:** se a causa for o `NoReadbacks`, o
renderizador por software tem de renderizar o NFSU2 **correto** (lento, mas correto). O cliente diz
que software também falhou — então ou a troca para software não chegou ao core (há precedente nesta
árvore: a chave de ANGLE ficou inerte por duas causas distintas, corrigidas na
[TASK-0083](../../../task/TASK-0083-escolha-de-angle-por-jogo-chega-ao-core.md)), ou há uma segunda
causa. **Enquanto isso não for medido, este relatório é hipótese com mecanismo, não causa fechada.**

## O usuário não consegue desligar isto pelo caminho certo

O mecanismo de "o que o jogador escolheu vence o banco" é o *pin*: o que conta é a **presença da
chave** na camada de jogo (`ComputePerGameOverrides`,
[PerGameOverrides.cpp:228](../../../../pcsx2/PerGameOverrides.cpp#L228)), e `HWDownloadMode` está na
tabela `s_gs_keys`. Mas `writeGameSettingsIni` grava **só as chaves que diferem do global**, e o
global é 0 = Accurate — que é exatamente o valor que o usuário escolheria para desfazer. Nada é
gravado, nada é pinado, o banco reaplica `NoReadbacks` no boot seguinte.

Está em relatório próprio, porque o defeito é mais amplo que o NFS:
[ajuste por jogo igual ao global não vence o GameDB](ajuste-por-jogo-igual-ao-global-nao-vence-o-gamedb_2026-09-05T20-14.md).

**O que sobra hoje como contorno** é o master global de correções manuais (`EmuCore/GS/UserHacks`,
`manualUserHacks` na UI): `isUserHackHWFix(HWDownloadMode)` cai no `default: return true`
([GameDatabase.cpp:465](../../../../pcsx2/GameDatabase.cpp#L465)), então ligá-lo pula a chave. O
custo é grosseiro: desliga **todas** as correções automáticas do GameDB, para **todos** os jogos —
inclusive o `halfPixelOffset`/`nativeScaling` que o próprio NFSU2 quer.

## O que este relatório não explica

- **O travamento do Jackass no loading.** Não há entrada nossa no overlay para `SLUS-21627`,
  `SLES-54663` nem `SLES-55081` — verificado. O upstream dá só `halfPixelOffset: 1`. Nada aqui o
  alcança: é outra causa, sem evidência ainda, e provavelmente merece relatório próprio depois de
  uma primeira medição.
- **O relato de que o software também falha.** Ver item 5.
- **O GPU/driver do Motorola G06.** Não confirmado. O aparelho **não estava conectado** a esta
  máquina durante a análise (`adb devices` vazio; nenhum dispositivo Android entre os 178 do USB).

## Próximos passos, na ordem de custo

Nenhum exige compilar. Os três primeiros são configuração no aparelho.

1. **Identificar o serial da cópia do cliente.** Se for PAL (`SLES-52725`), este relatório **não**
   se aplica e a causa é outra. Se for `SLUS-21065`, o mecanismo está ligado.
2. **Ligar o master de correções manuais** (`manualUserHacks`) e reabrir o NFSU2 em OpenGL. Se a
   corrupção sumir, a causa é uma correção do GameDB — e, dada a tabela do item 1, o candidato é o
   `hwDownloadMode`. É uma resposta binária e barata.
3. **Renderizador por software, confirmado no log**, não só selecionado na UI. É o discriminador do
   item 5: imagem correta em software ⇒ a causa está no caminho de hardware; imagem corrompida em
   software ⇒ o `NoReadbacks` não é a causa e este relatório cai.
4. **A/B de região**, se houver as duas cópias: NTSC-U contra PAL no mesmo aparelho, mesmo
   renderizador.
5. Só então decidir o destino da chave. **Não** retirar `hwDownloadMode: 2` do overlay antes disso:
   ela entrou por desempenho a pedido da comunidade, e tirá-la sem medida troca um relato por outro
   — que é exatamente o ciclo descrito em
   [`plano-grafico-mali-convergencia-upstream.md`](../../../plano-grafico-mali-convergencia-upstream.md).
   Se ela for a causa, a correção provavelmente é **por região/jogo com medida**, mais o conserto do
   pin (relatório ligado), para que o usuário possa decidir sozinho.

## Nota sobre o upstream

Esta árvore está **11 commits atrás** de `upstream/master` (`5fd85d7fc9`, 2026-09-02). Um deles é
`5fd85d7fc9 GS/HW: the blend-mix factor substitution has to read FBA from the context` — correção
de **blending** no GS/HW. Não foi avaliado se alcança este sintoma, mas é a primeira coisa a checar
antes de escrever qualquer correção de blending nossa, pela regra do `CLAUDE.md`.

---

## Medição em aparelho — 2026-09-05 21:17 a 21:40

**A hipótese central deste relatório NÃO se sustentou como causa suficiente.**

### O braço executado

| item | valor |
|---|---|
| aparelho | `moto g86 5G` (`ZY32LMNN9B`), SoC **MT6878**, Android **16** (SDK 36) |
| APK | `come.nanodata.armsx2` versionCode **2004**, versionName 2.0.4, instalado em 2026-09-03 19:22 |
| cópia | `Need for Speed - Underground 2 (USA).chd`, **`SLUS-21065` · CRC `F5C7B45F`** — lido do próprio app |
| configuração | `renderer=opengl`, `upscaleFloat=1`, `hardwareDownloadMode=0`, `manualUserHacks=false`, `useAngleOpenGL=false` — lidos de `armsx2-settings.json` **no aparelho** |
| overlay | `resources/armsx2_overrides.yaml` no aparelho, 44 785 bytes, **com `hwDownloadMode: 2` nas duas entradas de NFS** — lido no aparelho |

Ou seja: as duas pré-condições do §2 estão satisfeitas no aparelho, e a entrada do overlay que
força `NoReadbacks` está instalada e é a do serial exato da cópia.

### O resultado

**O jogo renderiza correto.** A captura do *Main Menu* — a mesma tela da imagem do cliente, com
"No Profile" e "Help" no rodapé — mostra o carro verde, o beco, as texturas e as cores todas
certas. Nenhum traço da corrupção relatada: nem tons de cinza, nem geometria cisalhada, nem o
quadrilátero pontilhado.

**Conclusão: `hwDownloadMode: 2` não é causa suficiente da corrupção.** Ele está ligado neste
aparelho, no mesmo jogo, na mesma cópia, e a imagem está correta.

Isso **não** absolve a chave por completo — ela pode ser um fator que só se manifesta no driver do
G06 —, mas derruba a explicação simples, e move o discriminador para o **aparelho**, não para o
banco de dados.

### O que a medição também estabeleceu

1. **O app faz *force refresh* do overlay a cada start.** Uma edição injetada em
   `resources/armsx2_overrides.yaml` foi apagada no start seguinte. O arquivo que o core lê é
   sempre o do APK — o comentário do `copyFile` está correto e o caminho está vivo.
2. **O `catalog.versions.subtitle` cru na tela do seletor de versões é do APK velho, não da
   árvore.** A chave existe hoje; a correção é da [TASK-0081](../../../task/TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md),
   commitada em 2026-09-03 22:11, e o APK do aparelho é de 19:22 do mesmo dia. Não abrir relatório.
3. **Não foi possível confirmar por log que o GameDB aplicou a chave.** O console do core sai em
   `logcat` sob a tag `STDOUT`, mas para logo após o dump de `EmuFolders`; não há `emulog.txt` em
   `logs/`. Uma sonda `skipDraw` injetada no overlay ficou **inconclusiva**, porque o GameDB é
   carregado uma vez (`std::call_once`) e o app já lê `resources/` no arranque, então a tabela
   provavelmente já estava em memória. Fica como lacuna: a cadeia está provada até "o arquivo certo,
   com a chave certa, está no disco que o core lê", e não além.

### O que isto muda nos próximos passos

Os passos 2 e 4 do plano original perderam a urgência: o A/B de contorno não tem o que mostrar num
aparelho onde a imagem já está certa. O que decide agora é **caracterizar o G06**:

1. **Modelo de GPU e versão do driver do G06**, e a **versão do app** que o cliente usa — se for uma
   release publicada e não a 2.0.4, o overlay e os defaults podem ser outros.
2. **Repetir esta mesma medição no G06**: mesma cópia USA, mesma configuração. Se lá corromper e
   aqui não, está isolado no par jogo/driver — a mesma família dos outros dois relatórios de Mali
   abertos nesta pasta.
3. Só então voltar ao `hwDownloadMode`, e aí como **fator**, não como causa.
