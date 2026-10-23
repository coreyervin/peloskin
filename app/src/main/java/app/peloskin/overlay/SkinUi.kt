package app.peloskin.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.peloskin.R
import app.peloskin.feed.FeedStatus
import app.peloskin.ride.PowerZones
import app.peloskin.ride.RideSnapshot
import app.peloskin.store.CompactMetric
import kotlin.math.roundToInt

val Figtree = FontFamily(
    Font(R.font.figtree_light, FontWeight.Light),
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

private val Ink = Color.White
private val Dim = Color(0xFFC9CCD2)
private val Divider = Color(0x38FFFFFF)
private val StripBg = Color(0xF0181A1F)
private val Up = Color(0xFF3FD28B)
private val Down = Color(0xFFFF4A55)
private val ZoneOn = Color(0xFF41C7C7)
private val ZoneOff = Color(0xFF3A3E46)

/**
 * Everything is sized in "u", 1% of the screen width, so the skin keeps the
 * mockup's proportions whatever the tablet's density.
 */
class Scale(private val u: Dp, private val density: Float, private val fontScale: Float) {
    fun dp(x: Float): Dp = u * x
    fun sp(x: Float): TextUnit = TextUnit((u.value * x) / fontScale, androidx.compose.ui.unit.TextUnitType.Sp)
}

@Composable
fun rememberScale(): Scale {
    val width = LocalConfiguration.current.screenWidthDp
    val d = LocalDensity.current
    return remember(width, d) { Scale((width / 100f).dp, d.density, d.fontScale) }
}

data class SkinState(
    val ride: RideSnapshot,
    val feed: FeedStatus,
    val ftp: Int?,
    val heartRate: Int?,
    val ftpBanner: Int?,
)

private fun SkinState.live() = feed == FeedStatus.Connected

private fun Float.whole(live: Boolean) = if (live) roundToInt().toString() else "--"
private fun Float.oneDp(live: Boolean) = if (live) "%.1f".format(this) else "--"
private fun Float.twoDp(live: Boolean) = if (live) "%.2f".format(this) else "--"

@Composable
private fun Label(text: String, s: Scale, size: Float = 0.78f, color: Color = Ink) = Text(
    text.uppercase(),
    color = color,
    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = s.sp(size), letterSpacing = 0.14.em),
)

// ---------------------------------------------------------------- full skin

/** Tap anywhere on it to switch to the single-line bar, like Grupetto's overlay. */
@Composable
fun FullSkin(state: SkinState, onToggle: () -> Unit) {
    val s = rememberScale()
    val live = state.live()
    val r = state.ride
    // AVG and BEST stay up after a ride ends, until the next one starts.
    val stats = live && (r.riding || r.elapsedSec > 0)
    Column(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000), Color(0xB3000000))))
                .padding(top = s.dp(3f), bottom = s.dp(1.4f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusLine(state, s)
                if (state.ftp != null) ZoneBar(r.power, state.ftp, live, s, Modifier.padding(bottom = s.dp(0.6f)))
                Row(verticalAlignment = Alignment.Bottom) {
                    MetricColumn("Cadence", r.cadence.whole(live), "rpm", r.avgCadence, r.bestCadence, r.cadence, stats, live && r.riding, s)
                    VDivider(s)
                    MetricColumn("Output", r.power.whole(live), "watts", r.avgPower, r.bestPower, r.power, stats, live && r.riding, s)
                    VDivider(s)
                    MetricColumn("Resistance", r.resistance.whole(live), "percent", r.avgResistance, r.bestResistance, r.resistance, stats, live && r.riding, s)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(s.dp(4.2f)).background(StripBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                state.heartRate?.let { StripItem("Heart rate", "(bpm)", it.toString(), s); StripDivider(s) }
                StripItem("Speed", "(mph)", r.speedMph.oneDp(live), s)
                StripDivider(s)
                StripItem("Distance", "(mi)", r.distanceMi.twoDp(live), s)
            }
            Box(Modifier.width(s.dp(FoldSlot)), contentAlignment = Alignment.Center) {
                FoldButtonView(expanded = true)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start) {
                StripItem("Total output", "(kj)", r.totalKj.whole(live), s)
                StripDivider(s)
                StripItem("Calories", "(kcal)", r.calories.whole(live), s)
            }
        }
    }
}

/** Width of the gap in the strip where the fold button sits, in u. */
private const val FoldSlot = 3.2f
/** Fold button diameter, in u. */
private const val FoldButton = 1.9f

/** Shows which way a tap will switch the layout. */
@Composable
private fun FoldButtonView(expanded: Boolean) {
    val s = rememberScale()
    Box(
        Modifier.size(s.dp(FoldButton)).border(1.dp, Color(0x73FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Drawn rather than typed: Figtree has no chevron glyph.
        Canvas(Modifier.size(s.dp(FoldButton * 0.42f), s.dp(FoldButton * 0.24f))) {
            val path = Path().apply {
                if (expanded) { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
                else { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            }
            drawPath(path, Dim, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun StatusLine(state: SkinState, s: Scale) {
    val text = when {
        state.ftpBanner != null -> "New FTP ${state.ftpBanner} · from your best 20 minutes"
        state.feed == FeedStatus.NotFound -> "Can't reach Grupetto · is it running?"
        state.feed == FeedStatus.Connecting -> "Connecting to Grupetto…"
        else -> null
    } ?: return
    Text(
        text,
        color = if (state.ftpBanner != null) Color(0xFFE6B422) else Dim,
        modifier = Modifier.padding(bottom = s.dp(0.8f)),
        style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = s.sp(0.9f)),
    )
}

@Composable
private fun MetricColumn(
    name: String, value: String, unit: String,
    avg: Float, best: Float, current: Float, showStats: Boolean, showTrend: Boolean, s: Scale,
) {
    Row(
        Modifier.padding(horizontal = s.dp(1.8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideStat("Avg", if (showStats) avg.roundToInt().toString() else "--",
            trend = if (showTrend && avg > 0f) current >= avg else null, alignEnd = true, s = s)
        Column(
            Modifier.width(s.dp(10.5f)).padding(horizontal = s.dp(0.8f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label(name, s)
            Spacer(Modifier.height(s.dp(0.5f)))
            Text(value, color = Ink, style = TextStyle(
                fontFamily = Figtree, fontWeight = FontWeight.Light, fontSize = s.sp(4.3f),
                textAlign = TextAlign.Center, letterSpacing = (-0.01).em,
            ))
            Text(unit, color = Dim, style = TextStyle(fontFamily = Figtree, fontSize = s.sp(0.85f)))
        }
        SideStat("Best", if (showStats) best.roundToInt().toString() else "--", trend = null, alignEnd = false, s = s)
    }
}

@Composable
private fun SideStat(label: String, value: String, trend: Boolean?, alignEnd: Boolean, s: Scale) {
    Column(
        Modifier.width(s.dp(4.5f)),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Label(label, s, size = 0.72f, color = Dim)
        Spacer(Modifier.height(s.dp(0.35f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            trend?.let {
                Text(if (it) "▲" else "▼", color = if (it) Up else Down,
                    style = TextStyle(fontSize = s.sp(0.8f)), modifier = Modifier.padding(end = s.dp(0.3f)))
            }
            Text(value, color = Ink, style = TextStyle(fontFamily = Figtree, fontSize = s.sp(1.45f)))
        }
    }
}

@Composable
private fun VDivider(s: Scale) = Box(Modifier.width(1.dp).height(s.dp(6.5f)).background(Divider))

@Composable
private fun ZoneBar(power: Float, ftp: Int, live: Boolean, s: Scale, modifier: Modifier = Modifier) {
    val zone = if (live) PowerZones.zone(power, ftp) else 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Label("Zone", s, size = 0.7f, color = Dim)
        Text(" ${if (live) zone else "-"}", color = Ink, style = TextStyle(fontFamily = Figtree, fontSize = s.sp(1.05f)))
        Spacer(Modifier.width(s.dp(0.8f)))
        ZoneBlocks(zone, s, blockWidth = 1.6f, blockHeight = 0.45f)
        Spacer(Modifier.width(s.dp(0.8f)))
        Label("FTP", s, size = 0.7f, color = Dim)
        Text(" ${if (live) "${PowerZones.percentOfFtp(power, ftp)}%" else "--"}", color = Ink,
            style = TextStyle(fontFamily = Figtree, fontSize = s.sp(1.05f)))
    }
}

@Composable
private fun ZoneBlocks(zone: Int, s: Scale, blockWidth: Float, blockHeight: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(s.dp(0.2f))) {
        repeat(PowerZones.Count) { i ->
            Box(
                Modifier.width(s.dp(blockWidth)).height(s.dp(blockHeight))
                    .background(if (i < zone) ZoneOn else ZoneOff, RoundedCornerShape(s.dp(0.2f)))
            )
        }
    }
}

@Composable
private fun StripItem(name: String, unit: String, value: String, s: Scale) {
    Column(
        Modifier.padding(horizontal = s.dp(3.2f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${name.uppercase()} $unit", color = Dim, style = TextStyle(
            fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = s.sp(0.72f), letterSpacing = 0.08.em))
        Spacer(Modifier.height(s.dp(0.35f)))
        Text(value, color = Ink, style = TextStyle(fontFamily = Figtree, fontSize = s.sp(1.3f)))
    }
}

@Composable
private fun StripDivider(s: Scale) = Box(Modifier.width(1.dp).height(s.dp(2.4f)).background(Color(0x29FFFFFF)))

// ------------------------------------------------------------- compact skin

/** One-line bar: tap anywhere on it to go back to the full layout, drag it to move it. */
@Composable
fun CompactSkin(state: SkinState, metrics: List<CompactMetric>, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val s = rememberScale()
    val live = state.live()
    val r = state.ride
    Row(
        modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .background(Color(0xEB181A1F), RoundedCornerShape(s.dp(2.2f)))
            .padding(horizontal = s.dp(1.2f), vertical = s.dp(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!live) {
            Text(
                if (state.feed == FeedStatus.NotFound) "Can't reach Grupetto" else "Connecting to Grupetto…",
                color = Dim, modifier = Modifier.padding(horizontal = s.dp(1.1f)),
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = s.sp(0.9f)),
            )
        } else {
            val shown = metrics.filter { it != CompactMetric.HeartRate || state.heartRate != null }
            shown.forEachIndexed { i, m ->
                if (i > 0) Box(Modifier.width(1.dp).height(s.dp(1.6f)).background(Color(0x2EFFFFFF)))
                when (m) {
                    CompactMetric.Cadence -> CompactItem("Cadence", r.cadence.whole(true), "rpm", s)
                    CompactMetric.Output -> CompactItem("Output", r.power.whole(true), "W", s) {
                        state.ftp?.let { ftp ->
                            Spacer(Modifier.width(s.dp(0.5f)))
                            ZoneBlocks(PowerZones.zone(r.power, ftp), s, blockWidth = 0.9f, blockHeight = 0.35f)
                        }
                    }
                    CompactMetric.Resistance -> CompactItem("Resistance", r.resistance.whole(true), "%", s)
                    CompactMetric.Speed -> CompactItem("Speed", r.speedMph.oneDp(true), "mph", s)
                    CompactMetric.Distance -> CompactItem("Distance", r.distanceMi.twoDp(true), "mi", s)
                    CompactMetric.TotalOutput -> CompactItem("Total", r.totalKj.whole(true), "kJ", s)
                    CompactMetric.Calories -> CompactItem("Calories", r.calories.whole(true), "kcal", s)
                    CompactMetric.HeartRate -> CompactItem("Heart rate", state.heartRate.toString(), "bpm", s)
                }
            }
        }
        Spacer(Modifier.width(s.dp(0.5f)))
        FoldButtonView(expanded = false)
    }
}

@Composable
private fun CompactItem(name: String, value: String, unit: String, s: Scale, extra: @Composable () -> Unit = {}) {
    Row(Modifier.padding(horizontal = s.dp(1.1f)), verticalAlignment = Alignment.CenterVertically) {
        Label(name, s, size = 0.62f, color = Dim)
        Spacer(Modifier.width(s.dp(0.45f)))
        Text(value, color = Ink, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Light, fontSize = s.sp(1.7f)))
        Spacer(Modifier.width(s.dp(0.3f)))
        Text(unit, color = Dim, style = TextStyle(fontFamily = Figtree, fontSize = s.sp(0.7f)))
        extra()
    }
}
