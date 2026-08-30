package com.armsx2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.armsx2.i18n.I18n
import com.armsx2.ui.WelcomeBanner
import java.io.File

/**
 * Diz ao usuário, uma vez, quando o APARELHO está segurando o clock da CPU durante o jogo.
 *
 * Medido no SM-A127M (registro completo em
 * `docs/bugs/open/gos-samsung-limita-clock-a-metade-em-jogo_2026-08-29T12-40.md`): o Game
 * Optimizing Service da Samsung prende os 8 núcleos em 1053 MHz de 2002 MHz enquanto o nosso jogo
 * está em primeiro plano, e a emulação cai de 49,8 fps para 8,6 fps. Desabilitar o pacote do GOS
 * devolve a velocidade cheia; nenhuma das quatro tratativas do lado do app muda alguma coisa —
 * inclusive `android.game_mode_config`, porque o Game Mode do Android governa só downscaling de
 * backbuffer e override de fps, e não frequência de CPU.
 *
 * Como não há correção possível daqui, o que este vigia entrega é a única coisa útil que sobra:
 * o app deixa de parecer culpado por uma lentidão que não é dele. Ele não executa nada no sistema
 * e não pretende poder — desabilitar o GOS exige um PC.
 */
object ThrottleWatcher {
    /** Um diretório por cluster. Cada um traz o próprio teto e o próprio clock corrente. */
    private const val CPUFREQ_ROOT = "/sys/devices/system/cpu/cpufreq"
    /** Aparelho de política única (sem `policy*`) ainda expõe o cluster do cpu0 aqui. */
    private const val CPU0_CPUFREQ = "/sys/devices/system/cpu/cpu0/cpufreq"

    private const val PREF_KEY = "throttle.warnings"
    private const val PREF_WARNED = "throttle.warned"
    /** Houve corte neste aparelho. Diferente de [PREF_WARNED]: este não é rearmado por ninguém —
     *  é fato sobre o aparelho, e é o que decide se a ação de conserto tem por que existir. */
    private const val PREF_DETECTED = "throttle.detected"

    /**
     * O serviço da Samsung que aplica o corte. Só o nome do pacote: o app não o desabilita — a
     * permissão para isso (`CHANGE_COMPONENT_ENABLED_STATE`) é `signature|privileged|role` —, ele
     * apenas abre a página onde o usuário pode forçar a parada.
     */
    private const val VENDOR_THROTTLER_PACKAGE = "com.samsung.android.game.gos"

    private const val SAMPLE_MS = 3_000L
    /** Amostras COM A EMULAÇÃO ATRASADA antes de julgar — cerca de um minuto de jogo lento. */
    private const val MIN_SLOW_SAMPLES = 20
    /** Um cluster que nunca passa desta fração do próprio teto está sendo segurado. */
    private const val CEILING_RATIO = 0.70f
    /** Abaixo disto a emulação está atrasada; em dia, um clock baixo é escolha certa do governador. */
    private const val SLOW_BELOW_PCT = 92f

    /** Interruptor em Configurações → Aplicativo. Ligado por padrão. */
    val enabled = androidx.compose.runtime.mutableStateOf(true)

    /** Já se mediu um corte neste aparelho. Enquanto for falso, oferecer conserto é chute. */
    val detected = androidx.compose.runtime.mutableStateOf(false)

    /** Teto de hardware de um cluster, e o maior clock que ele alcançou nesta sessão. */
    private class Cluster(val cur: File, val maxKHz: Int) {
        var peakKHz = 0
    }

    @Volatile
    private var sampler: Thread? = null

    /** O sysfs não abriu (SELinux varia por aparelho): desliga e não tenta de novo. */
    private var sysfsUnreadable = false

    fun load() {
        runCatching {
            val prefs = com.armsx2.runtime.MainActivityRuntime.prefs
            enabled.value = prefs.getBoolean(PREF_KEY, true)
            detected.value = prefs.getBoolean(PREF_DETECTED, false)
        }
    }

    /**
     * Há uma ação concreta a oferecer: mediu-se corte E o aparelho é daquele fabricante cuja tela
     * de conserto eu conheço. Fora da Samsung o app sabe que há um teto, não quem o pôs — mandar
     * o usuário a uma tela adivinhada seria pior que não mandar.
     */
    fun vendorFixAvailable(): Boolean = detected.value && isSamsung()

    private fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * Abre a página de informações do serviço que aplica o corte, onde o botão **Forçar parada**
     * devolve a velocidade cheia (medido: 8,5 fps → 49,8 fps, e o processo não voltou sozinho em
     * 100 s de jogo). O **Desativar** dessa mesma página está morto no aparelho medido, então a
     * instrução que acompanha o botão fala em forçar parada, não em desativar.
     *
     * Devolve `false` — e diz isso ao usuário — quando nada resolve a intent, em vez de estourar
     * `ActivityNotFoundException` em cima de quem só queria entender por que o jogo está lento.
     */
    fun openVendorThrottlerSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", VENDOR_THROTTLER_PACKAGE, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) WelcomeBanner.show(I18n.get("throttle.fix.unavailable"))
        return ok
    }

    fun set(value: Boolean) {
        enabled.value = value
        runCatching {
            val editor = com.armsx2.runtime.MainActivityRuntime.prefs.edit()
                .putBoolean(PREF_KEY, value)
            // Religar o aviso rearma quem já foi avisado: é a única forma de o usuário vê-lo de
            // novo depois de dispensá-lo, já que o aviso é de uma vez por instalação.
            if (value) editor.putBoolean(PREF_WARNED, false)
            editor.apply()
        }
    }

    /** Começa a vigiar. Chamado quando a emulação começa (jogo ou BIOS). Idempotente. */
    fun start() {
        if (!enabled.value || sysfsUnreadable || sampler != null) return
        if (alreadyWarned()) return
        val clusters = readClusters()
        if (clusters.isEmpty()) {
            sysfsUnreadable = true
            return
        }
        val t = Thread({ sample(clusters) }, "ARMSX2-ThrottleWatch")
        t.isDaemon = true
        sampler = t
        t.start()
    }

    /** Para de vigiar. Chamado do caminho terminal único de volta à biblioteca. Idempotente. */
    fun stop() {
        sampler?.interrupt()
        sampler = null
    }

    private fun alreadyWarned(): Boolean = runCatching {
        com.armsx2.runtime.MainActivityRuntime.prefs.getBoolean(PREF_WARNED, false)
    }.getOrDefault(false)

    /**
     * Um [Cluster] por policy. `cpuinfo_max_freq` é o teto de hardware — e não `scaling_max_freq`,
     * que no aparelho medido continuava em 2002000 enquanto o clock real não passava de 1053000:
     * o corte do GOS não aparece no teto da política.
     */
    private fun readClusters(): List<Cluster> = runCatching {
        val dirs = File(CPUFREQ_ROOT).listFiles { f -> f.isDirectory && f.name.startsWith("policy") }
            ?.toList()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(File(CPU0_CPUFREQ))
        dirs.mapNotNull { dir ->
            val max = readKHz(File(dir, "cpuinfo_max_freq")) ?: return@mapNotNull null
            val cur = File(dir, "scaling_cur_freq")
            if (max <= 0 || readKHz(cur) == null) null else Cluster(cur, max)
        }
    }.getOrDefault(emptyList())

    private fun readKHz(f: File): Int? =
        runCatching { f.readText().trim().toIntOrNull() }.getOrNull()

    private fun sample(clusters: List<Cluster>) {
        var slowSamples = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(SAMPLE_MS)
                // 0 = sem VM válida (pausado, ainda bootando, já encerrado). Não há o que julgar.
                val speed = runCatching { kr.co.iefriends.pcsx2.NativeApp.getEmuSpeedPercent() }
                    .getOrDefault(0f)
                if (speed <= 0f) continue

                // O pico conta em TODA amostra com VM: basta um cluster ter alcançado o teto uma
                // vez para provar que o aparelho não está preso.
                for (c in clusters) readKHz(c.cur)?.let { if (it > c.peakKHz) c.peakKHz = it }

                if (speed >= SLOW_BELOW_PCT) continue
                if (++slowSamples < MIN_SLOW_SAMPLES) continue

                val held = clusters.all { it.peakKHz <= (it.maxKHz * CEILING_RATIO).toInt() }
                if (held) {
                    warn(clusters, speed)
                    return
                }
                // Alcançou o teto em algum cluster: a lentidão é outra coisa, e este vigia não tem
                // nada a dizer sobre ela. Sai em vez de reavaliar para sempre.
                return
            }
        } catch (_: InterruptedException) {
            // stop() durante o sleep — saída normal.
        } finally {
            if (sampler === Thread.currentThread()) sampler = null
        }
    }

    private fun warn(clusters: List<Cluster>, speed: Float) {
        val top = clusters.maxByOrNull { it.maxKHz } ?: return
        val pct = (top.peakKHz * 100 / top.maxKHz).coerceIn(0, 100)
        // O suporte precisa enxergar o mesmo número que o usuário viu.
        println(
            "@@ANDROID_THROTTLE@@ peakKHz=${top.peakKHz} maxKHz=${top.maxKHz} pct=$pct " +
                "speed=${speed.toInt()} manufacturer=${Build.MANUFACTURER}"
        )
        detected.value = true
        runCatching {
            com.armsx2.runtime.MainActivityRuntime.prefs.edit()
                .putBoolean(PREF_WARNED, true)
                .putBoolean(PREF_DETECTED, true)
                .apply()
        }
        // Nomear o culpado só onde ele foi medido. Fora da Samsung o app sabe que HÁ um teto, não
        // quem o pôs — e chutar transformaria uma medição em boato.
        val key = if (isSamsung()) "throttle.warn.samsung" else "throttle.warn"
        val text = runCatching { I18n.get(key).format(pct) }.getOrDefault("")
        if (text.isEmpty()) return
        val activity = com.armsx2.runtime.MainActivityRuntime.instance ?: return
        activity.runOnUiThread { WelcomeBanner.show(text) }
    }
}
