// SPDX-License-Identifier: GPL-3.0+
package com.armsx2

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import java.io.File

/**
 * Application subclass that owns the Coil [ImageLoader] used app-wide for game
 * cover art. We override the default Coil disk cache for two reasons:
 *
 *  1. The default location is `cacheDir/image_cache`, which Android may wipe
 *     under memory pressure. Cover art is small, never changes, and worth
 *     keeping across cache-clear events — `filesDir/cover_cache` is the
 *     persistent equivalent.
 *  2. The default size is 2% of free space (max 250 MB). 100 MB is plenty
 *     for thousands of covers (typical PS1/PS2 cover JPEG ~30-80 KB) while
 *     not eating the user's storage.
 *
 * Cover requests in the UI use Coil's `SubcomposeAsyncImage`, which picks up
 * this default `ImageLoader` automatically — no changes needed at call sites.
 */
class Pasx2Application : Application(), ImageLoaderFactory {

	override fun onCreate() {
		super.onCreate()
		instance = this
		installCrashLogging()
		installTelemetry()
	}

	companion object {
		@Volatile private var instance: Pasx2Application? = null

		/**
		 * Contexto de aplicacao para codigo que sobrevive a Activity.
		 *
		 * O fork ja tem `MainActivityRuntime.instance?.applicationContext`, mas aquele `instance` e
		 * a Activity: some quando ela e destruida. O download de ROM roda num Service em primeiro
		 * plano justamente para continuar com o app fechado, entao precisa de um contexto que nao
		 * dependa de haver tela.
		 *
		 * Atribuido no onCreate da Application, portanto valido antes de qualquer componente.
		 */
		@JvmStatic
		fun appContext(): android.content.Context =
			instance ?: error("Pasx2Application.appContext() antes de onCreate")
	}

	/**
	 * Telemetria de producao (`/logErr`) — a metade que o upstream nao tem.
	 *
	 * `installCrashLogging()` acima grava arquivos locais: uteis quando alguem tem o aparelho na
	 * mao e sabe onde procurar. Isto e o outro caso, que e o comum: o usuario reporta "trava" e
	 * ninguem nunca ve o aparelho. Os tres pedacos que faltam sao enviar o relato, recuperar o
	 * crash NATIVO da sessao anterior via `ApplicationExitInfo`, e decodificar o tombstone
	 * protobuf — sem o decoder o relato chega como um blob binario inutil.
	 *
	 * Ordem importa em dois pontos:
	 *
	 *  - `TelemetryReporter.init` vem primeiro porque le o kill-switch. Nenhum relato pode sair
	 *    antes de sabermos se o usuario desligou o envio.
	 *  - `CrashReporter.install` vem DEPOIS de `installCrashLogging()`, e isso e deliberado: os
	 *    dois instalam um `UncaughtExceptionHandler` e cada um encadeia no anterior. Instalando
	 *    nesta ordem, o nosso roda primeiro e chama o deles em seguida, entao o arquivo local
	 *    continua sendo escrito. Inverter tambem funcionaria; o que NAO funciona e um dos dois
	 *    esquecer de encadear.
	 *
	 * Tudo aqui e best-effort e nao pode derrubar o boot — daí o `runCatching`, no mesmo espirito
	 * do metodo acima.
	 */
	private fun installTelemetry() {
		runCatching { com.armsx2.telemetry.TelemetryReporter.init(this) }
		runCatching { com.armsx2.telemetry.CrashReporter.install(this) }
		runCatching { com.armsx2.telemetry.CrashReporter.installAnrWatchdog() }
		runCatching { com.armsx2.telemetry.CrashReporter.uploadPendingAsync() }
		runCatching { com.armsx2.telemetry.CrashReporter.reportNativeExitsAsync() }
	}

	override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
		super.onConfigurationChanged(newConfig)
		com.armsx2.i18n.I18n.refreshSystemLanguage(this)
	}

	/**
	 * Diagnostics for the no-ADB case: some games (e.g. GoW2, LEGO Batman) drop
	 * back to the library on boot on some setups. We can't read logcat on the
	 * user's device, so:
	 *   1. Tee stdout/stderr — which carry the `@@ANDROID_LAUNCH_GAME@@` markers
	 *      and native Console output — into `<externalFilesDir>/logs/session.log`,
	 *      so even a native crash leaves a breadcrumb of the last thing attempted.
	 *   2. Install an uncaught-exception handler that dumps Kotlin/Java crashes to
	 *      `<externalFilesDir>/logs/crash-<time>.txt` with game + build context.
	 * Both are reachable via the in-app "Open data folder" action. Every step is
	 * guarded so the logger itself can never take the app down.
	 */
	private fun installCrashLogging() {
		runCatching {
			val logDir = File(getExternalFilesDir(null) ?: filesDir, "logs").apply { mkdirs() }

			runCatching {
				val sessionLog = File(logDir, "session.log")
				if (sessionLog.length() > 512L * 1024L) sessionLog.delete()
				val sink = java.io.PrintStream(java.io.FileOutputStream(sessionLog, true), true)
				System.setOut(TeePrintStream(System.out, sink))
				System.setErr(TeePrintStream(System.err, sink))
			}

			val previous = Thread.getDefaultUncaughtExceptionHandler()
			Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
				runCatching {
					File(logDir, "crash-${System.currentTimeMillis()}.txt").writeText(
						buildString {
							appendLine("ARMSX2 crash log")
							appendLine("time=${System.currentTimeMillis()}")
							appendLine("thread=${thread.name}")
							appendLine(
								"game=" + runCatching {
									com.armsx2.runtime.MainActivityRuntime.currentGame.value
										?.let { "${it.title} / ${it.serial}" }
								}.getOrNull(),
							)
							appendLine(
								"version=" + runCatching {
									packageManager.getPackageInfo(packageName, 0).versionName
								}.getOrNull(),
							)
							appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
							appendLine("---")
							appendLine(android.util.Log.getStackTraceString(throwable))
						},
					)
				}
				previous?.uncaughtException(thread, throwable)
			}
		}
	}

	override fun newImageLoader(): ImageLoader {
		val coverCacheDir = File(filesDir, "cover_cache").apply { mkdirs() }

		return ImageLoader.Builder(this)
			.components {
				// Animated library background support: decode animated GIF /
				// WebP / APNG. Android's ImageDecoder (API 28+) covers all
				// three; GifDecoder is the fallback for API 26-27. Static
				// images (JPEG/PNG covers) are unaffected.
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
					add(ImageDecoderDecoder.Factory())
				else
					add(GifDecoder.Factory())
			}
			.diskCache {
				DiskCache.Builder()
					.directory(coverCacheDir)
					// 100 MiB. Covers are tiny; this fits an enormous library.
					.maxSizeBytes(100L * 1024L * 1024L)
					.build()
			}
			.memoryCache {
				MemoryCache.Builder(this)
					// 25% of the app's memory budget (Coil default), but
					// explicit so it's clear what we're doing.
					.maxSizePercent(0.25)
					.build()
			}
			// Cache policy: keep both layers enabled. Read order is
			// memory → disk → network; write order is the inverse.
			.diskCachePolicy(CachePolicy.ENABLED)
			.memoryCachePolicy(CachePolicy.ENABLED)
			.networkCachePolicy(CachePolicy.ENABLED)
			// Game cover JPEGs from xlenore/* don't change once published,
			// so always serve from the disk cache after the first successful
			// fetch even if the server's Cache-Control says otherwise.
			.respectCacheHeaders(false)
			.crossfade(150)
			.build()
	}
}

/** PrintStream that mirrors everything to a second sink (the on-disk session log). */
private class TeePrintStream(
	private val primary: java.io.PrintStream,
	private val mirror: java.io.PrintStream,
) : java.io.PrintStream(primary) {
	override fun write(b: Int) {
		primary.write(b)
		runCatching { mirror.write(b) }
	}

	override fun write(buf: ByteArray, off: Int, len: Int) {
		primary.write(buf, off, len)
		runCatching { mirror.write(buf, off, len) }
	}

	override fun flush() {
		primary.flush()
		runCatching { mirror.flush() }
	}
}
