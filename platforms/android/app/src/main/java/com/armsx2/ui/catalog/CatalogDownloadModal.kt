package com.armsx2.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.catalog.CatalogEntry
import com.armsx2.catalog.DownloadQueueManager
import com.armsx2.i18n.str
import com.armsx2.ui.common.PadModal
import com.armsx2.ui.settings.controllerFocusable

/**
 * O que um toque num jogo ainda-não-baixado abre.
 *
 * Uma tela só, com as escolhas que fazem sentido para o estado daquele item — o papel que na versão
 * anterior cabia ao `dialog_rom_download`, que também era um painel com um botão primário de rótulo
 * variável e um de cancelar.
 *
 * Começar a baixar **pergunta antes**: uma ROM de PS2 tem entre 1 e 10 GB, e começar no primeiro
 * toque gastaria os dados do usuário por um encostar de dedo. E um download em andamento pode ser
 * **cancelado**, não só pausado: sem isso, um toque errado num jogo de 10 GB seria irreversível —
 * `remove()` para a transferência e apaga o `.part`.
 *
 * `PadModal` e não `AlertDialog`: um diálogo do Compose é uma janela Android própria e engole as
 * teclas do controle antes de chegarem ao `dispatchKeyEvent`. Ficaria perfeito no toque e morto no
 * gamepad — e o build recusa a compilar com um (`checkNoWindowModals`).
 */
@Composable
fun CatalogDownloadModal(
    entry: CatalogEntry,
    onStart: (CatalogEntry) -> Unit,
    onPause: (CatalogEntry) -> Unit,
    onResume: (CatalogEntry) -> Unit,
    onCancel: (CatalogEntry) -> Unit,
    onClose: () -> Unit,
) {
    val layer = "catalog-download-modal"
    val queueState = entry.queueState
    val choices: List<Triple<String, () -> Unit, Boolean>> = when (queueState) {
        DownloadQueueManager.State.PAUSED -> listOf(
            Triple(str("catalog.action.resume"), { onResume(entry); onClose() }, false),
            Triple(str("catalog.action.cancelDownload"), { onCancel(entry); onClose() }, true),
        )

        DownloadQueueManager.State.DOWNLOADING, DownloadQueueManager.State.QUEUED -> listOf(
            Triple(str("catalog.action.pause"), { onPause(entry); onClose() }, false),
            Triple(str("catalog.action.cancelDownload"), { onCancel(entry); onClose() }, true),
        )

        else -> listOf(
            Triple(str("catalog.confirm.start"), { onStart(entry); onClose() }, false),
        )
    }
    val message = when (queueState) {
        DownloadQueueManager.State.PAUSED -> str("catalog.paused")
        DownloadQueueManager.State.QUEUED -> str("catalog.queued")
        DownloadQueueManager.State.DOWNLOADING -> "${(entry.downloadProgress * 100).toInt()}%"
        else -> str("catalog.confirm")
    }

    PadModal(key = layer, onDismiss = onClose, initialFocusId = "$layer.close") {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                // Em coluna e não em linha: "Cancelar download" não cabe ao lado de mais dois
                // rótulos num aparelho de 384dp, e empilhado cada escolha vira um alvo largo —
                // melhor para o dedo e para o direcional.
                choices.forEach { (label, action, destructive) ->
                    CatalogModalButton(
                        label = label,
                        id = "$layer.${label.hashCode()}",
                        onClick = action,
                        container = if (destructive) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        content = if (destructive) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                CatalogModalButton(
                    label = str("action.cancel"),
                    id = "$layer.close",
                    onClick = onClose,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CatalogModalButton(
    label: String,
    id: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            // clickable() E controllerFocusable(): são caminhos distintos. O primeiro é o toque; o
            // segundo é o direcional/gamepad. A primeira versão desta tela tinha só o segundo, e
            // nada respondia ao dedo.
            .clickable(onClick = onClick)
            .controllerFocusable(id, RoundedCornerShape(12.dp), onConfirm = onClick),
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
