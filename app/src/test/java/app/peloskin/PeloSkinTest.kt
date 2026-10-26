package app.peloskin

import app.peloskin.data.store.CheckpointJson
import app.peloskin.data.store.SettingsJson
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample
import app.peloskin.domain.CompactEdge
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.Ftp
import app.peloskin.domain.FtpSource
import app.peloskin.domain.PowerZones
import app.peloskin.domain.RideSummary
import app.peloskin.domain.RideTracker
import app.peloskin.domain.Settings
import app.peloskin.domain.SkinMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeloSkinTest {

    @Test fun powerOnlyCountsWhilePedaling() {
        // The flywheel sensor can report spikes while the cranks are still.
        assertEquals(0f, BikeMath.sample(850f, 0f, 30f).power, 0f)
        assertEquals(150f, BikeMath.sample(150f, 80f, 30f).power, 0f)
        assertEquals(2500f, BikeMath.sample(90_000f, 80f, 30f).power, 0f)
        assertEquals(0f, BikeMath.sample(Float.NaN, 80f, 30f).power, 0f)
    }

    @Test fun resistanceIsClampedToPercent() {
        assertEquals(100f, BikeMath.sample(100f, 80f, 140f).resistance, 0f)
        assertEquals(0f, BikeMath.sample(100f, 80f, -3f).resistance, 0f)
    }

    @Test fun speedFollowsPelotonCurve() {
        assertEquals(0f, BikeMath.speedFromPower(0f), 0f)
        // Reference points from the PeloMon fit.
        assertEquals(7.40f, BikeMath.speedFromPower(20f), 0.05f)
        assertEquals(19.08f, BikeMath.speedFromPower(150f), 0.05f)
        assertEquals(22.46f, BikeMath.speedFromPower(232f), 0.05f)
        // Faster with more power.
        assertTrue(BikeMath.speedFromPower(300f) > BikeMath.speedFromPower(200f))
    }

    @Test fun steadyRideTotalsAndFtp() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { finished += it })
        val start = 1_000_000L
        // 25 minutes at 200 W / 90 rpm, one sample per second.
        for (i in 0..1500) t.onSample(start + i * 1000L, BikeSample(200f, 90f, 40f, 20f))
        val s = t.snapshot
        assertTrue(s.riding)
        assertEquals(1500, s.elapsedSec)
        assertEquals(300f, s.totalKj, 0.5f)
        assertEquals(200f, s.avgPower, 0.01f)
        assertEquals(90f, s.avgCadence, 0.01f)
        assertEquals(200f, s.best20Watts!!, 0.01f)
        assertEquals(20f * 1500 / 3600, s.distanceMi, 0.01f)
        assertEquals(300_000 / 4184.0 / 0.22, s.calories.toDouble(), 1.0)

        // Stop pedaling: the ride ends after 5 idle minutes.
        for (i in 1..310) t.onSample(start + (1500 + i) * 1000L, BikeSample(0f, 0f, 40f, 0f))
        assertEquals(false, t.snapshot.riding)
        assertEquals(1, finished.size)
        // The 5 idle minutes before the ride ended aren't part of it.
        assertEquals(1500, finished[0].durationSec)
        assertEquals(200, finished[0].best20Watts)
        assertEquals(190, Ftp.fromBest20(finished[0].best20Watts!!))
    }

    @Test fun best20FindsHardestWindow() {
        val t = RideTracker()
        val start = 0L
        // 5 easy minutes, 20 hard minutes, 5 easy minutes.
        var sec = 0
        repeat(300) { t.onSample(start + sec++ * 1000L, BikeSample(100f, 80f, 30f, 15f)) }
        repeat(1200) { t.onSample(start + sec++ * 1000L, BikeSample(250f, 95f, 45f, 22f)) }
        repeat(300) { t.onSample(start + sec++ * 1000L, BikeSample(100f, 80f, 30f, 15f)) }
        assertEquals(250f, t.snapshot.best20Watts!!, 0.5f)
    }

    @Test fun pausesDoNotCountTowardAverages() {
        val t = RideTracker()
        var sec = 0
        repeat(60) { t.onSample(sec++ * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        repeat(60) { t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 40f, 0f)) }
        assertEquals(200f, t.snapshot.avgPower, 0.01f)
        assertEquals(90f, t.snapshot.avgCadence, 0.01f)
        assertNull(t.snapshot.best20Watts)
    }

    @Test fun bumpingThePedalsIsNotARide() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { finished += it })
        var sec = 0
        repeat(2) { t.onSample(sec++ * 1000L, BikeSample(50f, 30f, 20f, 5f)) }
        repeat(400) { t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 20f, 0f)) }
        assertEquals(false, t.snapshot.riding)
        assertTrue(finished.isEmpty())
    }

    @Test fun rideEndsFromTickWhenFeedStops() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { finished += it })
        for (i in 0..600) t.onSample(i * 1000L, BikeSample(150f, 85f, 35f, 18f))
        t.tick(600_000L + 4 * 60_000L)
        assertTrue(t.snapshot.riding)
        t.tick(600_000L + 5 * 60_000L)
        assertEquals(false, t.snapshot.riding)
        assertEquals(600, finished.single().durationSec)
    }

    @Test fun longRideFindsHardestWindow() {
        // Three hours: easy, then 20 hard minutes in the middle, then easy again.
        val t = RideTracker()
        var sec = 0
        repeat(5000) { t.onSample(sec++ * 1000L, BikeSample(120f, 80f, 30f, 16f)) }
        repeat(1200) { t.onSample(sec++ * 1000L, BikeSample(260f, 95f, 45f, 23f)) }
        repeat(4600) { t.onSample(sec++ * 1000L, BikeSample(120f, 80f, 30f, 16f)) }
        assertEquals(260f, t.snapshot.best20Watts!!, 0.5f)
    }

    @Test fun rideResumesFromCheckpoint() {
        val a = RideTracker()
        var sec = 0
        repeat(1300) { a.onSample(sec++ * 1000L, BikeSample(210f, 90f, 40f, 20f)) }
        // Through JSON, the way it's stored on the tablet.
        val saved = CheckpointJson.decode(CheckpointJson.encode(a.checkpoint(sec * 1000L)!!))!!

        val b = RideTracker()
        assertTrue(b.resume(saved, sec * 1000L + 5_000))
        assertEquals(a.snapshot.totalKj, b.snapshot.totalKj, 0.01f)
        assertEquals(a.snapshot.best20Watts!!, b.snapshot.best20Watts!!, 0.01f)
        // Keeps riding where it left off.
        repeat(100) { b.onSample(sec++ * 1000L + 5_000, BikeSample(210f, 90f, 40f, 20f)) }
        assertEquals(210f, b.snapshot.best20Watts!!, 0.5f)
        assertTrue(b.snapshot.elapsedSec > a.snapshot.elapsedSec)
    }

    @Test fun staleCheckpointsAreIgnored() {
        val a = RideTracker()
        repeat(60) { a.onSample(it * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        val saved = a.checkpoint(60_000L)!!
        // Saved "in the future" means the tablet rebooted since.
        assertEquals(false, RideTracker().resume(saved, 30_000L))
        // Too long since the last pedal stroke.
        assertEquals(false, RideTracker().resume(saved, 60_000L + 6 * 60_000L))
        assertNull(CheckpointJson.decode("not json"))
        assertNull(RideTracker().checkpoint(0L))
    }

    @Test fun powerZones() {
        assertEquals(1, PowerZones.zone(100f, 200))   // 50%
        assertEquals(2, PowerZones.zone(150f, 200))   // 75%
        assertEquals(3, PowerZones.zone(232f, 305))   // 76%
        assertEquals(4, PowerZones.zone(200f, 200))   // 100%
        assertEquals(5, PowerZones.zone(230f, 200))   // 115%
        assertEquals(6, PowerZones.zone(280f, 200))   // 140%
        assertEquals(7, PowerZones.zone(400f, 200))   // 200%
        assertEquals(76, PowerZones.percentOfFtp(232f, 305))
        assertEquals(0, PowerZones.zone(200f, 0))
        assertEquals(0, PowerZones.percentOfFtp(0f, 0))
    }

    @Test fun ftpOnlyRises() {
        val ride = RideSummary(0, 1800, 180, 320, best20Watts = 200)
        assertEquals(190, Ftp.afterRide(null, autoUpdate = false, ride = ride))
        assertEquals(190, Ftp.afterRide(180, autoUpdate = true, ride = ride))
        assertNull(Ftp.afterRide(180, autoUpdate = false, ride = ride))
        assertNull(Ftp.afterRide(250, autoUpdate = true, ride = ride))
        assertNull(Ftp.afterRide(null, autoUpdate = true, ride = ride.copy(best20Watts = null)))
    }

    @Test fun settingsSurviveJsonRoundTrip() {
        val s = Settings(
            ftp = 243, ftpSource = FtpSource.Ride, ftpSetAt = 1234L, autoFtp = false,
            startMode = SkinMode.Compact, compactEdge = CompactEdge.Top,
            compactMetrics = listOf(CompactMetric.Output, CompactMetric.Calories),
            startOnBoot = false,
            rides = listOf(RideSummary(5L, 1800, 180, 320, 256), RideSummary(6L, 600, 120, 70, null)),
        )
        assertEquals(s, SettingsJson.decode(SettingsJson.encode(s)))
    }

    @Test fun zeroFtpInBackupIsIgnored() {
        assertNull(SettingsJson.decode("""{"ftp":0}""").ftp)
        assertNull(SettingsJson.decode("""{"ftp":-5}""").ftp)
    }

    @Test fun oldOrPartialBackupUsesDefaults() {
        val s = SettingsJson.decode("""{"version":1,"ftp":200,"startMode":"Sideways"}""")
        assertEquals(200, s.ftp)
        assertEquals(SkinMode.Full, s.startMode)
        assertNotNull(s.compactMetrics.firstOrNull())
    }
}
