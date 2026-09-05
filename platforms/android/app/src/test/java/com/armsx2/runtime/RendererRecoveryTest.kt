package com.armsx2.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A escada de "a imagem não apareceu" (TASK-0082).
 *
 * Ela é derivada, não guardada: o próximo backend sai do que está gravado para o jogo mais o
 * veredito do `auto`. Isso é o que este teste pina — inclusive o caso em que o veredito não chegou,
 * que é o único ponto onde a escada supõe alguma coisa.
 */
class RendererRecoveryTest {

    private val glVerdict = "OpenGL reason=platform-default"
    private val vkVerdict = "Vulkan reason=driver-rule:gl-arm-g52-r38-auto-vulkan"

    @Test
    fun `veredito e lido dos dois formatos e devolve null quando ausente`() {
        assertEquals(RendererRecovery.OPENGL, RendererRecovery.autoBackendOf(glVerdict))
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.autoBackendOf(vkVerdict))
        assertNull(RendererRecovery.autoBackendOf(""))
        assertNull(RendererRecovery.autoBackendOf(null))
        // "not-resolved" é o que o nativo devolve antes de setAutoRendererGpuStrings; o prefixo
        // ainda diz o backend, então continua legível.
        assertEquals(RendererRecovery.OPENGL, RendererRecovery.autoBackendOf("OpenGL reason=not-resolved"))
        assertNull(RendererRecovery.autoBackendOf("lixo"))
    }

    /** O caso do A12: `auto` resolve para OpenGL, a tela fica preta, o passo é Vulkan. */
    @Test
    fun `de auto vai para o oposto do que o auto escolheu`() {
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.nextBackend(RendererRecovery.AUTO, glVerdict))
        assertEquals(RendererRecovery.OPENGL, RendererRecovery.nextBackend(RendererRecovery.AUTO, vkVerdict))
    }

    /** Fixado no mesmo que o `auto` escolheria: trocar é o passo útil, não pular para software. */
    @Test
    fun `do backend que o auto escolheria vai para o oposto`() {
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.nextBackend(RendererRecovery.OPENGL, glVerdict))
        assertEquals(RendererRecovery.OPENGL, RendererRecovery.nextBackend(RendererRecovery.VULKAN, vkVerdict))
    }

    /** A troca de backend já foi tentada e não resolveu: o degrau seguinte é o software. */
    @Test
    fun `do oposto vai para software`() {
        assertEquals(RendererRecovery.SOFTWARE, RendererRecovery.nextBackend(RendererRecovery.VULKAN, glVerdict))
        assertEquals(RendererRecovery.SOFTWARE, RendererRecovery.nextBackend(RendererRecovery.OPENGL, vkVerdict))
    }

    @Test
    fun `de software fecha o ciclo em auto`() {
        assertEquals(RendererRecovery.AUTO, RendererRecovery.nextBackend(RendererRecovery.SOFTWARE, glVerdict))
        assertEquals(RendererRecovery.AUTO, RendererRecovery.nextBackend(RendererRecovery.SOFTWARE, vkVerdict))
    }

    /**
     * Dois toques a partir de `auto` chegam ao software, e o terceiro devolve o `auto`. É o
     * ciclo inteiro, e é o que garante que a escada não fica presa alternando os dois backends de
     * hardware para sempre.
     */
    @Test
    fun `o ciclo fecha em tres toques`() {
        val first = RendererRecovery.nextBackend(RendererRecovery.AUTO, glVerdict)
        val second = RendererRecovery.nextBackend(first, glVerdict)
        val third = RendererRecovery.nextBackend(second, glVerdict)
        assertEquals(RendererRecovery.VULKAN, first)
        assertEquals(RendererRecovery.SOFTWARE, second)
        assertEquals(RendererRecovery.AUTO, third)
    }

    /**
     * Sem veredito o `auto` é tratado como OpenGL (o `platform-default` do core no Android), então
     * o primeiro passo é Vulkan. Suposição declarada, e só do primeiro toque.
     */
    @Test
    fun `sem veredito supoe OpenGL e propoe Vulkan`() {
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.nextBackend(RendererRecovery.AUTO, ""))
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.nextBackend(RendererRecovery.AUTO, null))
    }

    /** Um valor gravado que não existe mais (arquivo editado à mão, migração futura) não trava. */
    @Test
    fun `valor gravado desconhecido cai no primeiro degrau`() {
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.nextBackend("d3d12", glVerdict))
        assertEquals(RendererRecovery.VULKAN, RendererRecovery.nextBackend("", glVerdict))
    }

    // ---- a escada com ANGLE (TASK-0087) ------------------------------------

    private fun step(stored: String, angle: Boolean = false, verdict: String? = glVerdict,
                     available: Boolean = true) =
        RendererRecovery.nextStep(stored, angle, verdict, available)

    /**
     * O caminho inteiro no aparelho do relato: o `auto` resolve para OpenGL e fica preto, o Vulkan
     * perde o device, e o ANGLE é a saída EM HARDWARE que existia e estava fora da escada.
     */
    @Test
    fun `com auto em OpenGL a escada passa por Vulkan, ANGLE e so entao software`() {
        val primeiro = step(RendererRecovery.AUTO)
        assertEquals(RendererRecovery.VULKAN, primeiro.renderer)
        assertEquals(false, primeiro.useAngle)

        val segundo = step(primeiro.renderer)
        assertEquals(RendererRecovery.OPENGL, segundo.renderer)
        assertEquals(true, segundo.useAngle)

        val terceiro = step(segundo.renderer, angle = segundo.useAngle)
        assertEquals(RendererRecovery.SOFTWARE, terceiro.renderer)
        assertEquals(false, terceiro.useAngle)

        val quarto = step(terceiro.renderer)
        assertEquals(RendererRecovery.AUTO, quarto.renderer)
    }

    /** Com o `auto` em Vulkan o degrau do ANGLE mantém o renderizador e só liga a chave. */
    @Test
    fun `com auto em Vulkan o degrau do ANGLE e OpenGL com a chave ligada`() {
        val vk = "Vulkan reason=driver-rule:qualquer"
        val primeiro = step(RendererRecovery.AUTO, verdict = vk)
        assertEquals(RendererRecovery.OPENGL, primeiro.renderer)
        assertEquals(false, primeiro.useAngle)

        val segundo = step(primeiro.renderer, verdict = vk)
        assertEquals(RendererRecovery.OPENGL, segundo.renderer)
        assertEquals(true, segundo.useAngle)
    }

    /**
     * Sem as `.so` do ANGLE no APK o degrau é PULADO. Propor um passo que nao pode funcionar
     * transformaria a escada num beco: o usuario tocaria, reiniciaria e veria a mesma tela preta.
     */
    @Test
    fun `sem as bibliotecas do ANGLE o degrau e pulado`() {
        val semAngle = step(RendererRecovery.VULKAN, available = false)
        assertEquals(RendererRecovery.SOFTWARE, semAngle.renderer)
        assertEquals(false, semAngle.useAngle)
    }

    /** A chave do ANGLE nao pode ficar pendurada no degrau seguinte. */
    @Test
    fun `sair do degrau do ANGLE desliga a chave`() {
        val depois = step(RendererRecovery.OPENGL, angle = true)
        assertEquals(RendererRecovery.SOFTWARE, depois.renderer)
        assertEquals(false, depois.useAngle)
    }

    /**
     * `useAngle` gravado com um renderizador que nao e OpenGL e estado incoerente -- `AngleDriver`
     * ja o trata como `Off`. A escada nao pode se perder nele.
     */
    @Test
    fun `chave do ANGLE com renderizador que nao e OpenGL nao trava a escada`() {
        val vindoDoVulkan = step(RendererRecovery.VULKAN, angle = true)
        assertEquals(RendererRecovery.OPENGL, vindoDoVulkan.renderer)
        assertEquals(true, vindoDoVulkan.useAngle)
    }
}
