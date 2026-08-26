package com.armsx2.update

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.armsx2.BuildConfig
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.SettingSwitchRow
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.launch
import com.armsx2.updates.AppUpdateManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Updater in-app — flavor de sideload (github) APENAS. Desenha o painel "procurar atualizacoes" na
 * tela Sobre. O flavor play recebe o stub vazio em src/play, entao nem este codigo, nem
 * REQUEST_INSTALL_PACKAGES, nem o FileProvider entram no AAB — e o build-play-aab.sh falha fechado
 * se a permissao algum dia vazar para la.
 *
 * **A UI e deles; a fonte de dados e nossa.** O original consultava a API de releases do GitHub do
 * ARMSX2 e escolhia o APK por marcadores no nome do arquivo. Este consulta o `version.json` do
 * canal de distribuicao do RetroSystem PS2 e delega tudo ao AppUpdateManager, que verifica o
 * SHA-256 antes de instalar — coisa que o original nao fazia.
 *
 * O conceito de "nightly" saiu junto: la era um pre-release diario do workflow `nightly.yml`, com
 * versionCode = segundos Unix para ficar sempre a frente de qualquer estavel. Nao ha equivalente do
 * nosso lado, e um toggle sem o que oferecer e pior que nenhum.
 */

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /**
     * `info` e o que o nosso AppUpdateManager devolveu: versionCode, apkUrl, sha256 e tamanho.
     * A UI so mostra `version` e `notes`, mas o download precisa do resto -- sobretudo do sha256,
     * sem o qual nao ha como recusar um APK errado.
     */
    data class Available(
        val version: String,
        val notes: String,
        val info: AppUpdateManager.UpdateInfo,
    ) : UpdateState
    data class Downloading(val pct: Int) : UpdateState
    data class Error(val msg: String) : UpdateState
}

@Composable
fun UpdaterEntry() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    // str() is @Composable, so resolve the strings the background check / onClick handlers need
    // here and capture them (they run outside composition).
    val checkFailedPrefix = str("update.checkFailed")
    val downloadFailedPrefix = str("update.downloadFailed")

    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Column(Modifier.padding(4.dp)) {
            Text(str("update.title"), style = MaterialTheme.typography.titleMedium)
            Text(
                "${str("update.currentVersion")}: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(str("update.checking"), style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.UpToDate -> Text(
                    str("update.upToDate"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is UpdateState.Error -> Text(
                    s.msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is UpdateState.Downloading -> Column {
                    Text("${str("update.downloading")} ${s.pct}%", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { s.pct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {}
            }
            Spacer(Modifier.height(8.dp))
            // Extracted so the controller's confirm and the touch onClick share one action, and
            // the button joins the nav registry ("update.check") — the whole updater section was
            // touch-only before.
            val runCheck: () -> Unit = {
                scope.launch {
                    state = UpdateState.Checking
                    state = checkForUpdate(context, checkFailedPrefix)
                }
            }
            Button(
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                onClick = runCheck,
                modifier = Modifier.controllerFocusable("update.check", onConfirm = runCheck),
            ) { Text(str("update.check")) }

            // Opt-in: silently check GitHub for a newer release on every app launch (default off).
            var checkOnLaunch by remember {
                mutableStateOf(MainActivityRuntime.prefs.getBoolean("update.checkOnLaunch", false))
            }
            SettingSwitchRow(
                title = str("update.checkOnLaunch"),
                description = str("update.checkOnLaunch.desc"),
                checked = checkOnLaunch,
                onCheckedChange = {
                    checkOnLaunch = it
                    MainActivityRuntime.prefs.edit().putBoolean("update.checkOnLaunch", it).apply()
                },
            )

            // O toggle "incluir builds nightly" foi removido junto com o updater do GitHub deles.
            // Nightly, la, era um pre-release diario publicado pelo workflow nightly.yml, com
            // versionCode = segundos Unix para ficar sempre a frente de qualquer estavel. O nosso
            // canal de distribuicao publica UM version.json, com channel="default" e a serie
            // 38, 39... Manter o switch seria manter um controle que nao tem o que oferecer -- o
            // mesmo defeito que este projeto ja catalogou duas vezes (isNativeInitializationSucceeded
            // sem consumidor, o toggle de gravar log que nao ligava log nenhum).
        }
    }

    (state as? UpdateState.Available)?.let { avail ->
        AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text("${str("update.available")}  ${avail.version}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        avail.notes.ifBlank { str("update.notesUnavailable") },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            downloadAndInstall(context, avail) { pct -> state = UpdateState.Downloading(pct) }
                            state = UpdateState.Idle
                        } catch (e: Exception) {
                            state = UpdateState.Error("$downloadFailedPrefix: ${e.message}")
                        }
                    }
                }) { Text(str("update.install")) }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateState.Idle }) { Text(str("action.cancel")) }
            },
        )
    }
}

/**
 * Boot-time auto-check (github flavor only). Mounted once at the app root; when the "check on
 * launch" toggle is on, it runs a single silent GitHub check on start and pops the update prompt
 * ONLY if a newer release exists — no "up to date" popup, no noise on every boot. Reuses the exact
 * check/download/install path as the manual button. Nightly-safe via checkForUpdate's VC guard.
 */
@Composable
fun AutoUpdateGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val checkFailedPrefix = str("update.checkFailed")

    LaunchedEffect(Unit) {
        if (MainActivityRuntime.prefs.getBoolean("update.checkOnLaunch", false)) {
            val result = checkForUpdate(context, checkFailedPrefix)
            if (result is UpdateState.Available) state = result  // stay silent on up-to-date / errors
        }
    }

    val s = state
    if (s is UpdateState.Available || s is UpdateState.Downloading) {
        val avail = s as? UpdateState.Available
        AlertDialog(
            onDismissRequest = { if (state !is UpdateState.Downloading) state = UpdateState.Idle },
            title = {
                Text(
                    if (state is UpdateState.Downloading) str("update.downloading")
                    else "${str("update.available")}  ${avail?.version.orEmpty()}",
                )
            },
            text = {
                when (val cur = state) {
                    is UpdateState.Available -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        Text(cur.notes.ifBlank { str("update.notesUnavailable") }, style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloading -> Column {
                        Text("${cur.pct}%", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { cur.pct / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {}
                }
            },
            confirmButton = {
                if (avail != null) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                downloadAndInstall(context, avail) { pct -> state = UpdateState.Downloading(pct) }
                            } finally {
                                state = UpdateState.Idle
                            }
                        }
                    }) { Text(str("update.install")) }
                }
            },
            dismissButton = {
                if (state is UpdateState.Available) {
                    TextButton(onClick = { state = UpdateState.Idle }) { Text(str("update.later")) }
                }
            },
        )
    }
}

/**
 * Consulta o NOSSO canal de distribuicao, nao a API de releases do GitHub deles.
 *
 * O que mudou, e por que: o updater original buscava
 * `api.github.com/repos/ARMSX2/ARMSX2/releases` e escolhia o APK por marcadores no nome do
 * arquivo (`-sdk26`, `-sdk30`, ...). O RetroSystem PS2 distribui de outro lugar e de outro jeito:
 * um unico `version.json` no R2, com `versionCode`, `apkUrl`, `sha256` e `size`.
 *
 * A diferenca que mais importa nao e o endereco -- e o **sha256**. O updater deles nao verifica
 * hash nenhum. O nosso verifica, e isso nao e zelo abstrato: a URL canonica do APK, atras do cache
 * de borda, continua servindo os bytes da versao ANTERIOR por um tempo depois do upload, ignorando
 * `Cache-Control`. Sem a verificacao, o app instala o APK errado sem perceber.
 *
 * Toda a mecanica (comparar versionCode, conferir o canal, baixar com resume, verificar o hash,
 * instalar pelo PackageInstaller) ja vive no AppUpdateManager, que roda numa thread propria e
 * responde por callback. Aqui so ha a ponte para corrotina, para a UI Compose deles continuar
 * intacta.
 */
private suspend fun checkForUpdate(context: Context, checkFailedPrefix: String): UpdateState =
    suspendCancellableCoroutine { cont ->
        AppUpdateManager(context).checkForUpdate(object : AppUpdateManager.CheckCallback {
            override fun onUpdateAvailable(info: AppUpdateManager.UpdateInfo) {
                // O version.json nao carrega notas de versao hoje; a UI cai no
                // "update.notesUnavailable" dela mesma. Acrescentar um campo `notes` no publicador
                // e o caminho, se um dia quisermos mostrar o changelog.
                if (cont.isActive) cont.resume(UpdateState.Available(info.versionName, "", info))
            }

            override fun onUpToDate() {
                if (cont.isActive) cont.resume(UpdateState.UpToDate)
            }

            override fun onError(message: String?) {
                if (cont.isActive) cont.resume(UpdateState.Error("$checkFailedPrefix: $message"))
            }
        })
    }

/**
 * Baixa, VERIFICA O SHA-256 e instala. Delega ao AppUpdateManager pela mesma razao acima.
 *
 * `onInstallStarted` e o fim da nossa parte: dali em diante quem conduz e o PackageInstaller, e a
 * tela "deseja instalar?" chega pelo UpdateInstallReceiver (registrado no manifesto do flavor
 * github). Sem aquele receiver a atualizacao baixa, passa na verificacao e simplesmente nao
 * acontece -- sem erro e sem tela.
 */
private suspend fun downloadAndInstall(
    context: Context,
    info: UpdateState.Available,
    onProgress: (Int) -> Unit,
) = suspendCancellableCoroutine { cont ->
    AppUpdateManager(context).downloadAndInstall(info.info, object : AppUpdateManager.InstallCallback {
        override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
            if (totalBytes > 0) onProgress(((bytesDownloaded * 100) / totalBytes).toInt())
        }

        override fun onInstallStarted() {
            if (cont.isActive) cont.resume(Unit)
        }

        override fun onError(message: String?) {
            // A UI deles espera excecao neste caminho (o `catch` que monta o
            // "update.downloadFailed"), entao o erro tem de voltar como excecao, nao como estado.
            if (cont.isActive) cont.cancel(IllegalStateException(message ?: "download failed"))
        }
    })
}
