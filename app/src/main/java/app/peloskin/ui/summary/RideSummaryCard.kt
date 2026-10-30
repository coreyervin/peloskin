package app.peloskin.ui.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.peloskin.data.store.FinishedRide
import app.peloskin.domain.NewRecord
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.RideDetail
import app.peloskin.domain.Units
import app.peloskin.ui.overlay.Figtree
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val Card = Color(0xFF181A1F)
private val Ink = Color.White
private val Dim = Color(0xFFC9CCD2)
private val Faint = Color(0xFF6E737D)
private val Rule = Color(0x29FFFFFF)
private val Teal = Color(0xFF41C7C7)
private val Gold = Color(0xFFE6B422)
private val Up = Color(0xFF3FD28B)
private val Down = Color(0xFFFF4A55)

/** Zone colors, cool to warm, so harder zones read hotter at a glance. */
private val ZoneColors = listOf(
    Color(0xFF5B8DB8), Color(0xFF41C7C7), Color(0xFF3FD28B), Color(0xFFE6B422),
    Color(0xFFF08A3C), Color(0xFFFF5A4E), Color(0xFFD04ACF),
)

private fun label(size: Int = 12, color: Color = Dim) =
    TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = size.sp, letterSpacing = 0.14.em, color = color)

/** After-ride summary: totals, output graph, time in zones, bests and a comparison. */
@Composable
fun RideSummaryCard(f: FinishedRide, units: Units, modifier: Modifier = Modifier, footer: String? = null) {
    val r = f.ride
    Column(
        modifier.widthIn(max = 820.dp).background(Card, RoundedCornerShape(12.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("RIDE COMPLETE", style = label(12, Teal))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${r.durationSec / 60} min ride",
                    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = Ink),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${f.profileName} · ${SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(r.startedAt))}",
                    style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, color = Dim),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        // Totals
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BigStat("Total output", r.totalKj.toString(), "kJ", f.previous?.let { r.totalKj - it.totalKj })
            BigStat("Avg output", r.avgPower.toString(), "watts", f.previous?.let { r.avgPower - it.avgPower })
            // Rides saved by older versions don't have these, so they show as dashes, not zeros.
            BigStat("Avg cadence", r.avgCadence.orDash(), "rpm", null)
            BigStat("Distance", if (r.distanceMi > 0f) "%.2f".format(units.distance(r.distanceMi)) else "--", units.distanceLabel, null)
            BigStat("Calories", r.calories.orDash(), "kcal", null)
        }
        f.previous?.let { p ->
            Text(
                "Compared with your ${p.durationSec / 60}-min ride on ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(p.startedAt))}",
                style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, color = Faint),
            )
        }

        // Output graph
        f.detail?.takeIf { it.powerEvery5s.size >= 2 }?.let { d ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OUTPUT", style = label())
                OutputGraph(d, r.ftp, Modifier.fillMaxWidth().height(150.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0:00", style = TextStyle(fontFamily = Figtree, fontSize = 12.sp, color = Faint))
                    Text(clock(r.durationSec), style = TextStyle(fontFamily = Figtree, fontSize = 12.sp, color = Faint))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // Time in zones
            r.zoneSeconds?.let { z ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TIME IN ZONES${r.ftp?.let { " · FTP $it" } ?: ""}", style = label())
                    val longest = (z.maxOrNull() ?: 0).coerceAtLeast(1)
                    z.forEachIndexed { i, sec -> ZoneRow(i + 1, sec, longest) }
                }
            }
            // Bests
            val hasBests = r.bestEfforts.isNotEmpty() || f.newFtp != null
            if (hasBests) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BEST EFFORTS", style = label())
                val prs = f.newRecords.filterIsInstance<NewRecord.Effort>().filter { it.previous != null }.map { it.seconds }.toSet()
                PersonalRecords.EffortWindows.forEach { w ->
                    r.bestEfforts[w]?.let { watts -> BestRow(PersonalRecords.effortLabel(w), "$watts W", w in prs) }
                }
                f.newRecords.filterIsInstance<NewRecord.ClassLength>().firstOrNull()?.let { rec ->
                    if (rec.previous != null) BestRow("${rec.minutes}-min ride", "${rec.value} kJ", true)
                }
                f.newFtp?.let { BestRow("New FTP", "$it W", true) }
            }
        }

        footer?.let {
            Text(it, style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, color = Faint, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BigStat(name: String, value: String, unit: String, change: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name.uppercase(), style = label(11))
        Spacer(Modifier.height(4.dp))
        Text(value, style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Light, fontSize = 38.sp, color = Ink))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(unit, style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, color = Dim))
            if (change != null && change != 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (change > 0) "+" else "−") + abs(change),
                    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        color = if (change > 0) Up else Down),
                )
            }
        }
    }
}

@Composable
private fun OutputGraph(d: RideDetail, ftp: Int?, modifier: Modifier) {
    val points = d.powerEvery5s
    val top = (maxOf(points.max(), ftp ?: 0) * 1.1f).coerceAtLeast(50f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun x(i: Int) = i * w / (points.size - 1)
        fun y(p: Int) = h - p / top * h
        // Faint guide lines at a quarter, half and three quarters of the scale.
        for (k in 1..3) drawLine(Rule, Offset(0f, h * k / 4), Offset(w, h * k / 4), strokeWidth = 1f)
        val line = Path().apply {
            moveTo(0f, y(points[0]))
            for (i in 1 until points.size) lineTo(x(i), y(points[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(Teal.copy(alpha = 0.45f), Teal.copy(alpha = 0.03f))))
        drawPath(line, Teal, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        ftp?.let {
            drawLine(Gold.copy(alpha = 0.8f), Offset(0f, y(it)), Offset(w, y(it)), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}

@Composable
private fun ZoneRow(zone: Int, seconds: Int, longest: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Z$zone", style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Dim),
            modifier = Modifier.width(28.dp))
        Box(Modifier.weight(1f).height(10.dp).background(Color(0xFF2A2D34), RoundedCornerShape(3.dp))) {
            if (seconds > 0) {
                Box(
                    Modifier.fillMaxWidth(seconds.toFloat() / longest).height(10.dp)
                        .background(ZoneColors[zone - 1], RoundedCornerShape(3.dp))
                )
            }
        }
        Text(clock(seconds), style = TextStyle(fontFamily = Figtree, fontSize = 13.sp, color = Ink, textAlign = TextAlign.End),
            modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun BestRow(name: String, value: String, isPr: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = TextStyle(fontFamily = Figtree, fontSize = 15.sp, color = Dim), modifier = Modifier.weight(1f))
        if (isPr) {
            Text("PR", style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                letterSpacing = 0.1.em, color = Card),
                modifier = Modifier.background(Gold, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = TextStyle(fontFamily = Figtree, fontSize = 18.sp, color = if (isPr) Gold else Ink))
    }
}

private fun Int.orDash() = if (this > 0) toString() else "--"

private fun clock(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
