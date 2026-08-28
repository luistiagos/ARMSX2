package com.armsx2.ui.catalog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.EmptyState
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.home.HomeViewModel

/**
 * A tela de downloads — o que a aba "Salvos" da versão anterior mostrava.
 *
 * Existe como tela, e não como uma faixa no topo da biblioteca, por um motivo concreto: a grade
 * carrega 12.628 cartões, e a fila empilhada acima dela empurrava a biblioteca inteira para baixo
 * a cada download. Na `version1` as duas nunca dividiram tela — eram as duas abas do
 * `BottomNavigationView`, e tocar para baixar trocava para esta.
 *
 * Divide o `HomeViewModel` com a biblioteca de propósito: é ele quem assina a fila e publica
 * `HomeUiState.queue`. Um ViewModel próprio significaria um segundo assinante do mesmo
 * `DownloadQueueManager`, com o ciclo de vida da tela — exatamente o arranjo que já deixou a
 * biblioteca muda uma vez (TASK-0038).
 */
@Composable
fun DownloadsScreen(onBack: () -> Unit, viewModel: HomeViewModel = viewModel()) {
    val queue = viewModel.state.value.queue
    NotificationPermissionPrompt(active = queue.isNotEmpty())

    ArmsBackdrop {
        Column(Modifier.fillMaxSize()) {
            ArmsTopBar(
                title = str("catalog.queue.title"),
                subtitle = if (queue.isEmpty()) null else str("catalog.queue.count").format(queue.size),
                leading = { RoundAction("←", str("action.back"), onBack) },
            )
            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = str("catalog.queue.empty.title"),
                        message = str("catalog.queue.empty.body"),
                        // Altura explicita como nas telas vizinhas (BIOS, Packs de Texturas): sem
                        // ela o cartao estica ate o rodape e vira uma moldura vazia de tela inteira.
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) {
                    DownloadQueueSection(
                        queue = queue,
                        onPause = viewModel::pauseQueued,
                        onResume = viewModel::resumeQueued,
                        onCancel = viewModel::cancelQueued,
                    )
                }
            }
        }
    }
}

/**
 * Pede `POST_NOTIFICATIONS` quando — e só quando — há um download para acompanhar.
 *
 * O app declarava a permissão no manifesto e nunca a pedia. Com `targetSdk=37`, no Android 13+ isso
 * significa nascer negada (`importance=NONE, userSet=false`) e o sistema **descartar** o que o app
 * posta, inclusive a notificação do serviço de primeiro plano: o download corria sem sinal nenhum
 * com o app fechado.
 *
 * Aqui, e não no boot: este é o instante em que o usuário acabou de mandar baixar e está olhando a
 * fila, então o pedido se explica sozinho. Pedir na abertura seria pedir sem contexto a quem talvez
 * nunca baixe nada — o padrão que a própria documentação do Android desaconselha.
 *
 * Não há flag de "já perguntei". Quem limita é o sistema: ele exibe o diálogo no máximo duas vezes
 * e depois nega em silêncio, sem mostrar nada. Guardar a nossa própria flag só tiraria do usuário a
 * segunda chance que o Android deliberadamente concede.
 */
@Composable
private fun NotificationPermissionPrompt(active: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    // O resultado não é consultado: conceder faz a notificação aparecer sozinha na próxima
    // atualização de progresso, e negar não muda nada dentro do app — a fila continua na tela.
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(active, granted) {
        if (active && !granted) request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
