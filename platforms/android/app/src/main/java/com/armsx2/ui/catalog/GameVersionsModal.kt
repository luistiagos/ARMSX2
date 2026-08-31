package com.armsx2.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.armsx2.catalog.CatalogEntry
import com.armsx2.catalog.DownloadQueueManager
import com.armsx2.i18n.str
import com.armsx2.ui.common.PadModal
import com.armsx2.ui.settings.controllerFocusable

/**
 * A escolha de QUAL arquivo baixar, quando um título tem mais de um.
 *
 * O manifesto tem uma linha por arquivo: "007 - Nightfire" são cinco (USA, duas europeias, Japan,
 * Korea), todas com a mesma arte no repositório de capas. A biblioteca passou a mostrar uma célula
 * por título (TASK-0047) e a diferença entre os lançamentos — que é justamente o que o nome do
 * arquivo carrega — mudou-se para cá.
 *
 * **Escolher aqui não começa o download.** A escolha cai no [CatalogDownloadModal], que é quem
 * pergunta antes de gastar 1–10 GB de dados. Dois painéis em sequência e não um só porque são duas
 * perguntas diferentes: "qual versão" e "pode baixar".
 *
 * `PadModal` e não `AlertDialog` pelo mesmo motivo do painel de download: um diálogo do Compose é
 * uma janela Android própria e engole as teclas do controle antes do `dispatchKeyEvent` — e o
 * build recusa a compilar com um (`checkNoWindowModals`).
 */
@Composable
fun GameVersionsModal(
    title: String,
    variants: List<CatalogEntry>,
    onVariantSelected: (CatalogEntry) -> Unit,
    onClose: () -> Unit,
) {
    val layer = "game-versions-modal"

    PadModal(key = layer, onDismiss = onClose, initialFocusId = "$layer.close") {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 480.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    str("catalog.versions.subtitle").replace("%1\$d", variants.size.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                // heightIn e não wrap: 25 versões (Metal Gear Solid 3 - Subsistence) não cabem numa
                // tela de 384dp, e sem o teto o painel cresceria para fora dela levando os botões
                // junto.
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(variants, key = { it.fileName }) { variant ->
                        VersionRow(
                            variant = variant,
                            id = "$layer.${variant.fileName.hashCode()}",
                            onClick = {
                                onClose()
                                onVariantSelected(variant)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        // clickable() E controllerFocusable(): o primeiro é o dedo, o segundo é o
                        // direcional. Ver a mesma dupla no CatalogDownloadModal.
                        .clickable(onClick = onClose)
                        .controllerFocusable("$layer.close", RoundedCornerShape(12.dp), onConfirm = onClose),
                ) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(
                            str("action.cancel"),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    variant: CatalogEntry,
    id: String,
    onClick: () -> Unit,
) {
    // O que diferencia um lançamento do outro é o sufixo do nome do arquivo — região, idioma,
    // disco, revisão. Por isso a linha mostra o nome CHEIO (sem extensão) e não o título.
    val name = variant.fileName.substringBeforeLast('.')
    val format = variant.fileName.substringAfterLast('.', "").uppercase()
    val state = when {
        variant.isDownloaded -> str("catalog.downloaded")
        variant.queueState == DownloadQueueManager.State.DOWNLOADING -> "${(variant.downloadProgress * 100).toInt()}%"
        variant.queueState == DownloadQueueManager.State.PAUSED -> str("catalog.paused.short")
        variant.queueState == DownloadQueueManager.State.QUEUED -> str("catalog.queued")
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .controllerFocusable(id, RoundedCornerShape(8.dp), onConfirm = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val serialUrl = com.armsx2.catalogRepoCoverUrl(variant.fileName)
        val manifestUrl = variant.coverUrl?.takeIf { it.isNotBlank() }
        val coverUrl = if (com.armsx2.CoverArtStyle.use3d.value) {
            serialUrl ?: manifestUrl
        } else {
            manifestUrl ?: serialUrl
        }

        if (coverUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .size(80, 110)
                    .crossfade(true)
                    .build(),
                contentDescription = name,
                modifier = Modifier
                    .size(width = 38.dp, height = 52.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        Modifier
                            .size(width = 38.dp, height = 52.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    )
                },
                // A outra URL, e não um retângulo cinza. As duas fontes falham por motivos
                // independentes — a do manifesto porque o link morreu, a do repositório porque
                // aquele serial não tem arte —, então a que sobrou costuma responder. Sem esta
                // segunda tentativa a lista de versões era a única tela do fluxo que mostrava um
                // vazio para um jogo cuja capa a grade estava exibindo atrás do painel.
                error = {
                    val other = if (coverUrl == manifestUrl) serialUrl else manifestUrl
                    if (other != null && other != coverUrl) {
                        SubcomposeAsyncImage(
                            model = other,
                            contentDescription = name,
                            modifier = Modifier
                                .size(width = 38.dp, height = 52.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                            loading = {},
                            error = {},
                        )
                    } else {
                        Box(
                            Modifier
                                .size(width = 38.dp, height = 52.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        )
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (state == null) format else "$format · $state",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
