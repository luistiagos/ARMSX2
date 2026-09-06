# Bug: ANR ao abrir o menu de pausa com o OpenGL rodando sob ANGLE

- **Detectado em:** 2026-09-05 01:51 (Galaxy A12 `SM-A127M`, Mali-G52 r38p1, `githubDebug`, ao
  validar o degrau do ANGLE na escada de recuperação)
- **Origem:** desconhecida — o traço disponível é `Input dispatching timed out`, sem stack da main
- **Errors (serviço):** nenhum ainda; a telemetria não foi consultada para esta assinatura
- **Classe:** fail (ANR)
- **Reincidência:** primeira vez, **uma única ocorrência**
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma ainda

## Sintoma

Com o jogo rodando em `renderer=opengl` + `useAngleOpenGL=true`, pressionar VOLTAR para abrir o menu
de pausa produziu o diálogo do sistema **"RetroSystem PS2 não está respondendo"**:

```
WindowManager: ANR in Window{... MainActivity}. Reason: ... is not responding.
               Waited 10001ms for MotionEvent
ActivityManager: ANR in come.nanodata.armsx2
                 Reason: Input dispatching timed out (... Waited 10001ms for MotionEvent)
```

O app **sobreviveu** — o processo seguiu vivo depois do diálogo, e a imagem do jogo continuava na
tela (`OpenGL HW`, 640x448, tela-título desenhada).

## O controle, e é o que dá peso ao relato

Mesmo jogo (Delta Force — Black Hawk Down: Team Sabre, `SLUS-21414`), mesmo aparelho, mesmo APK,
mesmo gesto, mudando **uma** variável:

| braço | log | ANR |
|---|---|---|
| `renderer=opengl` + `useAngleOpenGL=true` | `@@ANGLE@@ enabled`, `GL_VENDOR: Google Inc. (ARM)` | **1** |
| `renderer=opengl`, sem ANGLE | `@@ANGLE@@ off` | **0** — o menu abriu normalmente |

## Por que isto é sinal, e não prova

**Uma ocorrência contra um controle.** Não foi repetido, e há confundidores fortes, todos medidos
nesta mesma série:

- O APK é **`debuggable`**, e o ART **recusa AOT** para esses — a
  [TASK-0086](../../../task/TASK-0086-eco-da-busca-nao-recompoe-a-biblioteca.md) mediu **44,8% da
  CPU da thread da UI no interpretador**. Um timeout de input é muito mais fácil nesse regime.
- O aparelho é o A12, e o jogo rodava a **46–48% de velocidade**, com quadros de 65–78 ms.

Ou seja: pode ser o ANGLE, pode ser o aparelho engasgado, pode ser os dois.

## A hipótese que já estava escrita no código

`MainActivityRuntime.applyAngleEnv` comenta, sobre um relato anterior:

> *"gsBackThread rides on every line: GV7's back thread is the OTHER ANGLE suspect (ANGLE binds an
> EGL context to a single thread far more strictly than the native GLES drivers do)"*

Se abrir o menu de pausa toca o contexto EGL a partir de outra thread, o ANGLE é bem mais estrito
que o driver nativo — e o comportamento observado casa com isso. **Não verificado.**

## Como reproduzir

```bash
# põe um jogo em OpenGL + ANGLE
python setpref.py '{"renderer":"opengl","useAngleOpenGL":true}' SLUS-21414
adb shell am start -a android.intent.action.VIEW -n come.nanodata.armsx2/com.armsx2.MainActivity -d '<file:// da rom>'
# espera o jogo desenhar, e entao:
adb shell input keyevent KEYCODE_BACK
adb logcat -d | grep 'ANR in come.nanodata'
```

## Próximos passos, na ordem de custo

1. **Repetir** — cinco tentativas de cada lado, para saber se é reprodutível ou foi um engasgo.
2. **Medir num APK não-`debuggable`**, para tirar o interpretador do ART do caminho. É o mesmo
   próximo passo que o relato da digitação já pede.
3. Se reproduzir, **capturar o stack da main** (`/data/anr/`) em vez do `Input dispatching timed
   out`, que só diz que ela não respondeu, nunca por quê.
4. Só então decidir se o degrau do ANGLE na escada
   ([TASK-0087](../../../task/TASK-0087-angle-entra-na-escada-de-recuperacao.md)) precisa de
   ressalva. **Ele não foi removido**: no ponto da escada em que ele entra, a alternativa é
   `software`, e o ANGLE renderiza em hardware onde o driver nativo fica preto.

> **O que desbloqueia esta medição:** a [TASK-0088](../../../task/TASK-0088-medir-sem-o-interpretador-do-art.md)
> acrescentou `-Parmsx2.debug.debuggable=false`, que produz um APK de debug **não-`debuggable`** —
> assinado com a chave de debug, então instala neste aparelho sem desinstalar nada. É o que tira o
> interpretador do ART do caminho e permite refazer os números sem esse confundidor.

## A causa, achada no traço — 2026-09-06

O `bugreport` daquela sessão tinha sido gerado e não lido. Ele contém
`FS/data/anr/anr_2026-09-05-01-51-01-731`, o traço deste ANR. **A hipótese acima está errada.**

A thread principal está em `Native`, bloqueada num condvar dentro do nosso próprio core:

```
"main" prio=5 tid=1 Native
  native: #03  libemucore_4k.so (std::__ndk1::condition_variable::wait(unique_lock<mutex>&)+24)
  native: #05  libemucore_4k.so (Java_kr_co_iefriends_pcsx2_NativeApp_renderShadeBoost+308)
  native: #06  libart.so (art_quick_generic_jni_trampoline+148)
```

E a pilha Java diz de onde a chamada veio:

```
at kr.co.iefriends.pcsx2.NativeApp.renderShadeBoost(Native method)
at com.armsx2.config.Settings.applyTo(Settings.kt:1012)
at com.armsx2.ui.InGameOverlay.saveSettings$lambda$12(InGameOverlay.kt:181)
at com.armsx2.config.SettingsApplyQueue.runPending(SettingsApplyQueue.kt:131)
at com.armsx2.config.SettingsApplyQueue.runner$lambda$0(SettingsApplyQueue.kt:80)
at android.os.Handler.handleCallback(Handler.java:942)
at android.os.Looper.loop(Looper.java:313)
```

### O que isso quer dizer

**Não é o ANGLE amarrando o contexto EGL a uma thread.** É a
[`SettingsApplyQueue`](../../task/TASK-0084-coalescer-o-apply-de-configuracoes.md) despachando o
apply coalescido **por `Handler` na thread principal**, e `Settings.applyTo` fazendo uma chamada
JNI **bloqueante** que espera a thread da CPU do emulador.

E isso é **exatamente o resíduo que a TASK-0084 registrou como não resolvido**:

> *"O apply coalescido ainda custa 61–76 ms de main thread quando dispara — uma vez por gesto em vez
> de nove vezes por segundo, mas ainda um quadro estourado por gesto. Removê-lo significa tirar
> `applyTo()`/`commitSettings()` da UI thread, o que está bloqueado: `MemorySettingsInterface` não
> tem mutex e há escritores diretos na UI fora do `applyTo`."*

O que este traço acrescenta é o **teto**: aqueles 61–76 ms não são um limite. Quando a thread que o
JNI espera está lenta, a espera vai a **mais de 10 segundos** e vira ANR. O apply chegou ali porque a
recuperação do renderizador tinha acabado de gravar `renderer` + `useAngleOpenGL`, e o apply
pendente foi drenado no caminho de pausa.

### O papel do ANGLE, revisto

O controle sem ANGLE continua valendo — **0 ANR** —, mas a leitura muda: o ANGLE não é o mecanismo,
é o que torna a espera longa o suficiente para estourar. Ele deixa a thread do GS mais lenta neste
par jogo/aparelho, e a chamada bloqueante que já existia passa do limite.

Ou seja: **o degrau do ANGLE não é o defeito**. O defeito é uma chamada JNI bloqueante na thread
principal, que qualquer coisa suficientemente lenta transforma em ANR.

### O que isso muda nos próximos passos

Os passos 1 a 3 acima continuam úteis, mas deixam de ser o caminho principal. O caminho principal é
o que a TASK-0084 já nomeou e deixou bloqueado: **tirar `applyTo`/`commitSettings` da thread
principal**, o que exige mutex em `MemorySettingsInterface` no lado nativo — contribuição ao
upstream, não remendo local.

Enquanto isso não acontece, um paliativo de baixo risco seria a fila **não** drenar na thread
principal quando há VM rodando. Não avaliado aqui.

> **Nota de método.** Este traço estava disponível desde 05/09 e passou despercebido porque o
> `bugreport` foi disparado e nunca aberto. `Input dispatching timed out` diz que a main não
> respondeu, nunca por quê; o traço em `/data/anr/` diz. Vale sempre puxá-lo.

