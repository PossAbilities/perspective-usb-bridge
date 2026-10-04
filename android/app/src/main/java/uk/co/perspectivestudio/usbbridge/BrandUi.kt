package uk.co.perspectivestudio.usbbridge

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Perspective Studio look, matching the desktop app (windows/src/styles.css):
 * the same palette tokens, the same three typefaces, an ink top bar with a
 * status pill, and the lime "live" dot from the mark as the one signature move.
 */

object Brand {
    val Ink = Color(0xFF161042)
    val Midnight = Color(0xFF1A1546)
    val Panel = Color(0xFF241D57)
    val Raised = Color(0xFF2C2466)
    val Text = Color(0xFFF3F1FB)
    val Dim = Color(0xFFB3ABD6)
    val Lime = Color(0xFFCFE96A)
    val Orange = Color(0xFFF4592B)
    val Lilac = Color(0xFFCDB7F2)
    val Danger = Color(0xFFFF8A7A)
    val Line = Color(0x24CDB7F2)
    val LineStrong = Color(0x47CDB7F2)

    val Display = FontFamily(
        Font(R.font.montserrat_bold, FontWeight.Bold),
        Font(R.font.montserrat_extrabold, FontWeight.ExtraBold)
    )
    val Body = FontFamily(
        Font(R.font.dm_sans_regular, FontWeight.Normal),
        Font(R.font.dm_sans_medium, FontWeight.Medium),
        Font(R.font.dm_sans_bold, FontWeight.Bold)
    )
    val Mono = FontFamily(Font(R.font.ibm_plex_mono_medium, FontWeight.Medium))

    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-0.4).sp, color = Text)
    val heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Text)
    val label = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Text)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = Text)
    val hint = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = Dim)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Text)
}

enum class Status(val color: Color) { Idle(Brand.Dim), Busy(Brand.Lilac), Ok(Brand.Lime), Live(Brand.Lime), Error(Brand.Danger) }

/** The lime dot; pulses outwards while live, like the desktop status pill. */
@Composable
fun StatusDot(status: Status, size: Int = 8) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val progress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ring"
    )
    val blink by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )
    Box(
        Modifier
            .size(size.dp)
            .drawBehind {
                if (status == Status.Live) {
                    drawCircle(
                        color = status.color.copy(alpha = 0.55f * (1f - progress)),
                        radius = this.size.minDimension / 2 + 9.dp.toPx() * progress
                    )
                }
                drawCircle(color = status.color.copy(alpha = if (status == Status.Busy) blink else 1f))
            }
    )
}

@Composable
fun StatusPill(text: String, status: Status, modifier: Modifier = Modifier) {
    val border = when (status) {
        Status.Live -> Brand.Lime.copy(alpha = 0.45f)
        Status.Error -> Brand.Danger.copy(alpha = 0.45f)
        else -> Brand.LineStrong
    }
    Row(
        modifier
            .border(1.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(status)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = Brand.hint.copy(
                fontWeight = FontWeight.Medium,
                color = when (status) {
                    Status.Error -> Brand.Danger
                    Status.Live, Status.Ok -> Brand.Text
                    else -> Brand.Dim
                }
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Ink bar under the status bar: mark, product name, status pill. */
@Composable
fun TopBar(title: String, pillText: String, pillStatus: Status) {
    Column(Modifier.fillMaxWidth().background(Brand.Ink)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_ps_mark), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Brand.title)
                Text("Perspective Studio", style = Brand.hint)
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(pillText, pillStatus, Modifier.widthIn(max = 260.dp))
        }
        HorizontalDivider(color = Brand.Line)
    }
}

/** A titled block of the page. Sections are separated by space, not boxes. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = Brand.heading)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** The one raised surface on the page, for the thing it is about. */
@Composable
fun HeroPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Panel, RoundedCornerShape(22.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(22.dp))
            .padding(22.dp),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        // Navy on orange: white on this orange fails contrast at button size.
        colors = ButtonDefaults.buttonColors(containerColor = Brand.Orange, contentColor = Brand.Ink),
        contentPadding = PaddingValues(horizontal = 22.dp)
    ) { Text(text, style = Brand.label.copy(fontSize = 14.sp, color = Brand.Ink)) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, Brand.LineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Text),
        contentPadding = PaddingValues(horizontal = 22.dp)
    ) { Text(text, style = Brand.label.copy(fontSize = 14.sp)) }
}

/** A label above a value, e.g. "Same Wi-Fi" over an address. */
@Composable
fun LabelledValue(label: String, value: String, valueStyle: TextStyle = Brand.mono) {
    Column {
        Text(label, style = Brand.hint)
        Spacer(Modifier.height(2.dp))
        Text(value, style = valueStyle)
    }
}

/** Collapsible block, like the desktop's <details>. */
@Composable
fun Disclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Brand.Line)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .rotate(if (open) 45f else -45f)
                    .drawBehind {
                        val stroke = 1.8.dp.toPx()
                        drawLine(Brand.Dim, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), stroke)
                        drawLine(Brand.Dim, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), stroke)
                    }
            )
            Spacer(Modifier.width(12.dp))
            Text(title, style = Brand.label.copy(color = Brand.Dim))
        }
        if (open) Column(Modifier.padding(bottom = 12.dp), content = content)
    }
}
