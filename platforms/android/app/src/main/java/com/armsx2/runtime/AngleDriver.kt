package com.armsx2.runtime

/**
 * Decide se o renderizador OpenGL desta sessão deve passar por **ANGLE** (GLES-on-Vulkan) em vez do
 * driver GLES do sistema.
 *
 * ## Por que isso importa, com número
 *
 * Medido em 2026-09-04 no `SM-A127M` (Mali-G52, driver ARM r38p1), 007: Everything or Nothing,
 * `renderer=opengl`, upscale 1,25x — o relatório é
 * `docs/bugs/open/armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida`:
 *
 * | braço | `GL_VENDOR` | linha de GS do OSD | resultado |
 * |---|---|---|---|
 * | driver do sistema | `ARM` | `50216 PRIM │ 50 DRW │ 53 DRWC │ 0 BAR │ 11 RP │ 0 RB │ 5 TC │ 12 TU` | preto |
 * | ANGLE | `Google Inc. (ARM)` | **a mesma linha** | imagem |
 *
 * O emulador emite o mesmo trabalho nos dois braços. Quem difere é o driver. Por isso ANGLE é o
 * contorno certo para esse aparelho e trocar de backend não é: trocar para Vulkan leva junto uma
 * mudança de semântica de emulação (o piso de Z do PS2 que o Mali descarta no Vulkan e não descarta
 * no OpenGL), e ninguém decidiu isso.
 *
 * ## Por que a decisão é uma função pura
 *
 * Para poder ser testada sem aparelho, e para que o log diga exatamente **qual** dos três desfechos
 * ocorreu. O silêncio entre "o usuário não pediu ANGLE" e "o usuário pediu e a `.so` não estava no
 * APK" é o que impediu de diagnosticar o relato de ANGLE da 2.6.3 a partir de um log.
 */
object AngleDriver {

    /** O que fazer com as variáveis de ambiente `ARMSX2_ANGLE_*`. */
    enum class Decision {
        /** Apontar para as `.so` do ANGLE. */
        Enabled,

        /** O usuário pediu ANGLE, mas as bibliotecas não estão no diretório nativo do APK. */
        MissingLibs,

        /** Limpar as variáveis: ou o usuário não pediu, ou o renderizador não é OpenGL. */
        Off,
    }

    /**
     * [renderer] é o renderizador **já resolvido para este jogo** (per-game ∘ global), não o global
     * cru: quem fixa `opengl` só neste título tem tanto direito ao ANGLE quanto quem fixou no
     * global. `auto` deliberadamente **não** liga ANGLE — o backend só é decidido dentro do core,
     * depois que a `.so` de EGL já teria de estar escolhida.
     */
    fun decide(
        renderer: String?,
        useAngle: Boolean,
        eglPresent: Boolean,
        glesPresent: Boolean,
    ): Decision = when {
        !useAngle || renderer != "opengl" -> Decision.Off
        eglPresent && glesPresent -> Decision.Enabled
        else -> Decision.MissingLibs
    }
}
