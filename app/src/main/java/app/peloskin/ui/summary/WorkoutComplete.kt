package app.peloskin.ui.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.peloskin.data.store.FinishedRide
import app.peloskin.domain.HeartRateZones
import app.peloskin.domain.NewRecord
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.PowerZones
import app.peloskin.domain.RideDetail
import app.peloskin.domain.Units
import app.peloskin.ui.overlay.Band
import app.peloskin.ui.overlay.Dim
import app.peloskin.ui.overlay.Down
import app.peloskin.ui.overlay.Gold
import app.peloskin.ui.overlay.HeartRateZoneColors
import app.peloskin.ui.overlay.Ink
import app.peloskin.ui.overlay.InterTight
import app.peloskin.ui.overlay.Label
import app.peloskin.ui.overlay.Panel
import app.peloskin.ui.overlay.PowerZoneColors
import app.peloskin.ui.overlay.RiderAvatar
import app.peloskin.ui.overlay.RiderBadge
import app.peloskin.ui.overlay.Scale
import app.peloskin.ui.overlay.Star
import app.peloskin.ui.overlay.Up
import app.peloskin.ui.overlay.clock
import app.peloskin.ui.overlay.numberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// The details sheet is light, like the one on Peloton's bike.
private val Sheet = Color(0xFFF3F2F7)
private val SheetInk = Color(0xFF1D1F2B)
private val SheetDim = Color(0xFF5D6072)
private val SheetRule = Color(0xFFDDDBE6)
private val GraphBg = Color(0xFFEBE9F2)
private val Track = Color(0xFFE2E0EA)
private val GraphLine = Color(0xFF1F2340)
private val HeartRed = Color(0xFFE5383B)
private val PrGold = Color(0xFFB8860B)
private val Check = Color(0xFF3FD28B)

// ------------------------------------------------------------------ top bar

/**
 * Across the top after a ride, like Peloton's: a green check and "Workout complete", then pill
 * buttons for the details page and closing.
 */
@Composable
fun WorkoutCompleteBar(detailsOpen: Boolean, onDetails: () -> Unit, onDone: () -> Unit, s: Scale) {
    Row(
        Modifier.fillMaxWidth().height(s.dp(5.2f))
            .background(Brush.verticalGradient(listOf(Color(0xEB121826), Color(0xB3121826)))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(s.dp(15.3f)).fillMaxHeight().background(Color(0x8C0A0E18)).padding(start = s.dp(1.3f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckCircle(s, Modifier.size(s.dp(1.8f)))
            Spacer(Modifier.width(s.dp(0.6f)))
            Column {
                Label("Workout", s, size = 0.82f)
                Label("complete", s, size = 0.82f)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            Pill(if (detailsOpen) "Summary" else "Workout details", chevron = !detailsOpen, s = s, onClick = onDetails)
            Spacer(Modifier.width(s.dp(0.8f)))
            Pill("Done", chevron = false, s = s, onClick = onDone)
        }
        Spacer(Modifier.width(s.dp(15.3f)))
    }
}

@Composable
private fun Pill(text: String, chevron: Boolean, s: Scale, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .background(Color(0xBF606E8C))
            .padding(horizontal = s.dp(1.8f), vertical = s.dp(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, s, size = 0.8f)
        if (chevron) {
            Spacer(Modifier.width(s.dp(0.6f)))
            Chevron(right = true, Ink, Modifier.size(s.dp(0.35f), s.dp(0.6f)))
        }
    }
}

@Composable
private fun CheckCircle(s: Scale, modifier: Modifier) {
    Box(modifier.border(2.dp, Check, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(s.dp(0.8f), s.dp(0.6f))) {
            val p = Path().apply {
                moveTo(0f, size.height * 0.55f); lineTo(size.width * 0.38f, size.height); lineTo(size.width, 0f)
            }
            drawPath(p, Check, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun Chevron(right: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            if (right) { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height) }
            else { moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height) }
        }
        drawPath(p, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ------------------------------------------------------------ summary panel

/**
 * Peloton's workout summary panel, for the top-right corner: the rider and their medals, then
 * the ride's totals. Totals that changed since the rider's last ride of the same length show
 * the change; a record total turns gold.
 */
@Composable
fun WorkoutSummaryPanel(f: FinishedRide, units: Units, rider: RiderBadge?, onBack: () -> Unit, s: Scale) {
    val r = f.ride
    val classRecord = f.newRecords.filterIsInstance<NewRecord.ClassLength>().any { it.previous != null }
    val effortPrs = f.newRecords.filterIsInstance<NewRecord.Effort>().filter { it.previous != null }
    Column(Modifier.width(s.dp(24f)).background(Panel, RoundedCornerShape(s.dp(0.35f))).clip(RoundedCornerShape(s.dp(0.35f)))) {
        Box(Modifier.fillMaxWidth().background(Band).padding(horizontal = s.dp(1f), vertical = s.dp(0.7f))) {
            Row(
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chevron(right = false, Ink, Modifier.size(s.dp(0.35f), s.dp(0.6f)))
                Spacer(Modifier.width(s.dp(0.35f)))
                Label("Back", s, size = 0.78f)
            }
            Label("Workout summary", s, size = 0.78f, modifier = Modifier.align(Alignment.Center))
        }
        Row(Modifier.padding(start = s.dp(1f), end = s.dp(1f), top = s.dp(1f), bottom = s.dp(0.6f)), verticalAlignment = Alignment.CenterVertically) {
            rider?.let {
                Box {
                    RiderAvatar(it, Modifier.size(s.dp(3.2f)), fontSize = s.sp(1.3f))
                    // A green check on the picture, like Peloton's.
                    Box(
                        Modifier.align(Alignment.BottomStart).offset(x = -s.dp(0.2f), y = s.dp(0.1f)).size(s.dp(1.2f))
                            .background(Color(0xFF1E2638), CircleShape).border(1.5.dp, Check, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(s.dp(0.55f), s.dp(0.4f))) {
                            val p = Path().apply { moveTo(0f, size.height * 0.55f); lineTo(size.width * 0.38f, size.height); lineTo(size.width, 0f) }
                            drawPath(p, Check, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Spacer(Modifier.width(s.dp(0.9f)))
            }
            Column {
                Text(f.profileName, color = Ink, maxLines = 1, style = numberStyle(s, 1.45f, FontWeight.SemiBold))
                Text("${r.durationSec / 60} min ride · ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(r.startedAt))}",
                    color = Dim, style = numberStyle(s, 0.85f))
            }
        }
        if (f.newFtp != null || effortPrs.isNotEmpty()) {
            Row(Modifier.padding(start = s.dp(1f), end = s.dp(1f), bottom = s.dp(0.9f)), horizontalArrangement = Arrangement.spacedBy(s.dp(0.6f))) {
                f.newFtp?.let { Medal("FTP", it.toString(), s) }
                effortPrs.take(4).forEach { PrTag(PersonalRecords.effortLabel(it.seconds), s) }
            }
        }
        SummaryRow("Total output", "kj", r.totalKj.toString(), f.previous?.let { r.totalKj - it.totalKj }, gold = classRecord, s = s)
        SummaryRow("Avg output", "watts", r.avgPower.toString(), f.previous?.let { r.avgPower - it.avgPower }, s = s)
        // Rides saved by older versions don't have these, so they show as dashes, not zeros.
        SummaryRow("Avg resistance", "%", r.avgResistance.orDash(), null, s = s)
        SummaryRow("Avg cadence", "rpm", r.avgCadence.orDash(), null, s = s)
        SummaryRow("Distance", units.distanceLabel, if (r.distanceMi > 0f) "%.2f".format(units.distance(r.distanceMi)) else "--", null, s = s)
        SummaryRow("Calories", "kcal", r.calories.orDash(), null, s = s)
        r.avgHeartRate?.let { SummaryRow("Avg heart rate", "bpm", it.toString(), null, s = s) }
    }
}

/** A gold star medal, for a new FTP. */
@Composable
private fun Medal(label: String, value: String, s: Scale) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(s.dp(2.6f)).background(Brush.radialGradient(listOf(Color(0xFFFFE08A), Gold, Color(0xFFB8860B))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Star(Color(0xFFFFF6D6), Modifier.size(s.dp(1.1f))) }
        Spacer(Modifier.width(s.dp(0.4f)))
        Column {
            Label(label, s, size = 0.62f, color = Dim)
            Text(value, color = Ink, style = numberStyle(s, 1f, FontWeight.SemiBold))
        }
    }
}

/** A blue badge for a personal record, like Peloton's milestone badges. */
@Composable
private fun PrTag(effort: String, s: Scale) {
    Column(
        Modifier.height(s.dp(2.6f)).widthIn(min = s.dp(2.4f))
            .background(Brush.verticalGradient(listOf(Color(0xFF7FB2E6), Color(0xFF3D78B8))), RoundedCornerShape(s.dp(0.45f)))
            .padding(horizontal = s.dp(0.5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PR", color = Ink, style = numberStyle(s, 0.8f, FontWeight.Bold))
        Text(effort, color = Ink, style = numberStyle(s, 0.55f, FontWeight.SemiBold))
    }
}

@Composable
private fun SummaryRow(name: String, unit: String, value: String, change: Int?, gold: Boolean = false, s: Scale) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
    Row(Modifier.fillMaxWidth().padding(horizontal = s.dp(1f), vertical = s.dp(0.6f)), verticalAlignment = Alignment.CenterVertically) {
        Label(name, s, size = 0.78f, unit = unit, modifier = Modifier.weight(1f))
        if (change != null && change != 0) {
            Text(
                (if (change > 0) "+" else "−") + abs(change), color = if (change > 0) Up else Down,
                modifier = Modifier.padding(end = s.dp(0.45f)), style = numberStyle(s, 0.8f, FontWeight.SemiBold),
            )
        }
        Text(value, color = if (gold) Gold else Ink, style = numberStyle(s, 1.6f))
    }
}

// ----------------------------------------------------------- details sheet

/**
 * The workout details page: a light sheet, like Peloton's, with the output graph and time in
 * each power zone on the left, and heart rate and best efforts on the right. [header] adds the
 * rider and totals, for opening a past ride from settings.
 */
@Composable
fun WorkoutDetailsSheet(f: FinishedRide, units: Units, s: Scale, modifier: Modifier = Modifier, rider: RiderBadge? = null, header: Boolean = false) {
    val r = f.ride
    Column(modifier.background(Sheet, RoundedCornerShape(s.dp(0.4f))).clip(RoundedCornerShape(s.dp(0.4f)))) {
        if (header) {
            DetailsHeader(f, units, rider, s)
            Box(Modifier.fillMaxWidth().height(1.dp).background(SheetRule))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1.15f).padding(horizontal = s.dp(1.6f), vertical = s.dp(1.3f)), verticalArrangement = Arrangement.spacedBy(s.dp(0.75f))) {
                Title("Power zones", s)
                val graph = f.detail?.takeIf { it.powerEvery5s.size >= 2 }
                GraphHeader("Output", listOfNotNull(
                    (r.bestEfforts[5] ?: graph?.powerEvery5s?.max())?.let { "BEST $it WATTS" }, "AVG ${r.avgPower} WATTS"), s)
                if (graph != null) {
                    OutputGraph(graph, r.ftp, Modifier.fillMaxWidth().height(s.dp(7.5f)))
                    MinuteAxis(r.durationSec, s)
                } else {
                    Note("No graph for this ride.", s)
                }
                val zones = r.zoneSeconds
                val ftp = r.ftp
                if (zones != null && ftp != null) {
                    Caption("Time in zone · FTP $ftp", s)
                    val bounds = PowerZones.lowerBounds(ftp)
                    val longest = (zones.maxOrNull() ?: 0).coerceAtLeast(1)
                    zones.forEachIndexed { i, sec ->
                        val range = if (i == zones.lastIndex) "${bounds[i]}+ W" else "${bounds[i]}–${bounds[i + 1]} W"
                        ZoneRow("Zone ${i + 1}", range, sec, longest, PowerZoneColors[i], s)
                    }
                } else {
                    Note("No power zones: this ride had no FTP to work them out with.", s)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(SheetRule))
            Column(Modifier.weight(1f).padding(horizontal = s.dp(1.6f), vertical = s.dp(1.3f)), verticalArrangement = Arrangement.spacedBy(s.dp(0.75f))) {
                val hr = f.detail?.heartRateEvery5s?.takeIf { p -> p.count { it > 0 } >= 2 }
                if (r.avgHeartRate != null || hr != null) {
                    Title("Heart rate zones", s)
                    GraphHeader("Heart rate", listOfNotNull(r.maxHeartRate?.let { "MAX $it BPM" }, r.avgHeartRate?.let { "AVG $it BPM" }), s)
                    hr?.let { HeartRateGraph(it, Modifier.fillMaxWidth().height(s.dp(5f))) }
                    val hz = r.heartRateZoneSeconds
                    val max = r.maxHr
                    if (hz != null && max != null) {
                        val most = hz.indexOf(hz.max()) + 1
                        Caption("Time in zone · most in zone $most", s)
                        val bounds = HeartRateZones.lowerBounds(max)
                        val longest = hz.max().coerceAtLeast(1)
                        hz.forEachIndexed { i, sec ->
                            val range = when (i) {
                                0 -> "<${bounds[1]} bpm"
                                hz.lastIndex -> "${bounds[i]}+"
                                else -> "${bounds[i]}–${bounds[i + 1]}"
                            }
                            ZoneRow("Zone ${i + 1}", range, sec, longest, HeartRateZoneColors[i], s)
                        }
                    }
                    Spacer(Modifier.height(s.dp(0.2f)))
                    Caption("Best efforts", s)
                } else {
                    Title("Best efforts", s)
                }
                BestEfforts(f, s)
            }
        }
    }
}

@Composable
private fun DetailsHeader(f: FinishedRide, units: Units, rider: RiderBadge?, s: Scale) {
    val r = f.ride
    Row(Modifier.fillMaxWidth().padding(horizontal = s.dp(1.6f), vertical = s.dp(1.1f)), verticalAlignment = Alignment.CenterVertically) {
        rider?.let {
            RiderAvatar(it, Modifier.size(s.dp(3.2f)), fontSize = s.sp(1.3f))
            Spacer(Modifier.width(s.dp(0.9f)))
        }
        Column(Modifier.weight(1f)) {
            Text("${r.durationSec / 60} min ride", color = SheetInk, style = numberStyle(s, 1.6f, FontWeight.SemiBold))
            Text("${f.profileName} · ${SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(r.startedAt))}",
                color = SheetDim, style = numberStyle(s, 0.9f))
        }
        HeaderStat("Total output", "kj", r.totalKj.toString(), s)
        HeaderStat("Avg output", "watts", r.avgPower.toString(), s)
        HeaderStat("Distance", units.distanceLabel, if (r.distanceMi > 0f) "%.2f".format(units.distance(r.distanceMi)) else "--", s)
        HeaderStat("Calories", "kcal", r.calories.orDash(), s)
    }
}

@Composable
private fun HeaderStat(name: String, unit: String, value: String, s: Scale) {
    Column(Modifier.padding(start = s.dp(2f)), horizontalAlignment = Alignment.CenterHorizontally) {
        Label(name, s, size = 0.68f, color = SheetDim, unit = unit)
        Text(value, color = SheetInk, style = numberStyle(s, 1.6f))
    }
}

@Composable
private fun Title(text: String, s: Scale) =
    Text(text, color = SheetInk, style = numberStyle(s, 1.45f, FontWeight.Medium))

@Composable
private fun GraphHeader(name: String, stats: List<String>, s: Scale) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(name, color = SheetInk, modifier = Modifier.weight(1f), style = numberStyle(s, 0.95f, FontWeight.SemiBold))
        stats.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.width(s.dp(1f)))
            Text(t, color = SheetDim, style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = s.sp(0.68f), letterSpacing = 0.08.em))
        }
    }
}

@Composable
private fun Caption(text: String, s: Scale) = Text(
    text.uppercase(), color = SheetDim,
    style = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = s.sp(0.66f), letterSpacing = 0.08.em),
)

@Composable
private fun Note(text: String, s: Scale) = Text(text, color = SheetDim, style = numberStyle(s, 0.85f))

@Composable
private fun MinuteAxis(durationSec: Int, s: Scale) {
    val minutes = (durationSec / 60).coerceAtLeast(1)
    val step = listOf(1, 2, 5, 10, 15, 20, 30).first { minutes / it <= 6 }
    Row(Modifier.fillMaxWidth().offset(y = -s.dp(0.4f))) {
        val ticks = (0..minutes step step).toList()
        ticks.forEachIndexed { i, m ->
            Text(m.toString(), color = SheetDim, style = numberStyle(s, 0.6f))
            if (i < ticks.lastIndex) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ZoneRow(name: String, range: String, seconds: Int, longest: Int, color: Color, s: Scale) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append(name)
                withStyle(SpanStyle(fontSize = s.sp(0.7f), color = SheetDim, fontWeight = FontWeight.Normal)) { append("  $range") }
            },
            color = SheetInk, maxLines = 1, modifier = Modifier.width(s.dp(8.6f)),
            style = numberStyle(s, 0.85f, FontWeight.SemiBold),
        )
        Spacer(Modifier.width(s.dp(0.8f)))
        Box(Modifier.weight(1f).height(s.dp(0.75f)).background(Track, RoundedCornerShape(s.dp(0.4f)))) {
            if (seconds > 0) Box(
                Modifier.fillMaxWidth(seconds.toFloat() / longest).height(s.dp(0.75f)).background(color, RoundedCornerShape(s.dp(0.4f)))
            )
        }
        Text(clock(seconds), color = SheetInk, modifier = Modifier.width(s.dp(3.6f)),
            style = numberStyle(s, 0.85f).copy(textAlign = TextAlign.End))
    }
}

@Composable
private fun BestEfforts(f: FinishedRide, s: Scale) {
    val r = f.ride
    val prs = f.newRecords.filterIsInstance<NewRecord.Effort>().filter { it.previous != null }.map { it.seconds }.toSet()
    Column {
        PersonalRecords.EffortWindows.forEach { w ->
            r.bestEfforts[w]?.let { watts -> BestRow(PersonalRecords.effortLabel(w), "$watts W", w in prs, s) }
        }
        f.newRecords.filterIsInstance<NewRecord.ClassLength>().firstOrNull()?.let { rec ->
            if (rec.previous != null) BestRow("${rec.minutes}-min ride", "${rec.value} kJ", true, s)
        }
        f.newFtp?.let { BestRow("New FTP", "$it W", true, s) }
        if (r.bestEfforts.isEmpty() && f.newFtp == null) Note("Ride at least 5 seconds to see best efforts.", s)
    }
}

@Composable
private fun BestRow(name: String, value: String, isPr: Boolean, s: Scale) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SheetRule))
    Row(Modifier.fillMaxWidth().padding(vertical = s.dp(0.4f))) {
        Text(name, color = SheetInk, modifier = Modifier.weight(1f), style = numberStyle(s, 0.95f))
        Text(if (isPr) "$value · PR" else value, color = if (isPr) PrGold else SheetInk,
            style = numberStyle(s, 0.95f, if (isPr) FontWeight.SemiBold else FontWeight.Medium))
    }
}

@Composable
private fun OutputGraph(d: RideDetail, ftp: Int?, modifier: Modifier) {
    val points = d.powerEvery5s
    val top = (maxOf(points.max(), ftp ?: 0) * 1.1f).coerceAtLeast(50f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(GraphBg)
        fun x(i: Int) = i * w / (points.size - 1)
        fun y(p: Int) = h - p / top * h
        ftp?.let {
            drawLine(PrGold.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(w, y(it)), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val line = Path().apply {
            moveTo(0f, y(points[0]))
            for (i in 1 until points.size) lineTo(x(i), y(points[i]))
        }
        drawPath(line, GraphLine, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Heart rate for each 5 seconds; gaps without a reading are left out of the line. */
@Composable
private fun HeartRateGraph(points: List<Int>, modifier: Modifier) {
    val beats = points.filter { it > 0 }
    val low = (beats.min() - 10).coerceAtLeast(40).toFloat()
    val high = (beats.max() + 10).toFloat()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(GraphBg)
        fun x(i: Int) = i * w / (points.size - 1).coerceAtLeast(1)
        fun y(bpm: Int) = h - (bpm - low) / (high - low) * h
        val line = Path()
        var drawing = false
        points.forEachIndexed { i, bpm ->
            if (bpm <= 0) { drawing = false; return@forEachIndexed }
            if (drawing) line.lineTo(x(i), y(bpm)) else line.moveTo(x(i), y(bpm))
            drawing = true
        }
        drawPath(line, HeartRed, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun Int.orDash() = if (this > 0) toString() else "--"
