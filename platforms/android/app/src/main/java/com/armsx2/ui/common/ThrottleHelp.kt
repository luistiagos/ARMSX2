package com.armsx2.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.WelcomeBanner
import com.armsx2.ui.settings.controllerFocusable

/**
 * Ensina o usuário a desabilitar o Game Optimizing Service da Samsung, que segura a CPU deste
 * aparelho em 52% do máximo enquanto um jogo roda (8,5 fps contra 49,8 fps, medido).
 *
 * **Por que ensina ESTE caminho e não o mais curto.** As versões anteriores mandavam forçar a
 * parada do GOS — três toques, e mediram-se 88 s de alívio. Só que um monitor de 5 min mostrou o
 * defeito do plano: para dar esses três toques o usuário **sai do app**, e a volta ao jogo
 * ressuscita o serviço. O GOS renasceu 2 s depois da transição de foco, com o teto de volta. As
 * medições em que a parada segurou tinham o app já em primeiro plano, sem a volta — foi por isso
 * que os números e o relato de campo se contradiziam.
 *
 * O que resolve é `pm disable-user`, e há caminho sem PC para ele. É mais longo, mas é **uma vez
 * na vida do aparelho** contra três toques por sessão que quase sempre não funcionam.
 *
 * **Autolimitante:** o aviso só nasce enquanto o GOS estiver ativo. Assim que o usuário concluir,
 * a condição fica falsa e ele nunca mais aparece — sem precisar de "não mostrar de novo".
 *
 * Não usa `Dialog`/`AlertDialog` pelo motivo que o cabeçalho de [PadModal] explica: cada um é uma
 * janela Android própria e engole os KeyEvents do gamepad antes do `dispatchKeyEvent` da Activity.
 */
object ThrottleHelp {
    private const val LAYER = "throttle-help"
    private const val STEPS = 10

    /** O app que dá um shell adb dentro do próprio telefone, sem PC. */
    private const val LADB_PACKAGE = "com.draco.ladb"

    /** O comando que resolve. Copiado para a área de transferência no passo 8 — digitar isto à
     *  mão, num teclado de celular, é pedir erro de digitação. */
    private const val FIX_COMMAND = "pm disable-user --user 0 com.samsung.android.game.gos"

    private val step = mutableIntStateOf(0)

    /** Visível. Zero é o aviso curto; 1..[STEPS] são as telas do assistente. */
    val visible = mutableStateOf(false)

    fun show() {
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
            // Insistente: o scrim do host fecha o modal a qualquer toque fora do card, e com o
            // jogo deitado os dedos do usuário estão nos controles da tela. Sai só pelo botão.
            onDismiss = null,
            initialFocusId = "$LAYER.primary",
            scrollState = bodyScroll,
        ) {
            val s = step.intValue
            val context = LocalContext.current

            val title = if (s == 0) str("throttle.help.title")
            else str("throttle.help.stepOf").format(s, STEPS)

            val body = when (s) {
                0 -> str("throttle.help.intro")
                1 -> str("throttle.help.step1")
                2 -> str("throttle.help.step2")
                3 -> str("throttle.help.step3")
                4 -> str("throttle.help.step4")
                5 -> str("throttle.help.step5")
                6 -> str("throttle.help.step6")
                7 -> str("throttle.help.step7")
                8 -> str("throttle.help.step8").format(FIX_COMMAND)
                9 -> str("throttle.help.step9")
                else -> str("throttle.help.step10")
            }

            // Rótulos e ações são VALORES; os dois botões são compostos num ponto só, abaixo.
            // Ramificar a composição dos botões registraria e desregistraria o mesmo controllerId
            // a cada passo, que é o laço de recomposição do bug da fila de download.
            val primaryLabel = when (s) {
                0 -> str("throttle.help.seeHow")
                1 -> str("throttle.help.start")
                2 -> str("throttle.help.openStore")
                3 -> str("throttle.help.openAbout")
                4 -> str("throttle.help.openDev")
                8 -> str("throttle.help.copyCmd")
                STEPS -> str("throttle.help.close")
                else -> str("throttle.help.gotIt")
            }
            // O botão grande SEMPRE avança; quando há ação, ele age e avança no mesmo toque. Um
            // leigo não precisa entender a diferença entre "fazer" e "seguir".
            val primaryAction: () -> Unit = when (s) {
                STEPS -> ({ hide() })
                2 -> ({ openPlayStore(context); step.intValue = 3 })
                3 -> ({ openSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS); step.intValue = 4 })
                4 -> ({
                    openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    step.intValue = 5
                })
                8 -> ({ copyCommand(context); step.intValue = 9 })
                else -> ({ step.intValue = s + 1 })
            }
            val secondaryLabel = if (s == 0) str("throttle.help.later") else str("throttle.help.back")
            val secondaryAction: () -> Unit =
                if (s == 0) ({ hide() }) else ({ step.intValue = s - 1 })

            // safeDrawing ANTES de medir: assim `maxHeight` já desconta barra de status, barra de
            // navegação e recorte. Deitado a barra de navegação fica no rodapé, bem onde ficam os
            // botões deste card.
            BoxWithConstraints(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
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

    /** Página do LADB na loja. `market://` abre o app da Play; sem ele, o site resolve. */
    private fun openPlayStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$LADB_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isSuccess) return
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$LADB_PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { context.startActivity(web) }.isSuccess) warnUnavailable()
    }

    private fun openSettings(context: Context, action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { context.startActivity(intent) }.isSuccess) warnUnavailable()
    }

    private fun copyCommand(context: Context) {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val ok = clip != null && runCatching {
            clip.setPrimaryClip(ClipData.newPlainText("ARMSX2", FIX_COMMAND))
        }.isSuccess
        WelcomeBanner.show(I18n.get(if (ok) "throttle.help.copied" else "throttle.help.copyFailed"))
    }

    /** Aparelho sem a tela que o passo pede. Avisar é melhor que estourar
     *  `ActivityNotFoundException` em cima de quem só seguia instruções. */
    private fun warnUnavailable() {
        WelcomeBanner.show(I18n.get("throttle.help.openFailed"))
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
