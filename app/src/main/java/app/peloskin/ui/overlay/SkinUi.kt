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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.peloskin.R
import app.peloskin.data.bike.FeedStatus
import app.peloskin.data.store.RiderPhotos
import app.peloskin.data.store.SettingsStore
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.HeartRateZones
import app.peloskin.domain.PowerZones
import app.peloskin.domain.Profile
import app.peloskin.domain.RideSnapshot
import app.peloskin.domain.Settings
import app.peloskin.domain.Units
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The settings screen's font. */
val Figtree = FontFamily(
    Font(R.font.figtree_light, FontWeight.Light),
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

/** The skin's font: narrow, like the one Peloton's bike screen has used since late 2023. */
val InterTight = FontFamily(
    Font(R.font.intertight_extralight, FontWeight.ExtraLight),
    Font(R.font.intertight_light, FontWeight.Light),
    Font(R.font.intertight_regular, FontWeight.Normal),
    Font(R.font.intertight_medium, FontWeight.Medium),
    Font(R.font.intertight_semibold, FontWeight.SemiBold),
    Font(R.font.intertight_bold, FontWeight.Bold),
)

// Peloton's in-class colors: lighter slate tiles, darker navy panels.
internal val Ink = Color.White
internal val Dim = Color(0xFFD3D8E2)
internal val Faint = Color(0xFF97A0B3)
internal val Tile = Color(0x804A5670)
internal val Panel = Color(0xE61E2638)
internal val Band = Color(0x12FFFFFF)
internal val Up = Color(0xFF3FD28B)
internal val Down = Color(0xFFFF4A55)
internal val Gold = Color(0xFFE6B422)
private val Divider = Color(0x59FFFFFF)
private val ZoneOff = Color(0xFF363749)

/** Heart rate zones 1–5 in Peloton's colors (the ones Strive Score uses). */
val HeartRateZoneColors = listOf(
    Color(0xFF54C0A8), Color(0xFFA9BB56), Color(0xFFF9CC3F), Color(0xFFF28020), Color(0xFFE5383B),
)

/** Power zones 1–7 in Peloton's 2023 colors. */
val PowerZoneColors = listOf(
    Color(0xFF943DDC), Color(0xFF40ADE3), Color(0xFF23CE1D), Color(0xFFBCC31A),
    Color(0xFFF2C418), Color(0xFFE46B1C), Color(0xFFE5383B),
)

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
    /** [banner] is "Riding as …". The full layout skips it, since its rider bubble already shows who. */
    val riderSwitchBanner: Boolean = false,
    /** [banner] is a personal record, which the full layout shows as a card instead. */
    val record: SettingsStore.RecordCard? = null,
    /** The rider's heart rate, if they've paired a monitor. */
    val heartRate: HeartRateReading? = null,
)

/**
 * [zone] is 1–5, or 0 without a reading or a max heart rate; [zoneFraction] is how far into
 * that zone, for the meter. [searching] until the monitor connects. [zoneSeconds] is this
 * ride's time in each zone.
 */
data class HeartRateReading(
    val bpm: Int?,
    val zone: Int,
    val searching: Boolean,
    val percentOfMax: Int? = null,
    val zoneFraction: Float = 0f,
    val zoneSeconds: List<Int>? = null,
) {
    companion object {
        fun of(bpm: Int?, maxHr: Int?, searching: Boolean, zoneSeconds: List<Int>?): HeartRateReading {
            if (bpm == null || maxHr == null || maxHr <= 0) return HeartRateReading(bpm, 0, searching, zoneSeconds = zoneSeconds)
            val zone = HeartRateZones.zone(bpm, maxHr)
            val bounds = HeartRateZones.lowerBounds(maxHr) + maxHr
            val low = bounds[zone - 1]
            val high = bounds[zone]
            val fraction = if (high > low) (bpm - low).toFloat() / (high - low) else 1f
            return HeartRateReading(
                bpm, zone, searching, HeartRateZones.percentOfMax(bpm, maxHr), fraction.coerceIn(0.08f, 1f), zoneSeconds,
            )
        }
    }
}

/** A heart, drawn because the tablet's fonts don't all have one. */
@Composable
fun HeartIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w / 2, h * 0.95f)
            cubicTo(w * 0.05f, h * 0.62f, -w * 0.05f, h * 0.18f, w * 0.27f, h * 0.08f)
            cubicTo(w * 0.40f, h * 0.04f, w * 0.48f, h * 0.14f, w / 2, h * 0.24f)
            cubicTo(w * 0.52f, h * 0.14f, w * 0.60f, h * 0.04f, w * 0.73f, h * 0.08f)
            cubicTo(w * 1.05f, h * 0.18f, w * 0.95f, h * 0.62f, w / 2, h * 0.95f)
            close()
        }
        drawPath(p, color)
    }
}

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

/** Peloton's label: bold capitals, with the unit after it in lowercase brackets. */
@Composable
internal fun Label(text: String, s: Scale, size: Float = 0.86f, color: Color = Ink, unit: String? = null, modifier: Modifier = Modifier) = Text(
    buildAnnotatedString {
        append(text.uppercase())
        unit?.let { withStyle(SpanStyle(letterSpacing = 0.em)) { append(" ($it)") } }
    },
    color = color,
    modifier = modifier,
    maxLines = 1,
    style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = s.sp(size), letterSpacing = 0.05.em),
)

internal fun numberStyle(s: Scale, size: Float, weight: FontWeight = FontWeight.Light) =
    TextStyle(fontFamily = InterTight, fontWeight = weight, fontSize = s.sp(size), letterSpacing = (-0.01).em)

// ---------------------------------------------------------------- full skin

/**
 * The bottom of the full layout, like Peloton's: rider and the speed and distance tiles on the
 * left, cadence, output and resistance in the middle, total output and calories on the right.
 * Tap anywhere on it to switch to the single-line bar; hold it to end the ride.
 */
@Composable
fun FullSkin(state: SkinState, onToggle: () -> Unit, onNextRider: () -> Unit = {}, onEndRide: () -> Unit = {}) {
    val s = rememberScale()
    val live = state.live()
    val r = state.ride
    val hold = rememberHoldState()
    // AVG and BEST stay up after a ride ends, until the next one starts.
    val stats = live && (r.riding || r.elapsedSec > 0)
    val trend = live && r.riding
    Column(
        Modifier
            .fillMaxWidth()
            .tapOrHold(hold, enabled = live && r.riding, onTap = onToggle, onHoldDone = onEndRide)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x7304060C), Color(0xB304060C))))
            .padding(start = s.dp(1.3f), end = s.dp(1.3f), top = s.dp(2.4f), bottom = s.dp(1.5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusLine(state, hold, s)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            // Tapping the bubble switches rider; it takes the tap so the layout doesn't switch too.
            state.rider?.let { RiderBubble(it, s, diameter = 3f, onClick = onNextRider, modifier = Modifier.padding(end = s.dp(0.3f))) }
            MetricTile("Speed", state.units.speed(r.speedMph).oneDp(live), state.units.speedLabel, s)
            Spacer(Modifier.width(s.dp(0.55f)))
            MetricTile("Distance", state.units.distance(r.distanceMi).twoDp(live), state.units.distanceLabel, s)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.ftp != null) PowerZoneBox(r.power, state.ftp, live, s, Modifier.padding(bottom = s.dp(0.9f)))
                Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
                    BigMetric("Cadence", "rpm", r.cadence.whole(live), r.avgCadence, r.bestCadence, r.cadence, stats, trend, s)
                    MetricDivider()
                    BigMetric("Output", "watts", r.power.whole(live), r.avgPower, r.bestPower, r.power, stats, trend, s)
                    MetricDivider()
                    BigMetric("Resistance", "%", r.resistance.whole(live), r.avgResistance, r.bestResistance, r.resistance, stats, trend, s)
                }
            }
            MetricTile("Total output", r.totalKj.whole(live), "kj", s)
            Spacer(Modifier.width(s.dp(0.55f)))
            MetricTile("Calories", r.calories.whole(live), "kcal", s)
            FoldButtonView(expanded = true, s, Modifier.padding(start = s.dp(0.6f), bottom = s.dp(0.7f)))
        }
    }
}

/** A slate tile: label on top, value with a small unit under it. */
@Composable
private fun MetricTile(name: String, value: String, unit: String, s: Scale) {
    Column(
        Modifier
            .widthIn(min = s.dp(7.6f))
            .background(Tile, RoundedCornerShape(s.dp(0.35f)))
            .padding(start = s.dp(0.8f), end = s.dp(0.8f), top = s.dp(0.75f), bottom = s.dp(0.65f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(name, s, size = 0.78f)
        Spacer(Modifier.height(s.dp(0.3f)))
        Text(
            buildAnnotatedString {
                append(value)
                withStyle(SpanStyle(fontSize = s.sp(0.8f), letterSpacing = 0.em)) { append(" $unit") }
            },
            color = Ink, maxLines = 1, style = numberStyle(s, 1.55f, FontWeight.Normal),
        )
    }
}

/** Cadence, output or resistance: label above, AVG with a trend arrow, the big number, BEST. */
@Composable
private fun BigMetric(
    name: String, unit: String, value: String,
    avg: Float, best: Float, current: Float, showStats: Boolean, showTrend: Boolean, s: Scale,
) {
    Column(Modifier.padding(horizontal = s.dp(1.6f)), horizontalAlignment = Alignment.CenterHorizontally) {
        Label(name, s, unit = unit)
        Spacer(Modifier.height(s.dp(0.3f)))
        Row(verticalAlignment = Alignment.Bottom) {
            SideStat("Avg", if (showStats) avg.roundToInt().toString() else "--",
                trend = if (showTrend && avg > 0f) current >= avg else null, s = s)
            Text(
                value, color = Ink, modifier = Modifier.widthIn(min = s.dp(8.2f)).padding(horizontal = s.dp(0.5f)),
                style = numberStyle(s, 4.9f, FontWeight.ExtraLight).copy(textAlign = TextAlign.Center, letterSpacing = (-0.02).em),
            )
            SideStat("Best", if (showStats) best.roundToInt().toString() else "--", trend = null, s = s)
        }
    }
}

@Composable
private fun SideStat(label: String, value: String, trend: Boolean?, s: Scale) {
    Column(
        Modifier.widthIn(min = s.dp(3.2f)).padding(bottom = s.dp(0.55f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(label, s, size = 0.7f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            trend?.let { TrendArrow(up = it, s = s, modifier = Modifier.padding(end = s.dp(0.25f))) }
            Text(value, color = Ink, style = numberStyle(s, 1.55f))
        }
    }
}

/** Drawn rather than typed: the Peloton tablet's fonts have no ▲ ▼ glyphs. */
@Composable
private fun TrendArrow(up: Boolean, s: Scale, modifier: Modifier = Modifier) {
    Canvas(modifier.size(s.dp(0.66f), s.dp(0.5f))) {
        val path = Path().apply {
            if (up) { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            else { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
            close()
        }
        drawPath(path, if (up) Up else Down)
    }
}

@Composable
private fun MetricDivider() = Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 2.dp).background(Divider))

/** Peloton's power zone box: ZONE, seven segments with the watts each one starts at, % of FTP. */
@Composable
private fun PowerZoneBox(power: Float, ftp: Int, live: Boolean, s: Scale, modifier: Modifier = Modifier) {
    val zone = if (live) PowerZones.zone(power, ftp) else 0
    val bounds = PowerZones.lowerBounds(ftp)
    Row(
        modifier
            .background(Color(0xB8000000), RoundedCornerShape(s.dp(0.4f)))
            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(s.dp(0.4f)))
            .padding(horizontal = s.dp(1f), vertical = s.dp(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxStat("Zone", if (live) zone.toString() else "-", Alignment.Start, s)
        Spacer(Modifier.width(s.dp(1f)))
        Column {
            Row {
                repeat(PowerZones.Count) { i ->
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStart = s.dp(0.25f), bottomStart = s.dp(0.25f))
                        PowerZones.Count - 1 -> RoundedCornerShape(topEnd = s.dp(0.25f), bottomEnd = s.dp(0.25f))
                        else -> RoundedCornerShape(0.dp)
                    }
                    Box(
                        Modifier.padding(end = if (i < PowerZones.Count - 1) 1.dp else 0.dp)
                            .width(s.dp(2.1f)).height(s.dp(0.5f))
                            .background(if (i < zone) PowerZoneColors[i] else ZoneOff, shape)
                    )
                }
            }
            Spacer(Modifier.height(s.dp(0.3f)))
            Row {
                bounds.forEach { w ->
                    Text(w.toString(), color = Ink, modifier = Modifier.width(s.dp(2.1f)).padding(end = 1.dp),
                        style = numberStyle(s, 0.6f))
                }
            }
        }
        Spacer(Modifier.width(s.dp(1f)))
        BoxStat("FTP", if (live) "${PowerZones.percentOfFtp(power, ftp)}%" else "--", Alignment.End, s)
    }
}

@Composable
private fun BoxStat(label: String, value: String, align: Alignment.Horizontal, s: Scale) {
    Column(horizontalAlignment = align) {
        Text(label.uppercase(), color = Color(0xFF888A96), style = TextStyle(
            fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = s.sp(0.62f), letterSpacing = 0.1.em))
        Text(value, color = Ink, style = numberStyle(s, 1.25f))
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
            modifier = modifier.clip(CircleShape).border(2.dp, Color(0xD9FFFFFF), CircleShape),
        )
    } else {
        Box(modifier.background(riderColor(badge.colorIndex), CircleShape).border(2.dp, Color(0xD9FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Text(
                badge.initials,
                color = Color(0xFF14161A),
                style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = fontSize),
            )
        }
    }
}

/** Shows which way a tap will switch the layout, like Peloton's round chevron button. */
@Composable
private fun FoldButtonView(expanded: Boolean, s: Scale, modifier: Modifier = Modifier) {
    Box(
        modifier.size(s.dp(2.2f)).border(1.5.dp, Color(0xBFFFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Drawn rather than typed: the fonts have no chevron glyph.
        Canvas(Modifier.size(s.dp(0.9f), s.dp(0.5f))) {
            val path = Path().apply {
                if (expanded) { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
                else { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
            }
            drawPath(path, Ink, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun StatusLine(state: SkinState, hold: HoldState, s: Scale) {
    hold.progress?.let {
        HoldPrompt(it, s, Modifier.padding(bottom = s.dp(0.8f)))
        return
    }
    // Rider switches show in the bubble and records get their own card.
    val banner = state.banner?.takeUnless { state.riderSwitchBanner || state.record != null }
    val text = when {
        banner != null -> banner
        state.feed == FeedStatus.NotFound -> "Can't read the bike · is the sensor cable connected?"
        state.feed == FeedStatus.Unsupported -> "This bike isn't supported yet"
        state.feed == FeedStatus.Connecting -> "Connecting to the bike…"
        else -> null
    } ?: return
    Text(
        text,
        color = if (banner != null) Gold else Dim,
        modifier = Modifier
            .padding(bottom = s.dp(0.8f))
            .background(Panel, RoundedCornerShape(s.dp(0.35f)))
            .padding(horizontal = s.dp(1f), vertical = s.dp(0.45f)),
        style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = s.sp(0.9f)),
    )
}

// ------------------------------------------------------- heart rate panel

/**
 * Peloton's heart rate panel, for the left edge of the screen: zone, beats per minute and % of
 * max, the 5-zone meter, then time in zones 4–5 where Peloton has Strive Score.
 */
@Composable
fun HeartRatePanel(hr: HeartRateReading, s: Scale) {
    Column(Modifier.width(s.dp(16.5f)).background(Panel, RoundedCornerShape(s.dp(0.35f))).clip(RoundedCornerShape(s.dp(0.35f)))) {
        Label(if (hr.searching) "Finding monitor" else "Heart rate", s, size = 0.8f,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = s.dp(0.75f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = s.dp(1f)), verticalAlignment = Alignment.CenterVertically) {
            PanelStat("Zone", if (hr.zone > 0) hr.zone.toString() else "-", s, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(hr.bpm?.toString() ?: "--", color = Ink, style = numberStyle(s, 4.1f, FontWeight.ExtraLight).copy(letterSpacing = (-0.02).em))
                Text("bpm", color = Dim, style = numberStyle(s, 0.85f))
            }
            PanelStat("Max", hr.percentOfMax?.let { "$it%" } ?: "--", s, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = s.dp(1f), end = s.dp(1f), top = s.dp(0.7f), bottom = s.dp(0.85f)),
            horizontalArrangement = Arrangement.spacedBy(s.dp(0.25f)),
        ) {
            repeat(HeartRateZones.Count) { i ->
                Box(Modifier.weight(1f).height(s.dp(0.32f)).background(Color(0x2EFFFFFF), RoundedCornerShape(s.dp(0.16f)))) {
                    val fill = when {
                        i + 1 < hr.zone -> 1f
                        i + 1 == hr.zone -> hr.zoneFraction
                        else -> 0f
                    }
                    if (fill > 0f) Box(
                        Modifier.fillMaxWidth(fill).height(s.dp(0.32f))
                            .background(HeartRateZoneColors[i], RoundedCornerShape(s.dp(0.16f)))
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Band).padding(horizontal = s.dp(1f), vertical = s.dp(0.65f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Zones 4–5", s, size = 0.78f, modifier = Modifier.weight(1f))
            val z = hr.zoneSeconds
            ZoneRing(z, s)
            Spacer(Modifier.width(s.dp(0.45f)))
            Text(z?.let { clock(it[3] + it[4]) } ?: "--:--", color = Ink, style = numberStyle(s, 1.2f))
        }
    }
}

@Composable
private fun PanelStat(label: String, value: String, s: Scale, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(label, s, size = 0.68f)
        Text(value, color = Ink, style = numberStyle(s, 1.45f))
    }
}

/** A ring split by time in each heart rate zone, like Strive Score's. */
@Composable
private fun ZoneRing(seconds: List<Int>?, s: Scale) {
    Canvas(Modifier.size(s.dp(1.15f))) {
        val stroke = size.width * 0.2f
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        val total = seconds?.sum() ?: 0
        if (total == 0) {
            drawArc(ZoneOff, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            return@Canvas
        }
        var start = -90f
        seconds!!.forEachIndexed { i, sec ->
            val sweep = 360f * sec / total
            if (sweep > 0f) drawArc(HeartRateZoneColors[i], start, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke))
            start += sweep
        }
    }
}

// ------------------------------------------------------ personal record card

/** A best effort beaten mid-ride, for the top-right corner, like the PR row on Peloton's leaderboard. */
@Composable
fun RecordCardView(record: SettingsStore.RecordCard, s: Scale) {
    Column(Modifier.width(s.dp(19f)).background(Panel, RoundedCornerShape(s.dp(0.35f))).clip(RoundedCornerShape(s.dp(0.35f)))) {
        Row(Modifier.fillMaxWidth().background(Band).padding(horizontal = s.dp(1f), vertical = s.dp(0.6f)), verticalAlignment = Alignment.CenterVertically) {
            Star(Gold, Modifier.size(s.dp(1f)))
            Spacer(Modifier.width(s.dp(0.6f)))
            Label("${record.effort} personal record", s, size = 0.78f)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(s.dp(0.25f)).fillMaxHeight().background(Gold))
            Row(
                Modifier.weight(1f).padding(start = s.dp(0.75f), end = s.dp(1f), top = s.dp(0.6f), bottom = s.dp(0.7f)),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(record.watts.toString(), color = Ink, style = numberStyle(s, 2f))
                Text(" watts", color = Dim, modifier = Modifier.padding(bottom = s.dp(0.3f)), style = numberStyle(s, 0.85f))
                Spacer(Modifier.weight(1f))
                record.previous?.let {
                    Text("was $it", color = Dim, modifier = Modifier.padding(bottom = s.dp(0.3f)), style = numberStyle(s, 0.85f))
                }
            }
        }
    }
}

/** A five-pointed star, drawn because the tablet's fonts don't all have one. */
@Composable
internal fun Star(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val pts = listOf(0.50f to 0f, 0.61f to 0.35f, 0.98f to 0.35f, 0.68f to 0.57f, 0.79f to 0.91f,
            0.50f to 0.70f, 0.21f to 0.91f, 0.32f to 0.57f, 0.02f to 0.35f, 0.39f to 0.35f)
        val p = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
            close()
        }
        drawPath(p, color)
    }
}

internal fun clock(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val sec = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

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
            .background(Panel, RoundedCornerShape(s.dp(0.35f)))
            .padding(horizontal = s.dp(0.8f), vertical = s.dp(0.55f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.rider?.let { RiderBubble(it, s, diameter = 2.2f, onClick = onNextRider) }
        val holding = hold.progress
        if (holding != null) {
            HoldPrompt(holding, s, Modifier.padding(horizontal = s.dp(1.05f)))
        } else if (!live) {
            Text(
                when (state.feed) {
                    FeedStatus.NotFound -> "Can't read the bike"
                    FeedStatus.Unsupported -> "Bike not supported yet"
                    else -> "Connecting to the bike…"
                },
                color = Dim, modifier = Modifier.padding(horizontal = s.dp(1.05f)),
                style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Medium, fontSize = s.sp(0.9f)),
            )
        } else {
            var first = true
            @Composable fun sep() { if (!first) Box(Modifier.width(1.dp).height(s.dp(2f)).background(Color(0x38FFFFFF))); first = false }
            state.banner?.let { banner ->
                sep()
                Text(
                    banner.uppercase(), color = Gold, modifier = Modifier.padding(horizontal = s.dp(1.05f)),
                    style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = s.sp(0.8f), letterSpacing = 0.08.em),
                )
            }
            metrics.forEach { m ->
                sep()
                when (m) {
                    CompactMetric.Cadence -> CompactItem("Cadence", r.cadence.whole(true), "rpm", s)
                    CompactMetric.Output -> CompactItem("Output", r.power.whole(true), "watts", s) {
                        state.ftp?.let { ftp ->
                            Spacer(Modifier.width(s.dp(0.5f)))
                            MiniZones(PowerZones.zone(r.power, ftp), s)
                        }
                    }
                    CompactMetric.Resistance -> CompactItem("Resistance", r.resistance.whole(true), "%", s)
                    CompactMetric.Speed -> CompactItem("Speed", state.units.speed(r.speedMph).oneDp(true), state.units.speedLabel, s)
                    CompactMetric.Distance -> CompactItem("Distance", state.units.distance(r.distanceMi).twoDp(true), state.units.distanceLabel, s)
                    CompactMetric.TotalOutput -> CompactItem("Total output", r.totalKj.whole(true), "kj", s)
                    CompactMetric.Calories -> CompactItem("Calories", r.calories.whole(true), "kcal", s)
                }
            }
            // A rider with a heart rate monitor always gets their heart rate, at the end.
            state.heartRate?.let { hr ->
                sep()
                CompactItem("Heart rate", hr.bpm?.toString() ?: "--", "bpm", s)
            }
        }
        FoldButtonView(expanded = false, s, Modifier.padding(start = s.dp(0.5f)).size(s.dp(2f)))
    }
}

@Composable
private fun CompactItem(name: String, value: String, unit: String, s: Scale, extra: @Composable () -> Unit = {}) {
    Row(Modifier.padding(horizontal = s.dp(1.05f)), verticalAlignment = Alignment.CenterVertically) {
        Label(name, s, size = 0.68f)
        Spacer(Modifier.width(s.dp(0.4f)))
        Text(value, color = Ink, style = numberStyle(s, 1.95f, FontWeight.ExtraLight))
        Spacer(Modifier.width(s.dp(0.3f)))
        Text(unit, color = Ink, style = numberStyle(s, 0.75f, FontWeight.Medium))
        extra()
    }
}

@Composable
private fun MiniZones(zone: Int, s: Scale) {
    Row(horizontalArrangement = Arrangement.spacedBy(s.dp(0.15f))) {
        repeat(PowerZones.Count) { i ->
            Box(
                Modifier.width(s.dp(0.8f)).height(s.dp(0.32f))
                    .background(if (i < zone) PowerZoneColors[i] else ZoneOff, RoundedCornerShape(s.dp(0.15f)))
            )
        }
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
            style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = s.sp(0.8f), letterSpacing = 0.1.em),
        )
        Spacer(Modifier.width(s.dp(1f)))
        Box(Modifier.width(s.dp(12f)).height(s.dp(0.4f)).background(ZoneOff, RoundedCornerShape(s.dp(0.2f)))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(s.dp(0.4f)).background(Gold, RoundedCornerShape(s.dp(0.2f))))
        }
    }
}
