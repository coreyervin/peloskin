package app.peloskin

import app.peloskin.feed.BikeSample
import app.peloskin.feed.DirCon
import app.peloskin.feed.HeartRateParser
import app.peloskin.feed.IndoorBikeDataParser
import app.peloskin.ride.Ftp
import app.peloskin.ride.FtpSource
import app.peloskin.ride.PowerZones
import app.peloskin.ride.RideSummary
import app.peloskin.ride.RideTracker
import app.peloskin.store.CompactEdge
import app.peloskin.store.CompactMetric
import app.peloskin.store.Settings
import app.peloskin.store.SettingsJson
import app.peloskin.store.SkinMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeloSkinTest {

    /** Same layout Grupetto sends: speed, cadence, resistance, power. */
    private fun grupettoBikeData(cadence: Float, power: Int, speedMph: Float, resistance: Int): ByteArray {
        val flags = 0x0004 or 0x0020 or 0x0040
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(flags.toShort())
            .putShort((speedMph * 1.60934f * 100).toInt().toShort())
            .putShort((cadence * 2).toInt().toShort())
            .putShort(resistance.toShort())
            .putShort(power.toShort())
            .array()
    }

    @Test fun parsesGrupettoIndoorBikeData() {
        val s = IndoorBikeDataParser.parse(grupettoBikeData(84f, 232, 22.7f, 51))!!
        assertEquals(84f, s.cadence!!, 0.01f)
        assertEquals(232f, s.power!!, 0.01f)
        assertEquals(51f, s.resistance!!, 0.01f)
        assertEquals(22.7f, s.speedMph!!, 0.05f)
    }

    @Test fun rejectsTruncatedBikeData() {
        assertNull(IndoorBikeDataParser.parse(grupettoBikeData(84f, 232, 22.7f, 51).copyOf(7)))
    }

    @Test fun parsesHeartRate() {
        assertEquals(131, HeartRateParser.parse(byteArrayOf(0, 131.toByte())))
        assertEquals(300, HeartRateParser.parse(byteArrayOf(1, 0x2C, 0x01)))
    }

    @Test fun dirConReaderHandlesSplitFrames() {
        val payload = ByteBuffer.allocate(16 + 10)
            .putLong(DirCon.IndoorBikeData.mostSignificantBits)
            .putLong(DirCon.IndoorBikeData.leastSignificantBits)
            .put(grupettoBikeData(90f, 200, 20f, 40))
            .array()
        val frame = byteArrayOf(1, 0x06, 0, 0, 0, payload.size.toByte()) + payload
        val two = frame + frame
        val reader = DirCon.Reader()
        val first = reader.feed(two.copyOfRange(0, 20), 20)
        val rest = reader.feed(two.copyOfRange(20, two.size), two.size - 20)
        assertEquals(0, first.size)
        assertEquals(2, rest.size)
        assertEquals(DirCon.IndoorBikeData, rest[1].uuid)
        assertEquals(200f, IndoorBikeDataParser.parse(rest[1].data)!!.power!!, 0.01f)
    }

    @Test fun enableNotificationsFrame() {
        val f = DirCon.enableNotifications(DirCon.IndoorBikeData, 3)
        assertEquals(6 + 17, f.size)
        assertEquals(0x05, f[1].toInt())
        assertEquals(17, f[5].toInt())
        assertEquals(1, f.last().toInt())
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
        val saved = a.checkpoint(sec * 1000L)!!

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
        assertEquals(false, RideTracker().resume("not json", 60_000L))
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
            compactMetrics = listOf(CompactMetric.Output, CompactMetric.HeartRate),
            startOnBoot = false,
            rides = listOf(RideSummary(5L, 1800, 180, 320, 256), RideSummary(6L, 600, 120, 70, null)),
        )
        assertEquals(s, SettingsJson.decode(SettingsJson.encode(s)))
    }

    @Test fun zeroFtpInBackupIsIgnored() {
        assertNull(SettingsJson.decode("""{"ftp":0}""").ftp)
        assertNull(SettingsJson.decode("""{"ftp":-5}""").ftp)
    }

    @Test fun dirConErrorAndShortFrames() {
        val error = byteArrayOf(1, 0x05, 2, 0x04, 0, 0)           // "characteristic not found", no payload
        val short = byteArrayOf(1, 0x06, 0, 0, 0, 3, 9, 9, 9)     // payload too short to hold a UUID
        val frames = DirCon.Reader().let { it.feed(error + short, error.size + short.size) }
        assertEquals(2, frames.size)
        assertEquals(0x04, frames[0].response)
        assertNull(frames[0].uuid)
        assertNull(frames[1].uuid)
    }

    @Test fun oldOrPartialBackupUsesDefaults() {
        val s = SettingsJson.decode("""{"version":1,"ftp":200,"startMode":"Sideways"}""")
        assertEquals(200, s.ftp)
        assertEquals(SkinMode.Full, s.startMode)
        assertNotNull(s.compactMetrics.firstOrNull())
    }
}
