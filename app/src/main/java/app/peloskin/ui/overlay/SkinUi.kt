package app.peloskin.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import app.peloskin.data.bike.FeedStatus
import app.peloskin.data.store.RiderPhotos
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.PowerZones
import app.peloskin.domain.Profile
import app.peloskin.domain.RideSnapshot
import app.peloskin.domain.Settings
import app.peloskin.domain.Units
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Figtree = FontFamily(
    Font(R.font.figtree_light, FontWeight.Light),
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

private val Ink = Color.White
private val Dim = Color(0xFFC9CCD2)
private val Divider = Color(0x38FFFFFF)
private val StripBg = Color(0xFF181A1F)
private val Up = Color(0xFF3FD28B)
private val Down = Color(0xFFFF4A55)
private val ZoneOn = Color(0xFF41C7C7)
private val ZoneOff = Color(0xFF3A3E46)
private val Gold = Color(0xFFE6B422)

/**
 * Everything is sized in "u", 1% of the screen width, so the skin keeps the
 * mockup's proportions whatever the tablet's density.
 */
class Scale(private val u: Dp, private val fontScale: Float) {
    fun dp(x: Float): Dp = u * x
    fun sp(x: Float): TextUnit = TextUnit((u.value * x) / fontScale, androidx.compose.ui.unit.TextUnitType.Sp)
}

@Composable
fun rememberScale(): Scale {
    val width = LocalConfiguration.current.screenWidthDp
    val d = LocalDensity.current
    return remember(width, d) { Scale((width / 100f).dp, d.fontScale) }
}

data class SkinState(
    val ride: RideSnapshot,
    val feed: FeedStatus,
    val ftp: Int?,
    /** A short message: a new FTP, a new PR, or who's riding now. */
    val banner: String?,
    val units: Units = Units.Imperial,
    /** Who's riding, when there's more than one rider. */
    val rider: RiderBadge? = null,
)

/**
 * The rider's bubble, so it's clear at a glance who's riding: their picture, or their
 * initials on their own color if they haven't added one.
 */
data class RiderBadge(val initials: String, val colorIndex: Int, val photo: ImageBitmap? = null)

/** One color per rider, in the order they were added. */
val RiderColors = listOf(
    Color(0xFF41C7C7), Color(0xFFFF7A6B), Color(0xFFB48CFF), Color(0xFFE6B422), Color(0xFF3FD28B), Color(0xFF5B8DB8),
)

fun riderColor(index: Int) = RiderColors[index % RiderColors.size]

/** Riders' pictures, ready to draw, by rider id. */
@Composable
fun rememberRiderPhotos(): Map<String, ImageBitmap> {
    val photos by RiderPhotos.photos.collectAsState()
    return remember(photos) { photos.mapValues { it.value.asImageBitmap() } }
}

/** The bubble for [p], or null if they've been removed. */
fun Settings.badgeFor(p: Profile?, photos: Map<String, ImageBitmap>): RiderBadge? {
    val index = profiles.indexOfFirst { it.id == p?.id }
    if (p == null || index < 0) return null
    return RiderBadge(p.initials, index, photos[p.id])
}

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

/** Tap anywhere on it to switch to the single-line bar. */
@Composable
fun FullSkin(state: SkinState, onToggle: () -> Unit, onNextRider: () -> Unit = {}, onEndRide: () -> Unit = {}) {
    val s = rememberScale()
    val live = state.live()
    val r = state.ride
    val hold = rememberHoldState()
    // AVG and BEST stay up after a ride ends, until the next one starts.
    val stats = live && (r.riding || r.elapsedSec > 0)
    Column(
        Modifier.fillMaxWidth().tapOrHold(hold, enabled = live && r.riding, onTap = onToggle, onHoldDone = onEndRide),
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
                StatusLine(state, hold, s)
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
        Box(Modifier.fillMaxWidth().height(s.dp(4.2f)).background(StripBg), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                StripItem("Speed", "(${state.units.speedLabel})", state.units.speed(r.speedMph).oneDp(live), s)
                StripDivider(s)
                StripItem("Distance", "(${state.units.distanceLabel})", state.units.distance(r.distanceMi).twoDp(live), s)
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
        // Tapping the chip switches rider; it takes the tap so the layout doesn't switch too.
        state.rider?.let { RiderBubble(it, s, diameter = 2.5f, onClick = onNextRider, modifier = Modifier.padding(start = s.dp(1.4f))) }
        }
    }
}

/** Tapping the bubble switches rider. It takes the tap, so the layout doesn't switch too. */
@Composable
private fun RiderBubble(badge: RiderBadge, s: Scale, diameter: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            // A little extra room around the bubble so it's easy to hit with a finger mid-ride.
            .padding(s.dp(0.5f))
            .size(s.dp(diameter)),
    ) {
        RiderAvatar(badge, Modifier.fillMaxSize(), fontSize = s.sp(diameter * 0.42f))
    }
}

/** A rider's picture in a circle, or their initials on their color. Shared with the settings screen. */
@Composable
fun RiderAvatar(badge: RiderBadge, modifier: Modifier, fontSize: TextUnit) {
    val photo = badge.photo
    if (photo != null) {
        Image(
            photo, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape).border(1.5.dp, riderColor(badge.colorIndex), CircleShape),
        )
    } else {
        Box(modifier.background(riderColor(badge.colorIndex), CircleShape), contentAlignment = Alignment.Center) {
            Text(
                badge.initials,
                color = Color(0xFF14161A),
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = fontSize),
            )
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
private fun StatusLine(state: SkinState, hold: HoldState, s: Scale) {
    hold.progress?.let {
        HoldPrompt(it, s, Modifier.padding(bottom = s.dp(0.8f)))
        return
    }
    val text = when {
        state.banner != null -> state.banner
        state.feed == FeedStatus.NotFound -> "Can't read the bike · is the sensor cable connected?"
        state.feed == FeedStatus.Unsupported -> "This bike isn't supported yet"
        state.feed == FeedStatus.Connecting -> "Connecting to the bike…"
        else -> null
    } ?: return
    Text(
        text,
        color = if (state.banner != null) Gold else Dim,
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
            trend?.let { TrendArrow(up = it, s = s, modifier = Modifier.padding(end = s.dp(0.3f))) }
            Text(value, color = Ink, style = TextStyle(fontFamily = Figtree, fontSize = s.sp(1.45f)))
        }
    }
}

/** Drawn rather than typed: the Peloton tablet's fonts have no ▲ ▼ glyphs. */
@Composable
private fun TrendArrow(up: Boolean, s: Scale, modifier: Modifier = Modifier) {
    Canvas(modifier.size(s.dp(0.7f), s.dp(0.6f))) {
        val path = Path().apply {
            if (up) { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            else { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
            close()
        }
        drawPath(path, if (up) Up else Down)
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
fun CompactSkin(
    state: SkinState,
    metrics: List<CompactMetric>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onNextRider: () -> Unit = {},
    onEndRide: () -> Unit = {},
) {
    val s = rememberScale()
    val live = state.live()
    val r = state.ride
    val hold = rememberHoldState()
    Row(
        modifier
            .tapOrHold(hold, enabled = live && r.riding, onTap = onToggle, onHoldDone = onEndRide)
            .background(Color(0xEB181A1F), RoundedCornerShape(s.dp(2.2f)))
            .padding(horizontal = s.dp(1.2f), vertical = s.dp(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.rider?.let { RiderBubble(it, s, diameter = 1.7f, onClick = onNextRider) }
        val holding = hold.progress
        if (holding != null) {
            HoldPrompt(holding, s, Modifier.padding(horizontal = s.dp(1.1f)))
        } else if (!live) {
            Text(
                when (state.feed) {
                    FeedStatus.NotFound -> "Can't read the bike"
                    FeedStatus.Unsupported -> "Bike not supported yet"
                    else -> "Connecting to the bike…"
                },
                color = Dim, modifier = Modifier.padding(horizontal = s.dp(1.1f)),
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = s.sp(0.9f)),
            )
        } else {
            state.banner?.let { banner ->
                Text(
                    banner.uppercase(), color = Gold, modifier = Modifier.padding(horizontal = s.dp(1.1f)),
                    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = s.sp(0.8f), letterSpacing = 0.1.em),
                )
                Box(Modifier.width(1.dp).height(s.dp(1.6f)).background(Color(0x2EFFFFFF)))
            }
            metrics.forEachIndexed { i, m ->
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
                    CompactMetric.Speed -> CompactItem("Speed", state.units.speed(r.speedMph).oneDp(true), state.units.speedLabel, s)
                    CompactMetric.Distance -> CompactItem("Distance", state.units.distance(r.distanceMi).twoDp(true), state.units.distanceLabel, s)
                    CompactMetric.TotalOutput -> CompactItem("Total", r.totalKj.whole(true), "kJ", s)
                    CompactMetric.Calories -> CompactItem("Calories", r.calories.whole(true), "kcal", s)
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

// ------------------------------------------------------- hold to end ride

/** How long to hold the skin to end a ride, and how long before the prompt appears. */
private const val HoldMs = 1_500L
private const val HoldPromptMs = 300L

class HoldState {
    /** Progress through the hold, 0 to 1, or null when you aren't holding. */
    var progress by mutableStateOf<Float?>(null)
}

@Composable
fun rememberHoldState() = remember { HoldState() }

/**
 * A tap calls [onTap]. Holding for [HoldMs] calls [onHoldDone], with [hold] showing progress
 * once the press outlasts a tap. Letting go early cancels. Holding does nothing unless [enabled].
 */
fun Modifier.tapOrHold(hold: HoldState, enabled: Boolean, onTap: () -> Unit, onHoldDone: () -> Unit): Modifier =
    pointerInput(enabled) {
        coroutineScope {
            detectTapGestures(
                onPress = {
                    val timer = if (!enabled) null else launch {
                        delay(HoldPromptMs)
                        val start = System.nanoTime()
                        while (true) {
                            val held = (System.nanoTime() - start) / 1_000_000f + HoldPromptMs
                            if (held >= HoldMs) {
                                hold.progress = null
                                onHoldDone()
                                break
                            }
                            hold.progress = (held - HoldPromptMs) / (HoldMs - HoldPromptMs)
                            delay(16)
                        }
                    }
                    tryAwaitRelease()
                    timer?.cancel()
                    hold.progress = null
                },
                // A long press never counts as a tap, so letting go early doesn't switch layouts.
                onLongPress = {},
                onTap = { onTap() },
            )
        }
    }

@Composable
private fun HoldPrompt(progress: Float, s: Scale, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "HOLD TO END RIDE", color = Gold,
            style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = s.sp(0.8f), letterSpacing = 0.12.em),
        )
        Spacer(Modifier.width(s.dp(1f)))
        Box(Modifier.width(s.dp(12f)).height(s.dp(0.4f)).background(ZoneOff, RoundedCornerShape(s.dp(0.2f)))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(s.dp(0.4f)).background(Gold, RoundedCornerShape(s.dp(0.2f))))
        }
    }
}

