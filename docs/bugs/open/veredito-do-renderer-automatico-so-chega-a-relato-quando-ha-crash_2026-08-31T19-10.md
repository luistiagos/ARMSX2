# Bug: o veredito do renderer automático só chega a um relato quando há crash

- **Detectado em:** 2026-08-31 19:10 (leitura de código, durante o registro da regra `auto-vulkan`)
- **Origem:** análise da cadeia de defeitos gráficos no Galaxy A12
- **Errors (serviço):** nenhum — o defeito é a **ausência** de dado nos relatos existentes
- **Classe:** fail
- **Reincidência:** é a mesma lacuna que o registro do A07 descreve para os logs, num outro canal
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0065](../../task/TASK-0065-veredito-do-renderer-em-todo-relato.md)

## Sintoma

Diante de um relato de "tela preta" ou "linhas na imagem", **não há como saber qual renderizador o
aparelho escolheu, nem por quê** — nem pelo relato, nem pela telemetria, nem por nenhuma tela do
app. A única saída hoje é pedir uma foto e adivinhar.

## Por que o dado existe e mesmo assim não chega

A decisão do `auto` é tomada no núcleo, em
[`GSUtil::AndroidAutoPrefersVulkan`](../../../pcsx2/GS/GSUtil.cpp#L302), que **já registra o motivo**
numa string (`s_android_auto_renderer_reason`, com valores como `driver-rule:gl-arm-g52-r38-auto-vulkan`,
`adreno-default`, `gl-feedback-copy-workaround`, `platform-default`).

Só que essa string tem exatamente um consumidor: um `Console.WriteLn` em
[`GSUtil::GetPreferredRenderer`](../../../pcsx2/GS/GSUtil.cpp#L385). Daí em diante ela some. As três
pontes possíveis estão todas cortadas:

| caminho | por que não entrega |
|---|---|
| A ponte JNI | `Java_kr_co_iefriends_pcsx2_NativeApp_setAutoRendererGpuStrings` é `void` — o app **manda** as strings do GPU e não **recebe** nada de volta ([native-lib.cpp:2327](../../../platforms/android/app/src/main/cpp/native-lib.cpp#L2327)) |
| O resumo de boot do GS | `MainActivityRuntime` grava em `TelemetryReporter.setGraphicsBootSummary` só `gl_vendor`/`gl_renderer`/`gl_version` — a **identidade** do GPU, nunca a **decisão** ([MainActivityRuntime.kt:1962](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1962)) |
| O logcat capturado no relato | funciona, **mas só existe quando há crash**: `CrashReporter` anexa o logcat a um relato de crash/ANR |

## O buraco, em uma frase

**Tela preta e imagem corrompida não são crash.** O próprio registro do A07 já diz isso — *"não é
crash, não gera telemetria"*. Ou seja: o único canal que carrega o veredito é o único que não
dispara na classe de defeito para a qual o veredito importa.

O comentário no `MainActivityRuntime` que justifica a decisão atual raciocina corretamente sobre o
núcleo ser observável por log e o `CrashReporter` capturar logcat — e é verdade. O que ele não
cobre é o caso sem crash, que é o caso.

## Consequência medida

Três correções gráficas seguidas no mesmo aparelho (lento → tela preta → linhas), e em nenhuma
delas foi possível confirmar, a partir de um relato, qual backend estava ativo. A regra
`gl-arm-g52-r38-auto-vulkan` está publicada e **não há como distinguir em campo** entre:

- a regra casou e mandou o aparelho para o Vulkan;
- a regra não casou (por exemplo, o driver subiu para r39) e o aparelho está no OpenGL;
- o usuário escolheu Vulkan à mão e a regra é irrelevante ali.

As três produzem relatos idênticos.

## Correção

Levar o veredito ao resumo de boot do GS, que já é anexado a todo relato — e por ser uma string
curta, também serve para a tela de diagnóstico do app. Detalhes e escopo na
[TASK-0065](../../task/TASK-0065-veredito-do-renderer-em-todo-relato.md).

⚠️ **Sem trocar a assinatura da ponte existente.** JNI liga por **nome**, não por assinatura:
mudar `setAutoRendererGpuStrings` de `void` para devolver `jstring` compila, linka, roda e devolve
lixo em silêncio — é o defeito que o `CLAUDE.md` manda medir com `compare_jni_surface.py`. O
veredito sai por um **método novo**.
