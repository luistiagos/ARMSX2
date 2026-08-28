package com.armsx2.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.armsx2.catalog.DownloadQueueManager
import com.armsx2.i18n.str
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.home.DownloadQueueItem

/**
 * As linhas da fila de download — o conteúdo que a aba "Salvos" da versão anterior mostrava
 * (TASK-0038, movido para tela própria na TASK-0040).
 *
 * É o mesmo cartão de `res/layout/item_download_queue.xml`: capa, título em duas linhas, uma linha
 * de status, barra de progresso, botão primário pausar↔retomar e um cancelar sempre disponível. As
 * regras de visibilidade também são as de lá — em `QUEUED` não há barra nem botão primário, porque
 * não há o que mostrar nem o que pausar.
 *
 * Sem cabeçalho de seção: o único chamador é a [DownloadsScreen], cuja barra de topo já diz
 * "Downloads" — um "BAIXANDO" logo abaixo seria o mesmo rótulo duas vezes.
 */
@Composable
fun DownloadQueueSection(
    queue: List<DownloadQueueItem>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        queue.forEach { item ->
            DownloadQueueRow(item, onPause, onResume, onCancel)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DownloadQueueRow(
    item: DownloadQueueItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    // Enquanto o downloader resolve a URL (até cinco consultas encadeadas) ainda não há tamanho
    // total. Barra indeterminada nesse trecho: um "0%" parado por quarenta segundos é exatamente a
    // leitura de "não aconteceu nada" que abriu este bug.
    val resolving = item.state == DownloadQueueManager.State.DOWNLOADING && item.totalBytes <= 0L
    val percent = (item.progress * 100).toInt()

    val status = when (item.state) {
        DownloadQueueManager.State.QUEUED -> str("catalog.queue.waiting")
        DownloadQueueManager.State.PAUSED -> str("catalog.paused.short")
        DownloadQueueManager.State.ERROR -> str("catalog.queue.failed")
        else ->
            if (resolving) str("catalog.queue.resolving")
            else str("catalog.queue.progress")
                .format(megabytes(item.downloadedBytes), megabytes(item.totalBytes), percent)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueCover(item)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Sem barra em QUEUED, como no layout antigo: o item ainda não começou.
                if (item.state != DownloadQueueManager.State.QUEUED) {
                    Spacer(Modifier.height(6.dp))
                    if (resolving) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { item.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // Botão primário: pausar quando baixa, retomar quando pausado, ausente quando só
            // espera. Mesma matriz do `btn_queue_action`.
            //
            // UM só `RoundAction`, com o rótulo trocando — e não dois ramos de `when`. Dois ramos
            // são dois pontos de composição distintos com o MESMO `controllerId`: ao pausar, o
            // ramo antigo é destruído (`unregister`) e o novo registrado, e as duas operações
            // escrevem `selectedId`/`selectedIndex`, que a composição lê para desenhar o foco.
            // Com o foco do controle pousado neste botão — o caso normal nesta tela, onde ele é um
            // dos poucos controles — isso vira recomposição sem fim: a tela congela sob o véu
            // escuro, surda a toque e a BACK, com GCs de 14 MB a cada 250 ms. Medido no aparelho.
            // Um único ponto de composição mantém a identidade; só os parâmetros mudam.
            val pausable = item.state == DownloadQueueManager.State.DOWNLOADING
            val resumable = item.state == DownloadQueueManager.State.PAUSED ||
                item.state == DownloadQueueManager.State.ERROR
            if (pausable || resumable) {
                RoundAction(
                    glyph = if (pausable) "⏸" else "▶",
                    description = if (pausable) str("catalog.action.pause") else str("catalog.action.resume"),
                    onClick = { if (pausable) onPause(item.fileName) else onResume(item.fileName) },
                    buttonSize = 40.dp,
                    subtleFrame = true,
                    controllerId = "home.queue.${item.fileName}.action",
                )
            }
            Spacer(Modifier.width(4.dp))
            RoundAction(
                glyph = "✕",
                description = str("catalog.action.cancelDownload"),
                onClick = { onCancel(item.fileName) },
                buttonSize = 40.dp,
                subtleFrame = true,
                controllerId = "home.queue.${item.fileName}.cancel",
            )
        }
    }
}

@Composable
private fun QueueCover(item: DownloadQueueItem) {
    Box(
        Modifier
            .size(width = 56.dp, height = 72.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (item.coverUrl != null) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "↓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `%.1f` na locale do usuário — vírgula em pt-BR, como na versão anterior. */
private fun megabytes(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)
