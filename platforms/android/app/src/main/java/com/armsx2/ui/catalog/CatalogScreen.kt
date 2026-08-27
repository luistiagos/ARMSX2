package com.armsx2.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.armsx2.catalog.CatalogEntry
import com.armsx2.catalog.DownloadQueueManager
import com.armsx2.i18n.str
import com.armsx2.navigation.UiNavigator
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.PadModal
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.settings.controllerFocusable

/**
 * Catálogo de ROMs — a tela que o RetroSystem PS2 tem e o upstream não.
 *
 * A lógica (parse do manifesto, fila, download com retomada, serviço em primeiro plano) veio
 * inteira da linha anterior, em Java, sem reescrita: ela não depende de UI. O que é novo aqui é só
 * a apresentação, porque a anterior era `RecyclerView` + XML e este app é Compose.
 *
 * Uma ROM de PS2 tem entre 1 e 10 GB, e por isso o download **não** vive nesta tela: quem o conduz
 * é o [DownloadQueueManager], que continua com o app fechado através do
 * `DownloadForegroundService`. Esta tela só enfileira e observa — sair dela não cancela nada.
 */
@Composable
fun CatalogScreen(onBack: () -> Unit, viewModel: CatalogViewModel = viewModel()) {
    val state = viewModel.state.value
    LaunchedEffect(Unit) { viewModel.load() }

    // Entrada aguardando confirmacao. Uma ROM de PS2 tem entre 1 e 10 GB: comecar a baixar no
    // primeiro toque gastaria os dados do usuario por um encostar de dedo. A versao anterior
    // perguntava antes, e esta pergunta tambem.
    var pending by remember { mutableStateOf<CatalogEntry?>(null) }
    pending?.let { entry -> CatalogEntryModal(entry, viewModel) { pending = null } }

    ArmsBackdrop {
        Column(Modifier.fillMaxSize()) {
            ArmsTopBar(
                title = str("catalog.title"),
                subtitle = if (state.loading) {
                    str("catalog.loading")
                } else {
                    "${str("catalog.available")}: ${state.visible.size}"
                },
                // A barra e a da tela INICIAL do app, nao a de uma subtela: por isso a gaveta a
                // esquerda (como na biblioteca) e nao um "voltar", que aqui nao teria para onde ir.
                // E a gaveta e onde mora a engrenagem de Configuracoes.
                leading = {
                    RoundAction(
                        "☰",
                        str("games.overflow.openNavigation"),
                        { UiNavigator.drawerOpen.value = true },
                        framed = true,
                        buttonSize = 44.dp,
                        buttonShape = RoundedCornerShape(14.dp),
                        subtleFrame = true,
                    )
                },
                // A biblioteca dos jogos ja baixados -- a segunda metade do par que no app anterior
                // eram as duas abas do menu inferior (Catalogo / Meus jogos).
                actions = {
                    RoundAction(
                        "▤",
                        str("games.section.library"),
                        onBack,
                        framed = true,
                        buttonSize = 44.dp,
                        buttonShape = RoundedCornerShape(14.dp),
                        subtleFrame = true,
                    )
                },
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                singleLine = true,
                label = { Text(str("catalog.search")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .controllerFocusable("catalog.search"),
            )

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (state.visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        str("catalog.empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // Grade adaptativa em vez de um número fixo de colunas: a mesma tela serve um telefone
            // em retrato e um handheld em paisagem, e 132.dp mantém a capa legível nos dois.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.visible, key = { it.fileName }) { entry ->
                    // O estado e o progresso saem do `entry` AQUI e descem como valores, em vez de o
                    // cartao os ler de dentro. `CatalogEntry` e um objeto Java mutado pelo
                    // downloader: a instancia nunca muda, e com o strong skipping do Compose um
                    // parametro de mesma instancia deixa o cartao pulavel -- ele nunca redesenharia.
                    // Um enum e um Float mudam de valor, e valor o Compose enxerga.
                    CatalogCard(
                        entry = entry,
                        queueState = entry.queueState,
                        progress = entry.downloadProgress,
                        downloaded = entry.isDownloaded,
                        onAction = { pending = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    entry: CatalogEntry,
    queueState: DownloadQueueManager.State?,
    progress: Float,
    downloaded: Boolean,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            // clickable() E controllerFocusable(): sao caminhos distintos. O primeiro e o toque; o
            // segundo e o direcional/gamepad. A primeira versao desta tela tinha so o segundo, e o
            // cartao simplesmente nao respondia ao dedo.
            .clickable(onClick = onAction)
            .controllerFocusable("catalog.item.${entry.fileName}", RoundedCornerShape(14.dp), onConfirm = onAction),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // 3:4 é a proporção das capas de PS2; fixá-la evita a grade "pular" enquanto
                    // as imagens chegam da rede em ordem imprevisível.
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                AsyncImage(
                    model = entry.coverUrl.takeIf { it.isNotBlank() },
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            CatalogCardStatus(queueState, progress, downloaded)
        }
    }
}

/**
 * A linha de estado do cartão. É o único lugar da tela que muda enquanto um download corre, e por
 * isso concentra a leitura do estado da fila — o resto do cartão é estático.
 */
@Composable
private fun CatalogCardStatus(
    queueState: DownloadQueueManager.State?,
    progress: Float,
    downloaded: Boolean,
) {
    when {
        downloaded -> Text(
            str("catalog.downloaded"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        queueState == DownloadQueueManager.State.DOWNLOADING -> Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        queueState == DownloadQueueManager.State.QUEUED -> Text(
            str("catalog.queued"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        queueState == DownloadQueueManager.State.PAUSED -> Text(
            str("catalog.paused"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        queueState == DownloadQueueManager.State.ERROR -> Text(
            str("catalog.error"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                str("catalog.download"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * O que um toque num cartao abre.
 *
 * Uma tela so, com as escolhas que fazem sentido para o estado daquele item -- e o papel que na
 * versao anterior cabia ao `dialog_rom_download`, que tambem era um painel persistente com um botao
 * primario que mudava de rotulo e um de cancelar.
 *
 * Comecar a baixar **pergunta antes**: uma ROM de PS2 tem entre 1 e 10 GB, e comecar no primeiro
 * toque gastaria os dados do usuario por um encostar de dedo. E um download em andamento pode ser
 * **cancelado**, nao so pausado: sem isso, um toque errado num jogo de 10 GB seria irreversivel --
 * `remove()` para a transferencia e apaga o `.part`.
 *
 * `PadModal` e nao `AlertDialog`: um dialogo do Compose e uma janela Android propria e engole as
 * teclas do controle antes de chegarem ao `dispatchKeyEvent`. Ficaria perfeito no toque e morto no
 * gamepad -- e o build recusa a compilar com um (`checkNoWindowModals`).
 */
@Composable
private fun CatalogEntryModal(
    entry: CatalogEntry,
    viewModel: CatalogViewModel,
    onClose: () -> Unit,
) {
    val layer = "catalog-entry-modal"
    val queueState = entry.queueState
    val choices: List<Triple<String, () -> Unit, Boolean>> = when {
        entry.isDownloaded -> emptyList()

        queueState == DownloadQueueManager.State.PAUSED -> listOf(
            Triple(str("catalog.action.resume"), { viewModel.resume(entry); onClose() }, false),
            Triple(str("catalog.action.cancelDownload"), { viewModel.cancel(entry); onClose() }, true),
        )

        queueState == DownloadQueueManager.State.DOWNLOADING ||
            queueState == DownloadQueueManager.State.QUEUED -> listOf(
            Triple(str("catalog.action.pause"), { viewModel.pause(entry); onClose() }, false),
            Triple(str("catalog.action.cancelDownload"), { viewModel.cancel(entry); onClose() }, true),
        )

        else -> listOf(
            Triple(str("catalog.confirm.start"), { viewModel.start(entry); onClose() }, false),
        )
    }
    val message = when {
        entry.isDownloaded -> str("catalog.downloaded")
        queueState == DownloadQueueManager.State.PAUSED -> str("catalog.paused")
        queueState == DownloadQueueManager.State.QUEUED -> str("catalog.queued")
        queueState == DownloadQueueManager.State.DOWNLOADING ->
            "${(entry.downloadProgress * 100).toInt()}%"
        else -> str("catalog.confirm")
    }

    PadModal(
        key = layer,
        onDismiss = onClose,
        initialFocusId = "$layer.close",
    ) {
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
                // Em coluna e nao em linha: "Cancelar download" nao cabe ao lado de mais dois
                // rotulos num aparelho de 384dp, e empilhado cada escolha vira um alvo largo --
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
                    label = str(if (choices.isEmpty()) "action.close" else "action.cancel"),
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
            .clickable(onClick = onClick)
            .controllerFocusable(id, RoundedCornerShape(12.dp), onConfirm = onClick),
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
