package com.armsx2.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Controller-navigable on-screen keyboard for library search.
 *
 * The system IME can't be driven by a D-pad — Compose consumes D-pad key events for
 * focus traversal before the keyboard ever sees them — so on a handheld the only
 * reliable controller text entry is our own key grid. Touch still uses the normal
 * system keyboard when you tap the search field; this is the CONTROLLER path, opened by
 * A on the Search zone and driven from dispatchKeyEvent while [visible]. The keys are
 * also tappable (50dp tall, weight-filled), so touch works here too.
 */
object LibraryKeyboard {
    val visible = mutableStateOf(false)
    val row = mutableIntStateOf(0)
    val col = mutableIntStateOf(0)

    /** Caps-lock toggle: letter keys emit + render uppercase while true. Sticky until toggled off. */
    val shifted = mutableStateOf(false)

    /** Live buffer, seeded from the current query on open; every edit pushes to onChange. */
    val text = mutableStateOf("")
    private var onChange: (String) -> Unit = {}
    /** Empty-buffer hint; caller-set so the same keyboard serves library + settings search. */
    private val placeholder = mutableStateOf("Search games…")

    // Cached strings to avoid querying I18n on every composition pass
    private var cachedSpace: String? = null
    private var cachedClear: String? = null
    private var cachedDone: String? = null

    // Special keys carry multi-char labels; letter keys are single chars.
    const val SPACE = "space"
    const val BACKSPACE = "⌫"
    const val CLEAR = "clear"
    const val DONE = "done"
    const val SHIFT = "shift"

    val rows: List<List<String>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf(SHIFT, "z", "x", "c", "v", "b", "n", "m"),
        listOf(SPACE, BACKSPACE, CLEAR, DONE),
    )

    /** Use the Android system IME instead of these on-screen keys. Opt-in: the built-in keyboard
     *  stays the default because it is fully gamepad-navigable, whereas the system IME is not (and
     *  Android IMEs never deliver key events to the emulated PS2 pad at all). Switching here rather
     *  than at each call site means the library search and the rename field both follow the
     *  setting with no changes of their own. */
    private const val SystemImeKey = "ui.useSystemKeyboard"
    val useSystemIme = mutableStateOf(false)

    fun setUseSystemIme(on: Boolean) {
        useSystemIme.value = on
        com.armsx2.runtime.MainActivityRuntime.prefs.edit().putBoolean(SystemImeKey, on).apply()
    }

    /** Load the stored preference into [useSystemIme]. Called from open() and from the Settings
     *  row, which can be composed before the keyboard has ever been opened. */
    fun refreshUseSystemIme(): Boolean {
        useSystemIme.value = runCatching {
            com.armsx2.runtime.MainActivityRuntime.prefs.getBoolean(SystemImeKey, false)
        }.getOrDefault(false)
        return useSystemIme.value
    }

    private fun refreshCachedStrings() {
        cachedSpace = runCatching { com.armsx2.i18n.I18n.get("keyboard.space") }.getOrDefault("Space")
        cachedClear = runCatching { com.armsx2.i18n.I18n.get("keyboard.clear") }.getOrDefault("Clear")
        cachedDone = runCatching { com.armsx2.i18n.I18n.get("keyboard.done") }.getOrDefault("Done")
    }

    fun open(initial: String, onChange: (String) -> Unit, placeholder: String = "Search games…") {
        text.value = initial
        this.onChange = onChange
        this.placeholder.value = placeholder
        // Re-read on every open so the preference needs no wiring into app startup, and so a
        // change made in Settings takes effect the next time the keyboard is summoned.
        refreshUseSystemIme()
        refreshCachedStrings()
        row.intValue = 0
        col.intValue = 0
        shifted.value = false
        visible.value = true
    }

    fun close() { visible.value = false }

    fun move(dx: Int, dy: Int) {
        val br = row.intValue; val bc = col.intValue
        if (dy != 0) {
            row.intValue = (row.intValue + dy).coerceIn(0, rows.size - 1)
        }
        // Clamp the column into the (possibly shorter) target row after any move.
        col.intValue = (col.intValue + dx).coerceIn(0, rows[row.intValue].size - 1)
        if (row.intValue != br || col.intValue != bc)
            com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.NAV)
    }

    /** Press the currently-highlighted key. */
    fun press() {
        val key = rows[row.intValue].getOrNull(col.intValue) ?: return
        pressKey(key)
    }

    fun pressKey(key: String) {
        // A tick per key (touch or controller); Done gets the confirm blip.
        com.armsx2.MenuSfx.play(if (key == DONE) com.armsx2.MenuSfx.Event.SELECT else com.armsx2.MenuSfx.Event.NAV)
        if (key == SHIFT) { shifted.value = !shifted.value; return }
        val next = when (key) {
            SPACE -> text.value + " "
            BACKSPACE -> text.value.dropLast(1)
            CLEAR -> ""
            DONE -> { close(); return }
            else -> text.value + (if (shifted.value) key.uppercase() else key)
        }
        text.value = next
        onChange(next)
    }

    /** Convenience hardware shortcut (e.g. X = backspace) from dispatchKeyEvent. */
    fun backspace() = pressKey(BACKSPACE)

    private fun weightOf(key: String): Float = when (key) {
        SPACE -> 4f
        DONE -> 2f
        BACKSPACE, CLEAR, SHIFT -> 1.6f
        else -> 1f
    }

    private fun glyphOf(key: String, isShifted: Boolean): String = when (key) {
        SPACE -> cachedSpace ?: "Space"
        BACKSPACE -> "⌫"
        CLEAR -> cachedClear ?: "Clear"
        DONE -> cachedDone ?: "Done"
        SHIFT -> "⇧"
        else -> if (isShifted) key.uppercase() else key
    }

    @Composable
    fun Overlay(scope: BoxScope) {
        val isVisible = visible.value
        val isShifted = shifted.value
        // row/col NAO sao lidos aqui de proposito -- ver KeyCap. Uma leitura neste corpo invalida o
        // escopo reiniciavel mais proximo, que contem as cinco linhas: as quarenta teclas
        // recomporiam para trocar a cor de uma. Medido em ~32 ms por tecla no A12 (TASK-0068).

        with(scope) {
            // Tap-catcher behind the panel: a tap anywhere outside closes the keyboard, the same
            // exit as Done and BACK. Transparent, and deliberately NOT inside AnimatedVisibility.
            //
            // 1. WHY NOT AnimatedVisibility + matchParentSize(). That is what this was, and it did
            //    nothing at all. matchParentSize() is parent data, and the only thing that reads it
            //    is Box's own MeasurePolicy; the parent inside AnimatedVisibility is not that Box
            //    but a Layout running AnimatedEnterExitMeasurePolicy, which measures each child
            //    with the incoming constraints and never looks at parentData (verified against
            //    animation-android 1.11.4). The parent data was dropped in silence, the Box wrapped
            //    its empty content, and it measured 0 x 0 — no touch target, so "tap outside to
            //    close" was written but never existed. Bug:
            //    docs/bugs/open/teclado-virtual-scrim-de-tamanho-zero. fillMaxSize() takes the max
            //    constraints, which get passed straight through, so it does not care who the parent
            //    is. Plain `if` rather than AnimatedVisibility so the catcher disappears with the
            //    state instead of outliving it through the 90 ms exit — otherwise the tap that
            //    dismissed the keyboard would be followed by a second, dead one.
            //
            // 2. WHY NO DIM. The 0.5 black this used to ask for never rendered (see 1), so no build
            //    has ever shown it. Meanwhile the two hosts that hand text entry over — the
            //    settings search and a PadModal naming a preset — draw their own veil under their
            //    own panel, and this host is composed ABOVE both. A scrim here would dim the very
            //    list the user is typing to filter. A host that wants the room dark darkens it.
            if (isVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { close() },
                        ),
                )
            }

            // Keyboard panel with slide & fade (needs proper testing)
            AnimatedVisibility(
                visible = isVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 3 },
                exit = fadeOut(tween(90)) + slideOutVertically(tween(90)) { it / 3 },
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                ) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (useSystemIme.value) {
                            SystemImeField()
                            return@Column
                        }
                        TypedPreview()
                        rows.forEachIndexed { r, keys ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                keys.forEachIndexed { c, key ->
                                    val label = glyphOf(key, isShifted)
                                    key(r, c) {
                                        KeyCap(
                                            label = label,
                                            row = r,
                                            col = c,
                                            // O SHIFT acende por estar travado, nao por posicao --
                                            // e `shifted` ja e lido aqui em cima para os rotulos.
                                            forceSelected = key == SHIFT && isShifted,
                                            weight = weightOf(key),
                                            onPress = {
                                                row.intValue = r
                                                col.intValue = c
                                                pressKey(key)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The line of text typed so far, as its OWN composable.
     *
     * It reads [text] here rather than in [Overlay] on purpose. Compose invalidates the nearest
     * enclosing restartable scope of a state read, and in [Overlay] that scope is Surface's content
     * lambda -- the one holding all five rows. Every single keystroke therefore recomposed ~40
     * KeyCaps to redraw one string. Pulling the read down here means a keystroke invalidates this
     * function and nothing else; the grid only recomposes when the highlight or Shift moves.
     */
    @Composable
    private fun TypedPreview() {
        val typed = text.value
        Text(
            text = typed.ifEmpty { placeholder.value },
            color = if (typed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }

    /**
     * The system-IME alternative to the key grid. Rendered INLINE in the library's Box, never in a
     * Dialog: a modal Dialog takes its own focused window and swallows gamepad keys, so it would
     * strand anyone who toggles this on and then reaches for a controller.
     */
    @Composable
    private fun SystemImeField() {
        val focus = remember { FocusRequester() }
        val ime = LocalSoftwareKeyboardController.current
        OutlinedTextField(
            value = text.value,
            onValueChange = {
                // Strip newlines so a multiline IME can't inject them into a search query.
                val next = it.replace("\n", "")
                text.value = next
                onChange(next)
            },
            placeholder = { Text(placeholder.value) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { ime?.hide(); close() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        // Summon the IME with the field, and make sure it goes away with the overlay — otherwise a
        // dismissed keyboard leaves the system IME covering the library.
        LaunchedEffect(Unit) {
            focus.requestFocus()
            ime?.show()
        }
        DisposableEffect(Unit) { onDispose { ime?.hide() } }
    }

    /**
     * One key. Types on the finger going DOWN, not on it coming back up.
     *
     * `clickable` fires on release, so every character waited out however long the finger stayed on
     * the key — 60–120 ms of human timing, plus a frame — before anything at all happened on
     * screen, highlight included. That delay is most of what "the keyboard is slow" means here, and
     * it is why the system IME feels instant by comparison: Android soft keyboards commit on down.
     * There is nothing to disambiguate against — the panel does not scroll and the keys are not
     * draggable — so a down IS the press.
     *
     * [detectTapGestures] consumes the down, keeping it off the panel's no-op click absorber, and
     * the semantics block puts back the button role and click action that `clickable` used to
     * provide for TalkBack.
     */
    @Composable
    private fun RowScope.KeyCap(
        label: String,
        row: Int,
        col: Int,
        forceSelected: Boolean,
        weight: Float,
        onPress: () -> Unit,
    ) {
        // A selecao e DERIVADA aqui dentro, e nao recebida pronta de [Overlay]. As duas coisas que
        // fazem isso funcionar:
        //
        // 1. `derivedStateOf` recalcula para as quarenta teclas (duas comparacoes de inteiro) mas
        //    so notifica quem o observa quando o VALOR muda. Movendo o realce de uma tecla para
        //    outra, dois booleanos mudam -- entao duas teclas recompoem, nao quarenta.
        // 2. KeyCap e uma `@Composable` normal, logo tem escopo reiniciavel proprio, e a
        //    invalidacao para nela. Ler o mesmo estado la fora nao serviria: nem no corpo de
        //    Overlay (escopo das cinco linhas) nem dentro de `key(r, c)`, que e `inline` e portanto
        //    atribui a leitura ao escopo de fora.
        //
        // Medido: mover o realce custava ~32 ms por tecla no A12 (braco A x braco B, TASK-0068).
        val selected by remember(row, col, forceSelected) {
            derivedStateOf {
                forceSelected || (LibraryKeyboard.row.intValue == row && LibraryKeyboard.col.intValue == col)
            }
        }
        // pointerInput(Unit) never restarts, so it would hold the FIRST lambda it was given for the
        // life of the key. Read through rememberUpdatedState instead, or a future layout change
        // (a symbols page, another row set) would have this cap typing the character it used to be.
        val press by rememberUpdatedState(onPress)
        Box(
            modifier = Modifier
                .weight(weight)
                .height(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .pointerInput(Unit) { detectTapGestures(onPress = { press() }) }
                .semantics {
                    role = Role.Button
                    onClick(label) { press(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (label.length > 1) 13.sp else 18.sp,
                maxLines = 1,
            )
        }
    }
}
