package com.armsx2

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Portao de entrada do app: e esta a activity do LAUNCHER e a que recebe o "abrir com" de um
 * arquivo de ROM (intent VIEW). Hoje ela so encaminha para a Main.
 *
 * O que havia aqui era a reproducao de `res/raw/boot_intro.mp4`, o video de abertura com a marca
 * do ARMSX2. Ele foi removido junto com o arquivo e com o toggle "Boot animation" das
 * Configuracoes: era identidade DELES numa tela em que o usuario ve o nome do nosso produto. Nao
 * substituimos por outro video -- nao ha um, e uma abertura de 2 s que ninguem pediu custa 2 s a
 * cada arranque.
 *
 * A activity fica porque nao da para apaga-la: e o ponto de entrada declarado no manifesto.
 */
class BootSplashActivity : ComponentActivity() {
    private var launchedMain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // O tema do manifesto (Theme.ARMSX2.Boot) ja pinta a janela de preto, entao um aparelho em
        // modo claro nao pisca branco entre o icone e a Main.
        super.onCreate(savedInstanceState)
        applyImmersiveUi()
        launchMainAndFinish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveUi()
    }

    private fun applyImmersiveUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun launchMainAndFinish() {
        if (launchedMain) return
        launchedMain = true
        val launch = Intent(this, Main::class.java)
        intent?.let { source ->
            launch.action = source.action
            if (source.data != null || source.type != null) launch.setDataAndType(source.data, source.type)
            source.categories?.forEach(launch::addCategory)
            source.extras?.let(launch::putExtras)
            source.clipData?.let(launch::setClipData)
            launch.addFlags(
                source.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    ),
            )
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launch)
        finish()
        // overrideActivityTransition is API 34 (Android 14); on 13 and below it
        // throws NoSuchMethodError (crashed the splash on the Retroid). Fall back to
        // the deprecated overridePendingTransition there.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
    }
}
