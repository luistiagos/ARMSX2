package com.armsx2.runtime

import com.armsx2.runtime.AngleDriver.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A decisão de rodar o OpenGL por ANGLE (TASK-0083).
 *
 * O que este teste pina é o que o defeito original violava: a decisão depende do renderizador
 * **resolvido para o jogo**, e os três desfechos são distinguíveis — porque o log precisa dizer
 * "o usuário não pediu" e "o usuário pediu e a `.so` não veio no APK" com palavras diferentes.
 */
class AngleDriverTest {

    @Test
    fun `liga quando o renderizador resolvido e opengl e as duas so existem`() {
        assertEquals(
            Decision.Enabled,
            AngleDriver.decide("opengl", useAngle = true, eglPresent = true, glesPresent = true),
        )
    }

    @Test
    fun `nao liga quando a chave esta desligada`() {
        assertEquals(
            Decision.Off,
            AngleDriver.decide("opengl", useAngle = false, eglPresent = true, glesPresent = true),
        )
    }

    @Test
    fun `nao liga fora do opengl`() {
        // `auto` inclusive: o backend só é escolhido dentro do core, depois que a .so de EGL já
        // teria de estar decidida.
        for (renderer in listOf("vulkan", "software", "auto", "", null)) {
            assertEquals(
                "renderer=$renderer",
                Decision.Off,
                AngleDriver.decide(renderer, useAngle = true, eglPresent = true, glesPresent = true),
            )
        }
    }

    @Test
    fun `so ausente e um desfecho proprio, nao um desligamento silencioso`() {
        assertEquals(
            Decision.MissingLibs,
            AngleDriver.decide("opengl", useAngle = true, eglPresent = false, glesPresent = true),
        )
        assertEquals(
            Decision.MissingLibs,
            AngleDriver.decide("opengl", useAngle = true, eglPresent = true, glesPresent = false),
        )
        assertEquals(
            Decision.MissingLibs,
            AngleDriver.decide("opengl", useAngle = true, eglPresent = false, glesPresent = false),
        )
    }

    @Test
    fun `so ausente com a chave desligada continua sendo desligado`() {
        // A ordem importa: sem esta linha, "MissingLibs" poderia vazar para quem nunca pediu ANGLE
        // e encher o log de um aviso que não é sobre nada.
        assertEquals(
            Decision.Off,
            AngleDriver.decide("opengl", useAngle = false, eglPresent = false, glesPresent = false),
        )
    }
}
