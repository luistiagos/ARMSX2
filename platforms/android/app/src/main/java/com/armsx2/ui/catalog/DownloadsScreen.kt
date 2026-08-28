package com.armsx2.ui.catalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
