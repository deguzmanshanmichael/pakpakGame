package com.shan.pakpak

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shan.pakpak.engine.Input
import com.shan.pakpak.gfx.Gfx
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * Renders the game into a small "virtual pixel" image and scales it up by a whole number, which
 * keeps pixel art crisp on every screen and keeps the fill cost tiny on cheap phones.
 */
private class GameSurface {
    var buffer: ImageBitmap? = null
    var gfx: Gfx? = null
    var scale = 4
    var offX = 0
    var offY = 0

    fun configure(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Aim for a virtual width of ~216 px. Integer scale keeps every virtual pixel the same size.
        val s = max(2, w / 216)
        val vw = w / s
        val vh = h / s
        scale = s
        offX = (w - vw * s) / 2
        offY = (h - vh * s) / 2
        if (buffer?.width != vw || buffer?.height != vh) {
            val img = ImageBitmap(vw, vh)
            buffer = img
            gfx = Gfx(GraphicsCanvas(img), vw, vh)
        }
        G.onResize(vw, vh)
    }

    fun toVirtual(px: Float, py: Float) = Pair((px - offX) / scale, (py - offY) / scale)
}

/** The whole game as one composable: used by MainActivity on Android and MainViewController on iOS. */
@Composable
fun PakPakApp(config: PlatformConfig) {
    val haptics = LocalHapticFeedback.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    remember {
        G.init(config)
        true
    }
    G.hapticImpl = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    G.clipboardImpl = { clipboard.setText(AnnotatedString(it)) }

    val surface = remember { GameSurface() }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = -1L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last < 0) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                val g = surface.gfx
                if (g != null) {
                    try { G.frame(dt, g) } catch (e: Throwable) { G.reportCrash(e) }
                }
                frameTick = now
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onSizeChanged { surface.configure(it.width, it.height) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (ch in event.changes) {
                                val (x, y) = surface.toVirtual(ch.position.x, ch.position.y)
                                val id = ch.id.value.toInt()
                                when {
                                    ch.pressed && !ch.previousPressed -> G.input.push(Input.DOWN, x, y, id)
                                    ch.pressed -> if (id == G.input.primaryPointer) G.input.push(Input.MOVE, x, y, id)
                                    ch.previousPressed -> G.input.push(Input.UP, x, y, id)
                                }
                                ch.consume()
                            }
                        }
                    }
                },
        ) {
            frameTick // read so the canvas redraws every frame
            drawBuffer(surface)
        }
        G.promptState?.let { PromptOverlay(it) }
    }
}

private fun DrawScope.drawBuffer(s: GameSurface) {
    val img = s.buffer ?: return
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(s.offX, s.offY),
        dstSize = IntSize(img.width * s.scale, img.height * s.scale),
        filterQuality = FilterQuality.None,
    )
}

private val INK = Color(0xFF1C1612)
private val PAPER = Color(0xFFFAECC4)
private val ORANGE = Color(0xFFFF9628)

/** Cross-platform text-entry dialog (sign-in, chat, search...) drawn over the game. */
@Composable
private fun PromptOverlay(req: PromptRequest) {
    val values = remember(req) { req.fields.map { mutableStateOf(it.initial) } }
    val focus = remember(req) { FocusRequester() }
    fun finish(result: List<String>?) {
        if (G.promptState !== req) return
        G.promptState = null
        G.post { req.onResult(result) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            .pointerInput(req) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .background(PAPER, RoundedCornerShape(10.dp))
                .border(2.dp, INK, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(req.title, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFFC46010)))
            req.message?.let { BasicText(it, style = TextStyle(fontSize = 14.sp, color = INK)) }
            req.fields.forEachIndexed { i, f ->
                if (f.label.isNotEmpty()) BasicText(f.label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = INK))
                var text by values[i]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .border(1.dp, INK, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    if (text.isEmpty() && f.hint.isNotEmpty()) BasicText(f.hint, style = TextStyle(fontSize = 16.sp, color = Color(0xFF9A8F80)))
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= f.maxLength) text = it },
                        singleLine = !f.multiline,
                        maxLines = if (f.multiline) 5 else 1,
                        visualTransformation = if (f.password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.password) KeyboardType.Password else KeyboardType.Text),
                        textStyle = TextStyle(fontSize = 16.sp, color = INK),
                        cursorBrush = SolidColor(INK),
                        modifier = Modifier.fillMaxWidth().let { m -> if (i == 0) m.focusRequester(focus) else m },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton("Cancel", PAPER, Modifier.weight(1f)) { finish(null) }
                DialogButton(req.okLabel, ORANGE, Modifier.weight(1f)) { finish(values.map { it.value }) }
            }
        }
    }
    LaunchedEffect(req) { if (req.fields.isNotEmpty()) runCatching { focus.requestFocus() } }
}

@Composable
private fun DialogButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(color, RoundedCornerShape(6.dp))
            .border(2.dp, INK, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (color == ORANGE) Color.White else INK))
    }
}
