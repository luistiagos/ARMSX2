# TASK-0093: trazer o trabalho de controle do ARMSX3 e o fallback de rumble para pads sem motor

- **Status:** em andamento
- **Criada em:** 2026-09-08
- **Concluída em:** —
- **Feature:** [FEAT-0003](../features/FEAT-0003-colheita-upstream-setembro-2026.md)
- **Bugs que resolve:** —
- **Commit:** — (o vínculo é o prefixo `TASK-0093:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

> **Bloco 4 de 5 da FEAT-0003.** Só começar depois que a
> [TASK-0092](TASK-0092-gamedb-e-precisao-ee-vu.md) estiver commitada e validada.
>
> ⚠️ **Esta é a primeira task da feature que precisa de hardware externo.** Sem pelo menos um
> controle físico — de preferência dois, de marcas diferentes — ela não pode ser validada. Confirme
> isso com o usuário **antes** de começar a implementar, não depois.

## Resumo em uma linha

Dois commits, ~1.100 linhas somadas, que dão rumble USB direto para pads PlayStation, fixação de
slot por jogador que sobrevive a reconexão, destino de rumble por controle, e a escolha explícita
sobre vibrar o aparelho quando o pad não tem motor.

## Por que este trabalho existe — o problema que ele resolve

**Um pad que reporta motores que não consegue acionar é indistinguível de um que funciona.** Esta é
a frase que explica os dois commits inteiros, e é por isso que a resposta é sempre dar a escolha ao
usuário em vez de adivinhar.

O caso concreto: um handheld que faz ponte de um controle acoplado através do próprio nó HID
**re-apresenta o pad sob o vendor id do handheld**, anuncia um inventário completo de vibradores
para ele, **aceita toda chamada de `vibrate()` sem erro — e não move nada.** O force feedback nunca
é encaminhado ao pad, e **nenhuma chamada de API distingue isso de um motor funcionando.**

## Os dois commits, na ordem cronológica de aplicação

### 1. `57a2f47137` — *"let the user choose the rumble fallback for motorless pads (#646)"* (02/09)

Alguns controles **não expõem motor nenhum** ao Android: Xbox Series X|S por Bluetooth, e alguns
modos Bluetooth do DualSense. O emulador não consegue vibrá-los por rota nenhuma — o pad é
alimentado pelo caminho JNI próprio, e o `InputDevice` não reporta vibrador algum.

Aqui há **duas issues do mesmo relator que se contradizem**, e ambas estão certas:

- **#433** pediu para **parar de vibrar o TELEFONE** para esses pads — um aparelho no bolso ou num
  suporte não é a coisa que está na mão.
- **#646** é a outra metade do trade: com o fallback suprimido, o pad **não faz mais nada**.

"Nenhum default serve para todos, então vire escolha em vez de palpite." Acrescenta **"Vibrar este
aparelho em vez disso"** na aba Pad, **desligado por padrão** (o comportamento da #433). Pads
embutidos de handheld não são afetados nos dois casos — não são externos, então o fallback da #241
nunca os consultou. O toast de "Testar vibração" passa a **nomear a configuração** quando o pad não
expõe motor, de modo que o diagnóstico e o remédio ficam no mesmo lugar.

| Arquivo | +/− |
|---|---|
| `i18n/I18n.kt` | +2 |
| `input/ControllerMappings.kt` | +17 |
| `runtime/MainActivityRuntime.kt` | +1 |
| `ui/settings/PadTab.kt` | +13 |
| `kr/co/iefriends/pcsx2/NativeApp.java` | +13 −2 |

### 2. `a2692242e0` — *"Pad: bring over the ARMSX3 controller work"* (03/09)

Portado da árvore do **ARMSX3**, onde foi acertado contra hardware real. Os dois apps compartilham a
camada de input e settings de `com.armsx2`, então a maior parte atravessa direto; **as partes que
tocam slots foram mescladas, não copiadas**, porque o ARMSX2 tem Multitap e portas de PS2 onde o
ARMSX3 tem sete.

Quatro coisas:

1. **`UsbRumble`** — endereça o hardware diretamente com um HID `SET_REPORT`, contornando o problema
   descrito acima.
2. **`UsbPadTakeover`** (opcional, **desligado por padrão**) — reivindica o pad por inteiro e entrega
   a entrada dele como eventos Android. É **a única forma** de alcançar os motores num pad cuja
   única interface HID não pode ser retida só para saída — e traz gatilhos analógicos de verdade e
   os dois motores acionados independentemente.
3. **Descoberta de motor em nova ordem:** `defaultVibrator` **primeiro**, depois vibradores por id,
   depois a API legada por dispositivo. Nós perguntávamos **só** por id — que é a ordem que o ARMSX3
   mediu como **silenciando pads que funcionavam**, e é candidata a ser a causa da própria #646.
   Dois motores continuam ganhando um canal cada quando esse é o caminho que responde.
4. **Fixação de slot de jogador**, chaveada por `InputDevice.descriptor` para **sobreviver a
   reconexões**. Sem isso os slots são tomados por ordem de quem aperta primeiro, o que não
   consegue expressar "o DualSense é o jogador 1 e o pad embutido é o jogador 2". Uma fixação vence
   uma reivindicação velha, e **uma fixação numa porta de Multitap é ignorada enquanto o Multitap
   está desligado** — onde rotear um pad para uma porta de PS2 não armada faria a entrada dele
   sumir por completo.
5. **Destino de rumble por controle** (Auto / Controle / Aparelho / Desligado) — pela mesma razão da
   fixação: a escolha tem de ser do usuário.

| Arquivo | +/− | Situação |
|---|---|---|
| `input/UsbRumble.kt` | +363 | **arquivo novo** |
| `input/UsbPadTakeover.kt` | +270 | **arquivo novo** |
| `input/PadRouter.kt` | +231 −4 | sem delta nosso |
| `kr/co/iefriends/pcsx2/NativeApp.java` | +104 −35 | delta nosso: +7 |
| `ui/settings/PadTab.kt` | +69 | sem delta nosso |
| `i18n/I18n.kt` | +14 | delta nosso: +128 −23 |
| `runtime/MainActivityRuntime.kt` | +7 | delta nosso: **+500 −109** |
| `navigation/NavigationDrawer.kt` | +6 −1 | delta nosso: +14 −68 |
| `AndroidManifest.xml` | +4 | delta nosso: **+130 −3** |

## Superfície de conflito — medida em 2026-09-08

**Cinco arquivos estão nos dois lados**, e dois deles têm delta nosso grande:

- **`MainActivityRuntime.kt` (+500 −109 nosso)** — eles acrescentam 7 linhas. O conflito deve ser
  pequeno, mas o arquivo é o mais editado da nossa árvore.
- **`AndroidManifest.xml` (+130 −3 nosso)** — eles acrescentam 4 linhas, quase certamente permissão
  / intent-filter de USB. **Conferir se não colide com o nosso `MANAGE_EXTERNAL_STORAGE` do flavor
  `github`.**
- **`I18n.kt` (+128 −23 nosso)** — 16 chaves novas somadas entre os dois commits.
- `NavigationDrawer.kt`, `NativeApp.java` — deltas pequenos dos dois lados.

**`PadRouter.kt`, `PadTab.kt` e `ControllerMappings.kt` não têm delta nosso** — devem aplicar limpo.

> ⚠️ **`NativeApp.java` é a ponte JNI.** O namespace `Java_kr_co_iefriends_pcsx2_*` é preservado no
> upstream apesar do `applicationId` diferente, mas **JNI liga por nome, não por assinatura**: um
> método cujo nome bate e cuja assinatura não, compila, linka, roda e devolve lixo em silêncio. Se
> qualquer método nativo for tocado, rodar:
>
> ```bash
> python scripts/compare_jni_surface.py <referencia>/main.cpp \
>        platforms/android/app/src/main/cpp/native-lib.cpp
> ```

> ⚠️ **R8 está ligado no release.** `UsbRumble` e `UsbPadTakeover` estão em `com.armsx2.input`, que
> **não** é coberto pelo `-keep class kr.co.iefriends.pcsx2.**` do
> [`proguard-rules.pro`](../../platforms/android/app/proguard-rules.pro). Se alguma dessas classes
> for alcançada **por nome** (reflexão, JNI, `Class.forName`), ela precisa da própria regra — e a
> falha aparece **só em runtime, num build de release**. Conferir isso é parte desta task, não da
> próxima.

## Escopo

**Entra:**

1. `git cherry-pick 57a2f47137` (primeiro — é o mais antigo e o menor).
2. `git cherry-pick a2692242e0`.
3. Resolver os conflitos dos cinco arquivos compartilhados.
4. **Traduzir para pt-BR** as chaves novas de `I18n.kt` em `assets/i18n/pt-BR.json`. São 16 chaves;
   sem isso o usuário pt-BR vê texto em inglês numa aba inteira.
5. **Verificar a cobertura do R8** para as duas classes novas, e acrescentar regra se necessário.

**NÃO entra:**

- **Ligar o `UsbPadTakeover` por padrão.** O upstream o deixa desligado, e por bom motivo: ele
  reivindica o pad por inteiro. Manter o default deles.
- **Ligar "Vibrar este aparelho" por padrão.** Idem — o default é o comportamento da #433.
- **Mexer no nosso `UsbDevices.kt`.** Ele já existe na nossa árvore e não é tocado por nenhum dos
  dois commits. Se houver sobreposição funcional com `UsbRumble.kt`, **registrar no Resultado** e
  deixar para uma task própria — não unificar dentro desta.
- Traduzir para as outras 14 línguas.
- O Bloco 5.

## Como implementar

### 1. Confirmar o hardware antes de tudo

Pergunte ao usuário quais controles estão disponíveis. O ideal para cobrir os critérios:

| Para validar | Precisa de |
|---|---|
| Rumble USB direto | DualSense ou DualShock 4, **por cabo USB** |
| Fallback de pad sem motor | Xbox Series X\|S **por Bluetooth**, ou DualSense em modo BT |
| Fixação de slot | **dois** controles distintos |
| Multitap | dois controles + Multitap ligado |

Se só houver um controle, **diga quais critérios ficam sem validar** em vez de dá-los por bons.

### 2. Aplicar

```bash
git fetch upstream --prune
git cherry-pick 57a2f47137
git cherry-pick a2692242e0
```

Ao resolver conflito em `MainActivityRuntime.kt` e `AndroidManifest.xml`: **abrir os dois lados**
antes de escrever. Regra do projeto — não escreva código sobre símbolo que não abriu, e um `grep`
que mostra a linha não é verificação.

### 3. Compilar

Esta task **toca `I18n.kt`**, então o `-Pkotlin.incremental=false` é obrigatório na primeira
compilação:

```bash
cd platforms/android && ./gradlew.bat --stop
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:compileGithubDebugKotlin \
  -Pkotlin.incremental=false \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

Sem ele, o build falha com `Unresolved reference LSFG_EN` e uma cascata de erros em
`RendererTab.kt` que **não são do seu código** — `LSFG_EN` mora em `src/github/`, outro source set,
e o incremental perde a referência.

### 4. Instalar

```bash
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:installGithubDebug \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

Confirmar que o APK instalado é o novo (procedimento na TASK-0090, seção 4). **`compile*Kotlin`
verde não põe nada no aparelho.**

### 5. E um build de RELEASE, porque o R8 só falha lá

```bash
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:assembleGithubRelease \
  -Parmsx2.applicationId=come.nanodata.armsx2 \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

> 🔴 **Não publicar este APK.** O release assina com a keystore de **debug** quando
> `armsx2_keystore.properties` não existe, e **não falha ao fazê-lo**. Um APK com assinatura
> diferente quebra a atualização de todo mundo. Este build é só para provar que o R8 não apaga as
> classes novas.

## Como validar

### Critério 1 — rumble USB direto (`UsbRumble`)

Com um **DualSense ou DualShock 4 por cabo USB**:

1. Aba Pad → "Testar vibração".
2. **Aprovado:** o **controle** vibra. **Reprovado:** nada vibra, ou o **telefone** vibra no lugar.

### Critério 2 — o pad sem motor, e a escolha da #646

Com um **Xbox Series X|S por Bluetooth** (ou DualSense em modo BT sem motor exposto):

1. Com "Vibrar este aparelho em vez disso" **desligado** (o padrão): "Testar vibração" **não** faz o
   telefone vibrar, e o **toast nomeia a configuração**. — este é o comportamento da #433.
2. **Ligando** a opção: o telefone vibra. — este é o atendimento da #646.

**Aprovado:** os dois comportamentos, e o toast que aponta para o remédio.

> Se a **nova ordem de descoberta** (`defaultVibrator` primeiro) fizer o pad que antes era "sem
> motor" passar a vibrar **de verdade**, isso é o cenário que o commit levanta como possível causa
> raiz da #646. **Registrar no Resultado** — é informação que vale para o upstream.

### Critério 3 — `UsbPadTakeover`

1. Confirmar que está **desligado por padrão**.
2. Ligar. **Aprovado (3a):** a entrada do pad continua chegando ao jogo (agora como eventos
   Android). **Aprovado (3b):** os **gatilhos são analógicos** — testar num jogo de corrida, o
   acelerador é progressivo, não liga/desliga. **Aprovado (3c):** os dois motores são acionados
   **independentemente**.
3. **Desligar de novo e confirmar que o pad volta ao normal.** Uma reivindicação que não solta é
   pior que não ter a funcionalidade.

### Critério 4 — fixação de slot, e a reconexão

Com **dois controles**:

1. Fixar o controle A como jogador 1 e o B como jogador 2.
2. **Desconectar e reconectar** o A. **Aprovado (4a):** ele volta como jogador **1** — a fixação é
   por `InputDevice.descriptor` e sobrevive.
3. Fazer o B apertar primeiro e depois conectar o A. **Aprovado (4b):** a fixação vence a
   reivindicação por ordem de chegada.
4. **Com o Multitap DESLIGADO**, fixar um pad numa porta de Multitap. **Aprovado (4c):** a fixação é
   **ignorada** e a entrada do pad **continua chegando**. Este é o caso que o commit chama de
   perigoso — se a entrada sumir, é reprovação.
5. Ligar o Multitap e repetir. **Aprovado (4d):** agora a fixação vale.

### Critério 5 — destino de rumble por controle

Percorrer as quatro opções (Auto / Controle / Aparelho / Desligado) e confirmar que cada uma faz o
que diz. **"Desligado" tem de silenciar tudo** — é a opção que o usuário escolhe quando nada mais
funciona.

### Critério 6 — nada regrediu no controle que já funcionava

O pad que você usa normalmente, num jogo, 5 minutos: botões, os dois analógicos, gatilhos, d-pad,
e a vibração no jogo (não o teste). **Aprovado:** idêntico ao de antes.

### Critério 7 — R8 não apagou as classes novas

Instalar o APK **de release** e repetir os critérios 1 e 3. **Aprovado:** funcionam igual ao debug.
Se falharem só no release, é regra de `proguard-rules.pro` faltando — acrescentar e repetir.

### Critério 8 — a tradução

App em pt-BR: **nenhuma chave crua** (`pad.rumble.something`) e **nenhum texto em inglês** na aba
Pad. Este projeto já teve uma task inteira sobre isso
([TASK-0081](TASK-0081-nenhuma-chave-de-traducao-chega-crua-a-tela.md)).

### Testes de regressão

```bash
JAVA_HOME=D:/DevCaches/jdk-21 ./gradlew.bat :app:testGithubDebugUnitTest \
  -Dorg.gradle.java.installations.auto-detect=false \
  "-Dorg.gradle.java.installations.paths=D:\DevCaches\jdk-21"
```

## Antes de fechar

```bash
python scripts/check_traceability.py
python scripts/check_traceability.py --commits upstream/master..HEAD
```

Preencher `## Resultado` com: **quais controles foram usados, por marca e por conexão**, o veredito
de cada critério, **quais critérios ficaram sem validar por falta de hardware** (isto é obrigatório,
não opcional), se o R8 precisou de regra nova, e se houve sobreposição entre `UsbRumble.kt` e o
nosso `UsbDevices.kt`.

## Resultado

Implementação aplicada em **três commits**, adotando os commits upstream `57a2f47137` e
`a2692242e0` por `git cherry-pick -x`:

| Commit | Autor | O que é |
|---|---|---|
| `b1c22e953f` | jpolo1224 | cherry-pick de `57a2f47137` — o fallback de rumble (#646) |
| `60ea345692` | jpolo1224 | cherry-pick de `a2692242e0` — o trabalho de controle do ARMSX3 |
| `83ca710013` | Luis Tiago | as 16 traduções pt-BR da aba Pad |

> **Isto substitui um commit único anterior (`7300a5133d`), que juntava os dois commits do upstream
> e ficava assinado por nós.** Refeito em 2026-09-15 por decisão do usuário: a FEAT-0003 exige
> `cherry-pick -x` para **preservar a autoria do upstream** e deixar a linha
> `(cherry picked from commit …)` no corpo, que é o que faz o próximo `git merge upstream/master`
> reconhecer o que já veio. A árvore resultante é **byte a byte idêntica** à do commit anterior — foi
> conferido com `git diff` antes de mover a branch —, então nada do que foi validado mudou. O estado
> anterior está preservado em `backup/task-0093-squash-2026-09-15`.

O ajuste de integração que os dois cherry-picks precisaram: esta árvore semeia os gates nativos em
`seedNativeGates()`, num worker, para tirar o `System.loadLibrary` da thread da UI (TASK-0079).
`ControllerMappings.syncRumbleFallback()` entra lá; `UsbRumble.start`, `UsbRumble.loadTakeover` e
`PadRouter.loadPins` ficam no `onCreate`, porque precisam da Activity.

Validação feita nesta máquina:

- `git fetch upstream --prune`: passou.
- `:app:compileGithubDebugKotlin` com `-Pkotlin.incremental=false`: passou em 1m34s. Avisos: depreciações existentes e `UsbRumble.kt:268` usando `getParcelableExtra` legado.
- `:app:assembleGithubRelease -Parmsx2.applicationId=come.nanodata.armsx2`: passou em 6m32s; R8 concluiu sem exigir regra nova. As classes `UsbRumble` e `UsbPadTakeover` são alcançadas por referências Java diretas, não por reflexão/JNI por nome, então nenhuma regra nova foi adicionada a `proguard-rules.pro`.
- `:app:testGithubDebugUnitTest`: passou em 54s.
- `pt-BR.json`: JSON válido; as 16 chaves novas da aba Pad foram traduzidas.

Validação não feita nesta máquina:

- Instalação do APK debug/release: `adb devices` não listou nenhum aparelho conectado.
- Critérios 1 a 7 de hardware: pendentes por falta de aparelho/controles na sessão. Não foram testados DualSense/DualShock 4 por USB, Xbox Series X|S/DualSense por Bluetooth, dois controles para fixação de slot, Multitap, nem repetição em APK release.
- Critério 8 visual no app: a cobertura de chaves foi validada no arquivo, mas a tela pt-BR ainda precisa ser conferida no aparelho.

Sobreposição observada: `UsbRumble.kt` é uma rota nova de rumble USB direto para pads PlayStation. O `UsbDevices.kt` existente não foi alterado nem unificado nesta task, conforme o escopo.
