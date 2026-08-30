package com.armsx2.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx2.ui.settings.controllerFocusable

/**
 * O aviso do limite de CPU do aparelho, e o assistente que ensina a desarmá-lo.
 *
 * Substitui o diálogo único da TASK-0053, que voltou do teste com quatro defeitos — e cada
 * decisão aqui responde a um deles:
 *
 * - **Não fecha sozinho.** `onDismiss = null` é o que o [PadModal] chama de modal insistente: o
 *   toque no scrim é engolido e não fecha. Isso não era temporizador, era o `clickable` do scrim
 *   do host: com o jogo deitado os dedos ficam nos controles da tela, que são "fora do card", e
 *   qualquer toque fechava o aviso.
 * - **Cabe deitado.** O card mede o espaço disponível em vez de assumir retrato: o corpo leva
 *   `weight(1f, fill = false)` com rolagem e os botões ficam presos embaixo, então o **Fechar**
 *   nunca sai da tela. Jogo roda deitado, sempre — a versão anterior media 340 dp só de corpo
 *   contra ~288 dp de altura útil.
 * - **Um passo por tela.** O público do produto é leigo; três passos num parágrafo só não é
 *   instrução, é parede de texto.
 *
 * Não usa `Dialog`/`AlertDialog` pelo motivo que o cabeçalho de [PadModal] explica: cada um é uma
 * janela Android própria e engole os KeyEvents do gamepad antes do `dispatchKeyEvent` da Activity.
 */
object ThrottleHelp {
    private const val LAYER = "throttle-help"
    private const val STEPS = 4

    private val pct = mutableIntStateOf(0)
    private val step = mutableIntStateOf(0)

    /** Há a tela do GOS para ensinar a mexer. Falso num aparelho que corta o clock sem ser por
     *  ele: aí o aviso explica o que está havendo e para por aí, porque um assistente que manda
     *  abrir uma tela inexistente é pior que nenhum. */
    private val actionable = mutableStateOf(false)

    /** Visível. Zero é o aviso curto; 1..[STEPS] são as telas do assistente. */
    val visible = mutableStateOf(false)

    fun show(percent: Int, canFix: Boolean) {
        pct.intValue = percent
        actionable.value = canFix
        step.intValue = 0
        visible.value = true
    }

    fun hide() {
        visible.value = false
        step.intValue = 0
    }

    @Composable
    fun Host() {
        if (!visible.value) return
        val bodyScroll = rememberScrollState()
        PadModal(
            key = LAYER,
            // Insistente de propósito — ver o cabeçalho.
            onDismiss = null,
            initialFocusId = "$LAYER.primary",
            scrollState = bodyScroll,
        ) {
            val s = step.intValue
            val context = LocalContext.current

            val title = if (s == 0) str("throttle.help.title")
            else str("throttle.help.stepOf").format(s, STEPS)

            val introKey =
                if (actionable.value) "throttle.help.intro" else "throttle.help.intro.generic"
            val body = when (s) {
                0 -> str(introKey).format(pct.intValue)
                1 -> str("throttle.help.step1")
                2 -> str("throttle.help.step2")
                3 -> str("throttle.help.step3")
                else -> str("throttle.help.step4")
            }

            // Rótulos e ações são VALORES; os dois botões são compostos num ponto só, abaixo.
            // Ramificar a composição dos botões registraria e desregistraria o mesmo controllerId
            // a cada passo, que é exatamente o laço de recomposição do bug da fila de download.
            val primaryLabel = when (s) {
                0 -> if (actionable.value) str("throttle.help.seeHow") else str("throttle.help.close")
                1 -> str("throttle.help.openScreen")
                STEPS -> str("throttle.help.close")
                else -> str("throttle.help.next")
            }
            val primaryAction: () -> Unit = when (s) {
                0 -> if (actionable.value) ({ step.intValue = 1 }) else ({ hide() })
                // Avança ANTES de sair do app: quem volta do Android encontra a instrução
                // seguinte, e não a que acabou de executar.
                1 -> ({
                    step.intValue = 2
                    com.armsx2.ThrottleWatcher.openVendorThrottlerSettings(context)
                    Unit
                })
                STEPS -> ({ hide() })
                else -> ({ step.intValue = s + 1 })
            }
            val secondaryLabel = if (s == 0) str("throttle.help.close") else str("throttle.help.back")
            val secondaryAction: () -> Unit =
                if (s == 0) ({ hide() }) else ({ step.intValue = s - 1 })

            // safeDrawing ANTES de medir: assim `maxHeight` já desconta barra de status, barra de
            // navegação e recorte. Deitado a barra de navegação fica no rodapé, bem onde ficam os
            // botões deste card — sem isto o "Fechar" pode nascer embaixo dela.
            BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Mede em vez de assumir. Deitado sobram ~288 dp de altura no aparelho de teste.
                val cardWidth = minOf(460.dp, (maxWidth - 32.dp).coerceAtLeast(200.dp))
                val cardHeight = (maxHeight - 24.dp).coerceAtLeast(160.dp)
                Surface(
                    modifier = Modifier
                        .widthIn(max = cardWidth)
                        .heightIn(max = cardHeight),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        // fill = false: o corpo ocupa o que sobra, mas nunca empurra a fileira de
                        // botões para fora do card. É o que mantém o Fechar sempre alcançável.
                        Text(
                            body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(bodyScroll),
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpButton(
                                label = secondaryLabel,
                                id = "$LAYER.secondary",
                                onClick = secondaryAction,
                                container = MaterialTheme.colorScheme.surfaceVariant,
                                content = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            HelpButton(
                                label = primaryLabel,
                                id = "$LAYER.primary",
                                onClick = primaryAction,
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpButton(
    label: String,
    id: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.controllerFocusable(
            controllerId = id,
            shape = RoundedCornerShape(14.dp),
            onConfirm = onClick,
        ),
        shape = RoundedCornerShape(14.dp),
        color = container,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}
