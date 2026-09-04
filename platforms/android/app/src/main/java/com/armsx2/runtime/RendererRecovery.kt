package com.armsx2.runtime

/**
 * A escada de recuperação de "a imagem não apareceu".
 *
 * ## Por que ela é acionada pelo usuário, e nunca sozinha
 *
 * Existe uma classe de falha gráfica que o emulador **não consegue ver por dentro**: a saída fica
 * preta enquanto `Host::BeginPresentFrame` continua sendo chamado normalmente — a VM, o áudio e o
 * contador de quadros seguem. Do lado de dentro, uma sessão preta é indistinguível de uma boa
 * (registrado em `docs/bugs/open/armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida`:
 * quatro capturas do mesmo boot, byte a byte idênticas, com o `PerfLog` mostrando 36,9 fps).
 *
 * Distinguir por dentro exigiria amostrar pixels, e esse caminho já foi percorrido e medido nesta
 * base: **38 falsos positivos em 6 modelos**, e o `GraphicsHealthMonitor` da linha anterior
 * declarando falha em cena escura. Está proibido no plano gráfico e no relatório do bug.
 *
 * Então a pergunta vai para quem tem a informação: o usuário, que está olhando para a tela. Um
 * toque, nenhum falso positivo.
 *
 * ## Por que a escada é DERIVADA
 *
 * O passo seguinte sai do que já está gravado — o `renderer` deste jogo — mais o veredito do
 * `auto`. Nenhum contador novo em disco, nada para sair de sincronia com a configuração que o
 * usuário pode mudar por fora, pela aba Renderer, no mesmo menu.
 */
object RendererRecovery {

    const val AUTO = "auto"
    const val OPENGL = "opengl"
    const val VULKAN = "vulkan"
    const val SOFTWARE = "software"

    /**
     * Qual backend o `auto` escolheu, lido do veredito nativo.
     *
     * O veredito tem a forma `"Vulkan reason=driver-rule:<id>"` / `"OpenGL reason=platform-default"`
     * (`Java_kr_co_iefriends_pcsx2_NativeApp_getAutoRendererVerdict`), e vem vazio antes de
     * `setAutoRendererGpuStrings` rodar. Devolve `null` quando não dá para saber — quem chama
     * decide o que fazer com isso, em vez de receber um palpite disfarçado de fato.
     */
    fun autoBackendOf(verdict: String?): String? = when {
        verdict.isNullOrBlank() -> null
        verdict.startsWith("Vulkan", ignoreCase = true) -> VULKAN
        verdict.startsWith("OpenGL", ignoreCase = true) -> OPENGL
        else -> null
    }

    /** O outro backend de hardware. */
    private fun other(backend: String): String = if (backend == VULKAN) OPENGL else VULKAN

    /**
     * O próximo backend a tentar, dado o que está gravado para este jogo ([stored]) e o veredito
     * cru do `auto` ([verdict]).
     *
     * | gravado | próximo | por quê |
     * |---|---|---|
     * | `auto` | o oposto do que o `auto` escolheu | o que falhou foi a escolha do `auto` |
     * | o mesmo que o `auto` escolheria | o oposto | o usuário fixou o mesmo; trocar é o passo útil |
     * | o oposto do que o `auto` escolheu | `software` | a troca de backend já foi tentada |
     * | `software` | `auto` | fecha o ciclo e devolve a escolha automática |
     *
     * Com o veredito ausente (o app ainda não empurrou as strings de GL, ou o nativo respondeu
     * vazio) o `auto` é tratado como **OpenGL**: é o `platform-default` do Android neste core
     * (`GSUtil::AndroidAutoPrefersVulkan` devolve `false` quando nenhuma regra casa), então o
     * primeiro passo a partir de `auto` vira Vulkan. É a suposição menos ruim, e é só isso: uma
     * suposição, restrita ao primeiro toque, porque a partir do segundo o gravado já é explícito.
     */
    fun nextBackend(stored: String, verdict: String?): String {
        val auto = autoBackendOf(verdict) ?: OPENGL
        return when (stored) {
            SOFTWARE -> AUTO
            OPENGL, VULKAN -> if (stored == auto) other(auto) else SOFTWARE
            else -> other(auto) // AUTO e qualquer valor desconhecido/corrompido
        }
    }
}
