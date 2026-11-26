package org.vestaopensource.nebulapdf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Componentes con estética de escritorio clásico de principios de los 2000 (barras de título azules,
 * ventanas beige, botones con relieve, barra de tareas). Todo está dibujado desde cero.
 */
object Xp {
    val TitleTop = Color(0xFF0A5FE0)
    val TitleMid = Color(0xFF2A7BF0)
    val TitleBottom = Color(0xFF0447C8)
    val TitleInactive = Color(0xFF7A96DF)
    val Body = Color(0xFFECE9D8)
    val Frame = Color(0xFF0831D9)
    val ButtonBorder = Color(0xFF003C74)
    val Text = Color(0xFF000000)
    val Link = Color(0xFF0033CC)
    val Field = Color(0xFFFFFFFF)
    val FieldBorder = Color(0xFF7F9DB9)
    val Green = Color(0xFF3C9E35)
    val GreenLight = Color(0xFF5FC253)
    val Taskbar = Brush.verticalGradient(listOf(Color(0xFF3168D5), Color(0xFF4993E6), Color(0xFF2157D7), Color(0xFF1941A5)))
    val Desktop = Brush.verticalGradient(listOf(Color(0xFF2B6CD4), Color(0xFF5A9BE8), Color(0xFF7DB8EE), Color(0xFF3E8E3A), Color(0xFF2F7A2B)),)

    val body = TextStyle(color = Text, fontSize = 14.sp)
    val small = TextStyle(color = Text, fontSize = 12.sp)
}

/** Ventana con barra de título, botones de minimizar/maximizar/cerrar y cuerpo beige. */
@Composable
fun XpWindow(
    title: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    Column(
        modifier
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Xp.Frame)
            .padding(start = 3.dp, end = 3.dp, bottom = 3.dp)
    ) {
        // Barra de título
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Brush.verticalGradient(listOf(Xp.TitleTop, Xp.TitleMid, Xp.TitleBottom, Xp.TitleBottom)))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Box(Modifier.size(18.dp)) { icon() }; Spacer(Modifier.width(6.dp)) }
            Text(
                title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = TextStyle(
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    shadow = Shadow(Color(0xFF0A1E6E), Offset(1f, 1f), 1f)
                )
            )
            CaptionButton(CaptionKind.Minimize, null)
            Spacer(Modifier.width(2.dp))
            CaptionButton(CaptionKind.Maximize, null)
            Spacer(Modifier.width(2.dp))
            CaptionButton(CaptionKind.Close, onClose)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Xp.Body)
                .padding(10.dp),
            content = content,
        )
    }
}

enum class CaptionKind { Minimize, Maximize, Close }

@Composable
private fun CaptionButton(kind: CaptionKind, onClick: (() -> Unit)?) {
    val bg = if (kind == CaptionKind.Close)
        Brush.verticalGradient(listOf(Color(0xFFE8826B), Color(0xFFD5482A), Color(0xFFC13A1C)))
    else Brush.verticalGradient(listOf(Color(0xFF6A9CF5), Color(0xFF2E6BE6), Color(0xFF1D56D2)))
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .border(1.dp, Color.White, RoundedCornerShape(3.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val w = size.width
            val stroke = 2.2.dp.toPx()
            when (kind) {
                CaptionKind.Minimize -> drawLine(Color.White, Offset(1f, w - 1), Offset(w * 0.6f, w - 1), stroke)
                CaptionKind.Maximize -> {
                    drawRect(Color.White, Offset.Zero, Size(w, w), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 0.7f))
                    drawRect(Color.White, Offset.Zero, Size(w, stroke * 1.2f))
                }
                CaptionKind.Close -> {
                    drawLine(Color.White, Offset(0f, 0f), Offset(w, w), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(w, 0f), Offset(0f, w), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

/** Botón con relieve y borde azul oscuro. */
@Composable
fun XpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    default: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(3.dp)
    val bg = when {
        !enabled -> Brush.verticalGradient(listOf(Color(0xFFF5F4EA), Color(0xFFF5F4EA)))
        pressed -> Brush.verticalGradient(listOf(Color(0xFFE3E1D5), Color(0xFFF0EFE8)))
        else -> Brush.verticalGradient(listOf(Color.White, Color(0xFFF4F3EE), Color(0xFFE6E3D6)))
    }
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (enabled) Xp.ButtonBorder else Color(0xFFC9C7BA), shape)
            .then(if (default && enabled) Modifier.border(2.dp, Color(0xFF9DB9EB), RoundedCornerShape(3.dp)).padding(1.dp) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Xp.body.copy(color = if (enabled) Xp.Text else Color(0xFFA19F92)))
    }
}

/** Marco con título incrustado en el borde (como un "group box"). */
@Composable
fun XpGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.padding(top = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFD0D0BF), RoundedCornerShape(4.dp))
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        Text(
            title,
            style = Xp.small.copy(color = Color(0xFF0046D5)),
            modifier = Modifier
                .offset(x = 8.dp, y = (-8).dp)
                .background(Xp.Body)
                .padding(horizontal = 4.dp)
        )
    }
}

/** Casilla de verificación. */
@Composable
fun XpCheckbox(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(Brush.linearGradient(listOf(Color(0xFFDCDCD7), Color.White)))
                .border(1.dp, Color(0xFF1C5180)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Canvas(Modifier.size(10.dp)) {
                val w = size.width
                val s = 2.2.dp.toPx()
                drawLine(Color(0xFF21A121), Offset(0f, w * 0.5f), Offset(w * 0.38f, w * 0.88f), s)
                drawLine(Color(0xFF21A121), Offset(w * 0.38f, w * 0.88f), Offset(w, w * 0.1f), s)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = Xp.body)
    }
}

/** Barra de progreso de bloques verdes. Con progress = null se anima como "marquesina". */
@Composable
fun XpProgress(progress: Float?, modifier: Modifier = Modifier, tick: Long = 0L) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFF686868), RoundedCornerShape(3.dp))
            .padding(2.dp)
    ) {
        val block = 8.dp.toPx()
        val gap = 2.dp.toPx()
        val count = ((size.width + gap) / (block + gap)).toInt()
        val green = Brush.verticalGradient(listOf(Color(0xFFB4F2A9), Color(0xFF3FD23F), Color(0xFF2BB52B), Color(0xFF8CE689)))
        val (from, to) = if (progress != null) 0 to (count * progress.coerceIn(0f, 1f)).toInt()
        else ((tick % (count + 3)).toInt() - 3).let { it to it + 3 }
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(count)) {
            drawRoundRect(green, Offset(i * (block + gap), 0f), Size(block, size.height), CornerRadius(1f))
        }
    }
}

/** Campo de texto de solo lectura con borde azul grisáceo. */
@Composable
fun XpField(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Xp.Field)
            .border(1.dp, Xp.FieldBorder)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) { Text(text, style = Xp.body, maxLines = 2) }
}

/** Barra de tareas con botón de inicio verde y reloj. */
@Composable
fun XpTaskbar(startLabel: String, onStart: () -> Unit, clock: String, startIcon: @Composable () -> Unit, items: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Xp.Taskbar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF6DC35F), Color(0xFF3C9E35), Color(0xFF2F8A2A), Color(0xFF3FA03A))))
                .clickable(onClick = onStart)
                .padding(start = 10.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp)) { startIcon() }
            Spacer(Modifier.width(6.dp))
            Text(
                startLabel,
                style = TextStyle(
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    shadow = Shadow(Color(0xFF1A4A12), Offset(1.5f, 1.5f), 2f)
                )
            )
        }
        Row(Modifier.weight(1f).padding(horizontal = 6.dp), content = items)
        Box(
            Modifier
                .height(44.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF0C9AE8), Color(0xFF13A3F0), Color(0xFF0B7BD8))))
                .border(BorderStroke(1.dp, Color(0xFF0A5CB5)))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(clock, style = TextStyle(color = Color.White, fontSize = 13.sp))
        }
    }
}

/** Botón de la barra de tareas (ventana abierta). */
@Composable
fun RowScope.XpTaskButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 2.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) Color(0xFF1E52B7) else Color(0xFF3C81F3))
            .border(1.dp, Color(0xFF1A4BAE), RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, maxLines = 1, style = TextStyle(color = Color.White, fontSize = 12.sp))
    }
}

/** Menú de inicio desplegable. */
@Composable
fun XpStartMenu(
    header: String,
    headerIcon: @Composable () -> Unit,
    items: List<Pair<String, () -> Unit>>,
    footer: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    Column(modifier.width(260.dp).shadow(12.dp, shape).clip(shape).background(Color(0xFF1C5BD6))) {
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF1F67E3), Color(0xFF3E8AF0), Color(0xFF1A57C9))))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, Color.White, RoundedCornerShape(4.dp))) { headerIcon() }
            Spacer(Modifier.width(10.dp))
            Text(header, style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                shadow = Shadow(Color(0xFF0A1E6E), Offset(1f, 1f), 1f)))
        }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 6.dp)) {
            items.forEach { (label, action) ->
                Text(
                    label, style = Xp.body,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF4282F0), Color(0xFF1C5BD6)))).padding(10.dp)) {
            Text(footer, style = TextStyle(color = Color.White, fontSize = 11.sp))
        }
    }
}

@Composable
fun Desktop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Xp.Desktop), content = content)
}

