package bg.drone.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.drone.rid.TargetKind

object Palette {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF121821)
    val Surface2 = Color(0xFF1A2330)
    val Line = Color(0xFF243041)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B98A5)
    val Accent = Color(0xFF22D3EE)
    val Ok = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Bad = Color(0xFFEF4444)
}

val TargetKind.uiColor: Color get() = Color(color)

@Composable
fun DroneRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color.Black,
            secondary = Palette.Accent,
            background = Palette.Bg,
            surface = Palette.Surface,
            surfaceVariant = Palette.Surface2,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.Surface2,
            surfaceContainerLow = Palette.Surface,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.Muted,
            outline = Palette.Line,
            secondaryContainer = Color(0xFF0E3A44),
            onSecondaryContainer = Palette.Accent,
        ),
        content = content,
    )
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun KindBadge(kind: TargetKind) {
    Text(
        kind.short.uppercase(),
        color = kind.uiColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(kind.uiColor.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.Text, maxLines = 1)
        Text(label, color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(key, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(0.45f))
        Text(value, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(0.55f))
    }
}
