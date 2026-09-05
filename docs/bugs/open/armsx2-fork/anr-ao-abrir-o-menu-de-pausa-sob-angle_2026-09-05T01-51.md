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
