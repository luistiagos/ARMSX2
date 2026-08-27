package com.armsx2.ui.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.onboarding.OnboardingViewModel
import com.armsx2.ui.settings.controllerFocusable

/**
 * Gerir as pastas de ROM — como tela, não como assistente.
 *
 * A gaveta tem uma linha "Configurar/alterar pastas", e no upstream ela abre o **assistente de
 * primeira execução**: cinco páginas com "Próximo" e "Voltar", que pedem local dos dados, BIOS e
 * pastas antes de deixar sair. Para quem só quer acrescentar uma pasta, isso é um caminho de mão
 * única onde deveria haver um menu.
 *
 * Aqui é uma tela igual às outras — entra, mexe, volta pela seta. A [TASK-0022] já havia tirado o
 * assistente do arranque; esta tira também do único lugar que ainda o abria por engano.
 *
 * **A lógica não é nova.** Adicionar e remover continuam sendo
 * [OnboardingViewModel.addGameFolder] e [OnboardingViewModel.removeGameFolder], que já tomam a
 * permissão persistente do SAF e gravam em `MainActivityRuntime.setRomsDirs`. Duplicar isso aqui
 * criaria duas verdades sobre a mesma lista — e a que esquecesse
 * `takePersistableUriPermission` perderia o acesso à pasta no próximo arranque, em silêncio.
 */
@Composable
fun RomFoldersScreen(onBack: () -> Unit, viewModel: OnboardingViewModel = viewModel()) {
    val state = viewModel.state.value
    LaunchedEffect(Unit) { viewModel.load() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addGameFolder)
    }

    ArmsBackdrop {
        // Column + verticalScroll e não LazyColumn: a navegação por controle registra cada linha
        // num SideEffect quando ela compõe, e o Lazy nunca compõe o que está fora da tela — a
        // seleção emperraria no meio da lista. A lista de pastas é curta; não há custo.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArmsTopBar(
                // Sem subtitulo: o que caberia ali e a contagem de PASTAS, e reaproveitar o
                // rotulo "Biblioteca" para isso lia como contagem de JOGOS. A lista logo abaixo ja
                // mostra quantas sao, uma por linha.
                title = str("setup.page.roms.title"),
                leading = { RoundAction("←", str("action.back"), onBack) },
            )

            Text(
                str("setup.step.rom.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )

            state.gameFolders.forEach { raw ->
                // O rótulo é o último segmento do URI de árvore ("primary:ROMs" -> "ROMs"). O
                // caminho cru fica como reserva: uma pasta semeada por seedOwnRomsFolder é POSIX,
                // não tem segmento com ':' e cairia num rótulo vazio.
                val label = raw.toUri().lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: raw
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("▦", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(
                            onClick = { viewModel.removeGameFolder(raw) },
                            modifier = Modifier.controllerFocusable(
                                "rom-folders.remove.$raw",
                                RoundedCornerShape(12.dp),
                                onConfirm = { viewModel.removeGameFolder(raw) },
                            ),
                        ) {
                            Text(str("setup.button.remove"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .controllerFocusable(
                        "rom-folders.add",
                        RoundedCornerShape(14.dp),
                        onConfirm = { folderPicker.launch(null) },
                    ),
            ) {
                Text(
                    if (state.gameFolders.isEmpty()) str("setup.button.pickRomsFolder")
                    else str("setup.button.addAnotherFolder"),
                )
            }
        }
    }
}
