package app.peloskin

import app.peloskin.data.bike.BikeFeed
import app.peloskin.data.bike.FeedStatus
import app.peloskin.data.bike.SensorSource
import app.peloskin.data.store.CheckpointJson
import app.peloskin.data.store.SettingsJson
import app.peloskin.data.store.SettingsMerge
import app.peloskin.domain.BikeMath
import app.peloskin.domain.BikeSample
import app.peloskin.domain.CompactEdge
import app.peloskin.domain.CompactMetric
import app.peloskin.domain.Ftp
import app.peloskin.domain.FtpSource
import app.peloskin.domain.NewRecord
import app.peloskin.domain.PersonalRecords
import app.peloskin.domain.PowerZones
import app.peloskin.domain.Profile
import app.peloskin.domain.Record
import app.peloskin.domain.Records
import app.peloskin.domain.ResistanceFilter
import app.peloskin.domain.RideDetail
import app.peloskin.domain.RideSummary
import app.peloskin.domain.RideTracker
import app.peloskin.domain.Settings
import app.peloskin.domain.SkinMode
import app.peloskin.domain.Units
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeloSkinTest {
    private val Boot = 7


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
        val t = RideTracker(onRideFinished = { ride, _ -> finished += ride })
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
        val t = RideTracker(onRideFinished = { ride, _ -> finished += ride })
        var sec = 0
        repeat(2) { t.onSample(sec++ * 1000L, BikeSample(50f, 30f, 20f, 5f)) }
        repeat(400) { t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 20f, 0f)) }
        assertEquals(false, t.snapshot.riding)
        assertTrue(finished.isEmpty())
    }

    @Test fun rideEndsFromTickWhenFeedStops() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { ride, _ -> finished += ride })
        for (i in 0..600) t.onSample(i * 1000L, BikeSample(150f, 85f, 35f, 18f))
        t.tick(600_000L + 50_000L)
        assertTrue(t.snapshot.riding)
        t.tick(600_000L + 60_000L)
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
        val saved = CheckpointJson.decode(CheckpointJson.encode(a.checkpoint(sec * 1000L, Boot)!!))!!

        val b = RideTracker()
        assertTrue(b.resume(saved, sec * 1000L + 5_000, Boot))
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
        val saved = a.checkpoint(60_000L, Boot)!!
        // Saved "in the future" means the tablet rebooted since.
        assertEquals(false, RideTracker().resume(saved, 30_000L, Boot))
        // Saved in an earlier boot, even if the clock happens to look fine.
        assertEquals(false, RideTracker().resume(saved, 70_000L, Boot + 1))
        // Too long since the last pedal stroke.
        assertEquals(false, RideTracker().resume(saved, 60_000L + 6 * 60_000L, Boot))
        assertNull(CheckpointJson.decode("not json"))
        assertNull(RideTracker().checkpoint(0L, Boot))
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

    private val fullRide = RideSummary(
        startedAt = 5L, durationSec = 1800, avgPower = 180, totalKj = 320, best20Watts = 256,
        avgCadence = 88, avgResistance = 41, distanceMi = 9.5f, calories = 348,
        bestEfforts = mapOf(5 to 610, 60 to 330, 300 to 270, 1200 to 256),
        zoneSeconds = listOf(120, 300, 600, 500, 200, 60, 20), ftp = 240,
    )

    @Test fun settingsSurviveJsonRoundTrip() {
        val corey = Profile(
            "rider-1", "Corey", ftp = 243, ftpSource = FtpSource.Ride, ftpSetAt = 1234L, autoFtp = false,
            rides = listOf(fullRide, RideSummary(6L, 600, 120, 70, null)),
            records = Records(
                efforts = mapOf(5 to Record(610, 5L), 1200 to Record(256, 5L)),
                byLength = mapOf(30 to Record(320, 5L)),
            ),
        )
        val s = Settings(
            profiles = listOf(corey, Profile("rider-2", "Jess", ftp = 190)),
            activeProfileId = "rider-2",
            startMode = SkinMode.Compact, compactEdge = CompactEdge.Top,
            compactMetrics = listOf(CompactMetric.Output, CompactMetric.Calories),
            startOnBoot = false,
            units = Units.Metric,
        )
        assertEquals(s, SettingsJson.decode(SettingsJson.encode(s)))
        assertEquals("Jess", s.active.name)
    }

    @Test fun zeroFtpInBackupIsIgnored() {
        assertNull(SettingsJson.decode("""{"ftp":0}""").active.ftp)
        assertNull(SettingsJson.decode("""{"profiles":[{"id":"a","name":"A","ftp":-5}]}""").active.ftp)
    }

    @Test fun versionOneBackupBecomesOneRider() {
        val s = SettingsJson.decode(
            """{"version":1,"ftp":200,"ftpSource":"Ride","startMode":"Sideways",
               "rides":[{"startedAt":9,"durationSec":1830,"avgPower":170,"totalKj":310,"best20":205}]}"""
        )
        assertEquals(1, s.profiles.size)
        assertEquals(200, s.active.ftp)
        assertEquals(FtpSource.Ride, s.active.ftpSource)
        assertEquals(310, s.active.rides.single().totalKj)
        // Old rides only knew their best 20 minutes.
        assertEquals(mapOf(1200 to 205), s.active.rides.single().bestEfforts)
        assertEquals(SkinMode.Full, s.startMode)
        assertNotNull(s.compactMetrics.firstOrNull())
    }

    @Test fun staleActiveRiderFallsBackToTheFirst() {
        val s = SettingsJson.decode("""{"profiles":[{"id":"a","name":"A"}],"activeProfileId":"gone"}""")
        assertEquals("a", s.active.id)
    }

    @Test fun checkpointWithWrongRingSizeIsRejected() {
        val a = RideTracker()
        repeat(60) { a.onSample(it * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        val c = a.checkpoint(60_000L, Boot)!!
        val broken = CheckpointJson.decode(CheckpointJson.encode(c).replace(Regex("\"ring\":\\[[^]]*]"), "\"ring\":[1,2,3]"))!!
        assertEquals(false, RideTracker().resume(broken, 61_000L, Boot))
    }

    @Test fun stoppingSavesTheRide() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { ride, _ -> finished += ride })
        repeat(600) { t.onSample(it * 1000L, BikeSample(180f, 88f, 40f, 19f)) }
        t.finish()
        assertEquals(false, t.snapshot.riding)
        assertEquals(599, finished.single().durationSec)
        assertEquals(180, finished.single().avgPower)
        t.finish()
        assertEquals(1, finished.size)
    }

    @Test fun stoppingAShortSpinSavesNothing() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { ride, _ -> finished += ride })
        repeat(40) { t.onSample(it * 1000L, BikeSample(180f, 88f, 40f, 19f)) }
        t.finish()
        assertTrue(finished.isEmpty())
    }

    @Test fun staleCheckpointBecomesARide() {
        val t = RideTracker()
        repeat(1500) { t.onSample(it * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        val (summary, detail) = RideTracker.summaryOf(t.checkpoint(1_500_000L, Boot)!!, ftp = null)!!
        assertEquals(299, detail.powerEvery5s.size) // complete 5-second blocks only
        assertEquals(1499, summary.durationSec)
        assertEquals(200, summary.avgPower)
        assertEquals(200, summary.best20Watts)
        val short = RideTracker()
        repeat(30) { short.onSample(it * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        assertNull(RideTracker.summaryOf(short.checkpoint(30_000L, Boot)!!, ftp = null))
    }

    @Test fun resistanceSpikesAreFiltered() {
        val f = ResistanceFilter()
        val out = listOf(40f, 40f, 40f, 100f, 40f, 40f, 45f, 45f, 45f)
            .map { f.clean(BikeMath.sample(150f, 85f, it)).resistance }
        // The single 100 never shows; the real step to 45 arrives one reading late.
        assertEquals(listOf(40f, 40f, 40f, 40f, 40f, 40f, 40f, 45f, 45f), out)
    }

    @Test fun backupMergeKeepsEverything() {
        fun rider(id: String, ftp: Int, ftpSetAt: Long, rides: List<RideSummary>, best5: Int) = Profile(
            id, id, ftp = ftp, ftpSetAt = ftpSetAt, rides = rides,
            records = Records(efforts = mapOf(5 to Record(best5, rides.first().startedAt))),
        )
        val local = Settings(
            profiles = listOf(rider("a", 200, 100L, listOf(RideSummary(3L, 1800, 180, 320, 210), RideSummary(1L, 1200, 150, 180, null)), 600)),
            activeProfileId = "a", startMode = SkinMode.Compact,
        )
        val backup = Settings(
            profiles = listOf(
                rider("a", 230, 200L, listOf(RideSummary(2L, 2400, 190, 450, 240), RideSummary(1L, 1200, 150, 180, null)), 650),
                rider("b", 180, 50L, listOf(RideSummary(4L, 1200, 140, 170, null)), 500),
            ),
            activeProfileId = "b", startMode = SkinMode.Full,
        )
        val merged = SettingsMerge.merge(local, backup)
        val a2 = merged.profiles.first { it.id == "a" }
        assertEquals(listOf(3L, 2L, 1L), a2.rides.map { it.startedAt })
        assertEquals(230, a2.ftp)                         // backup's FTP is newer
        assertEquals(650, a2.records.efforts[5]!!.value)  // the better record wins
        assertEquals(listOf("a", "b"), merged.profiles.map { it.id }) // a rider only in the backup comes back
        assertEquals(SkinMode.Compact, merged.startMode)  // local choices win
        assertEquals("a", merged.activeProfileId)
        assertEquals(backup, SettingsMerge.merge(null, backup))
    }

    // --------------------------------------------------------------- records

    @Test fun bestEffortsAndGraphAreTracked() {
        var got: Pair<RideSummary, RideDetail>? = null
        val t = RideTracker(onRideFinished = { r, d -> got = r to d })
        var sec = 0
        // 10 min at 150 W with one hard minute at 300 W and a 5-second sprint at 600 W in it.
        repeat(300) { t.onSample(sec++ * 1000L, BikeSample(150f, 85f, 35f, 18f)) }
        repeat(55) { t.onSample(sec++ * 1000L, BikeSample(300f, 95f, 50f, 23f)) }
        repeat(5) { t.onSample(sec++ * 1000L, BikeSample(600f, 110f, 50f, 28f)) }
        repeat(240) { t.onSample(sec++ * 1000L, BikeSample(150f, 85f, 35f, 18f)) }
        // A 2-minute idle tail before stopping.
        repeat(120) { t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 35f, 0f)) }
        t.finish()
        val (ride, detail) = got!!
        assertEquals(600, ride.bestEfforts[5])
        assertEquals((55 * 300 + 5 * 600) / 60, ride.bestEfforts[60])
        assertTrue(ride.bestEfforts[300]!! in 180..200)
        assertNull(ride.bestEfforts[1200]) // the ride is only 10 minutes long
        // One point per complete 5 seconds of the 599-second ride, without the idle tail.
        assertEquals(119, detail.powerEvery5s.size)
        assertEquals(150, detail.powerEvery5s.first())
    }

    @Test fun timeInZonesSkipsTheIdleTail() {
        var got: RideSummary? = null
        val t = RideTracker(ftp = { 200 }, onRideFinished = { r, _ -> got = r })
        var sec = 0
        repeat(300) { t.onSample(sec++ * 1000L, BikeSample(200f, 90f, 40f, 20f)) } // 100% FTP: zone 4
        repeat(30) { t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 40f, 0f)) }      // a pause: zone 1
        repeat(300) { t.onSample(sec++ * 1000L, BikeSample(100f, 80f, 30f, 15f)) } // 50% FTP: zone 1
        repeat(200) { t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 30f, 0f)) }     // idle tail, dropped
        t.finish()
        val zones = got!!.zoneSeconds!!
        assertEquals(200, got!!.ftp)
        assertTrue(zones[3] in 298..300)
        assertTrue(zones[0] in 328..331)
        assertEquals(zones.sum(), zones[0] + zones[3])
    }

    @Test fun noZonesWithoutAnFtp() {
        var got: RideSummary? = null
        val t = RideTracker(onRideFinished = { r, _ -> got = r })
        repeat(400) { t.onSample(it * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        t.finish()
        assertNull(got!!.zoneSeconds)
    }

    @Test fun recordsAreSetAndBroken() {
        val first = fullRide
        val (records, broken) = PersonalRecords.apply(Records(), first)
        assertEquals(5, broken.size)               // four efforts and the 30-min ride, all firsts
        assertTrue(broken.all { it.previous == null })
        val better = first.copy(startedAt = 9L, totalKj = 340, bestEfforts = mapOf(5 to 600, 60 to 345))
        val (after, broken2) = PersonalRecords.apply(records, better)
        assertEquals(setOf(60, 30), broken2.map { if (it is NewRecord.Effort) it.seconds else (it as NewRecord.ClassLength).minutes }.toSet())
        assertEquals(330, (broken2.first { it is NewRecord.Effort } as NewRecord.Effort).previous)
        assertEquals(610, after.efforts[5]!!.value)  // not beaten, kept
        assertEquals(9L, after.byLength[30]!!.startedAt)
    }

    @Test fun ridesAreGroupedByClassLength() {
        assertEquals(30, PersonalRecords.classLength(31 * 60))
        assertEquals(45, PersonalRecords.classLength(44 * 60))
        assertEquals(20, PersonalRecords.classLength(19 * 60 + 10))
        assertNull(PersonalRecords.classLength(38 * 60)) // between a 30 and a 45
    }

    @Test fun summaryComparesWithTheLastRideOfTheSameLength() {
        val rides = listOf(
            RideSummary(30L, 2700, 170, 460, 200), // 45 min
            RideSummary(20L, 1810, 160, 290, 190), // 30 min
            RideSummary(10L, 1790, 150, 270, 180), // 30 min
        )
        val ride = RideSummary(40L, 1820, 175, 318, 210)
        assertEquals(20L, PersonalRecords.previousSimilar(rides, ride)!!.startedAt)
        assertNull(PersonalRecords.previousSimilar(rides, RideSummary(40L, 600, 150, 90, null)))
    }

    @Test fun checkpointKeepsRecordsAndGraph() {
        val a = RideTracker(ftp = { 200 })
        var sec = 0
        repeat(400) { a.onSample(sec++ * 1000L, BikeSample(210f, 90f, 40f, 20f)) }
        val saved = CheckpointJson.decode(CheckpointJson.encode(a.checkpoint(sec * 1000L, Boot)!!))!!
        var got: Pair<RideSummary, RideDetail>? = null
        val b = RideTracker(ftp = { 200 }, onRideFinished = { r, d -> got = r to d })
        assertTrue(b.resume(saved, sec * 1000L + 1_000, Boot))
        repeat(100) { b.onSample(sec++ * 1000L + 1_000, BikeSample(210f, 90f, 40f, 20f)) }
        b.finish()
        assertEquals(210, got!!.first.bestEfforts[300])
        assertTrue(got!!.second.powerEvery5s.size >= 99)
        assertTrue(got!!.first.zoneSeconds!![3] > 400)
    }

    // ------------------------------------------------------------------ feed

    private class FakeSource(
        override val name: String,
        private val startsOk: Boolean = true,
        private val onStart: ((BikeSample) -> Unit, () -> Unit) -> Unit = { _, _ -> },
    ) : SensorSource {
        var starts = 0
        var stops = 0
        override fun start(onSample: (BikeSample) -> Unit, onLost: () -> Unit): Boolean {
            starts++
            if (startsOk) onStart(onSample, onLost)
            return startsOk
        }
        override fun stop() { stops++ }
    }

    @Test fun feedReportsUnsupportedBike() = runTest {
        val feed = BikeFeed(emptyList(), log = {})
        feed.start(backgroundScope)
        runCurrent()
        assertEquals(FeedStatus.Unsupported, feed.status.value)
    }

    @Test fun feedFallsBackToTheNextSource() = runTest {
        val broken = FakeSource("service", startsOk = false)
        val working = FakeSource("app", onStart = { sample, _ -> sample(BikeMath.sample(150f, 85f, 40f)) })
        val feed = BikeFeed(listOf(broken, working), log = {})
        val got = mutableListOf<BikeSample>()
        backgroundScope.launch { feed.samples.collect { got += it } }
        feed.start(backgroundScope)
        runCurrent()
        assertEquals(FeedStatus.Connected, feed.status.value)
        assertEquals("app", feed.source.value)
        assertEquals(150f, got.single().power, 0f)
        assertEquals(1, broken.stops)
    }

    @Test fun feedReconnectsWhenTheServiceGoesAway() = runTest {
        var lose: (() -> Unit)? = null
        val source = FakeSource("service", onStart = { sample, lost -> sample(BikeMath.sample(150f, 85f, 40f)); lose = lost })
        val feed = BikeFeed(listOf(source), log = {})
        feed.start(backgroundScope)
        runCurrent()
        assertEquals(FeedStatus.Connected, feed.status.value)
        lose!!()
        runCurrent()
        assertEquals(FeedStatus.Connecting, feed.status.value)
        assertNull(feed.source.value)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, source.starts)
        assertEquals(FeedStatus.Connected, feed.status.value)
    }

    @Test fun feedGivesUpOnASilentService() = runTest {
        val silent = FakeSource("service")
        val feed = BikeFeed(listOf(silent), log = {})
        feed.start(backgroundScope)
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals(FeedStatus.NotFound, feed.status.value)
        assertEquals(1, silent.stops)
    }

    @Test fun demoRideIsMarked() = runTest {
        var demo = true
        val feed = BikeFeed(emptyList(), demo = { demo }, log = {})
        var count = 0
        backgroundScope.launch { feed.samples.collect { count++ } }
        feed.start(backgroundScope)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(feed.demoActive.value)
        assertTrue(count >= 4)
        demo = false
        advanceTimeBy(300)
        runCurrent()
        assertEquals(false, feed.demoActive.value)
        assertNull(feed.source.value)
    }

    @Test fun unitsConvertSpeedAndDistance() {
        assertEquals(20f, Units.Imperial.speed(20f), 0f)
        assertEquals(32.19f, Units.Metric.speed(20f), 0.01f)
        assertEquals(1.61f, Units.Metric.distance(1f), 0.01f)
        assertEquals("km/h", Units.Metric.speedLabel)
        // Older backups without a units field stay in miles.
        assertEquals(Units.Imperial, SettingsJson.decode("""{"version":1}""").units)
    }

    // --------------------------------------------------------- review fixes

    @Test fun aSampleSpanningSecondsIsSpreadAcrossThem() {
        var got: Pair<RideSummary, RideDetail>? = null
        val t = RideTracker(ftp = { 200 }, onRideFinished = { r, d -> got = r to d })
        var ms = 0L
        // 400 s at 200 W, with readings 2.5 s apart, as the slower fallback might send.
        while (ms <= 400_000L) { t.onSample(ms, BikeSample(200f, 90f, 40f, 20f)); ms += 2_500 }
        t.finish()
        val (ride, detail) = got!!
        // Every second is 200 W, so every second is zone 4, and nothing leaks into zone 1.
        assertEquals(ride.durationSec, ride.zoneSeconds!![3])
        assertEquals(0, ride.zoneSeconds!![0])
        assertTrue(detail.powerEvery5s.all { it == 200 })
        assertEquals(200, ride.bestEfforts[5])
    }

    @Test fun zonesUseTheFtpOfTheRiderCreditedAtTheEnd() {
        var ftp = 400
        var got: RideSummary? = null
        val t = RideTracker(ftp = { ftp }, onRideFinished = { r, _ -> got = r })
        repeat(400) { t.onSample(it * 1000L, BikeSample(200f, 90f, 40f, 20f)) }
        ftp = 200 // the rider was switched before the ride ended
        t.finish()
        assertEquals(200, got!!.ftp)
        assertTrue(got!!.zoneSeconds!![3] >= 398) // 200 W at FTP 200 is zone 4 throughout
    }

    @Test fun upgradingRebuildsRecordsFromHistory() {
        val s = SettingsJson.decode(
            """{"version":1,"rides":[
               {"startedAt":1,"durationSec":1800,"avgPower":150,"totalKj":270,"best20":190},
               {"startedAt":2,"durationSec":1810,"avgPower":160,"totalKj":290,"best20":205}]}"""
        )
        val records = s.active.records
        assertEquals(205, records.efforts[1200]!!.value)
        assertEquals(2L, records.efforts[1200]!!.startedAt)
        assertEquals(290, records.byLength[30]!!.value)
    }

    @Test fun oldCheckpointKeepsItsBest20() {
        val old = """{"bootId":7,"savedAt":1000,"startedAt":5,"lastPedalAt":1000,"elapsed":1300,
            "elapsedAtLastPedal":1300,"moving":1300,"powerSum":260000,"cadenceSum":117000,"resistanceSum":52000,
            "energyJ":260000,"distanceMi":7,"bestPower":250,"bestCadence":95,"bestResistance":45,"seconds":1301,
            "best20":205,"ring":[0]}"""
        val c = CheckpointJson.decode(old)!!
        assertEquals(205.0, c.efforts[PersonalRecords.EffortWindows.indexOf(1200)], 0.0)
        assertEquals(205, RideTracker.summaryOf(c, ftp = null)!!.first.best20Watts)
    }

    @Test fun anUnusedDefaultRiderTakesTheBackupsName() {
        val backup = Settings(profiles = listOf(Profile(Profile.FirstId, "Corey", ftp = 230, ftpSetAt = 5L,
            rides = listOf(RideSummary(3L, 1800, 180, 320, 210)))))
        val local = Settings(startMode = SkinMode.Compact) // a fresh install, layout changed once
        val merged = SettingsMerge.merge(local, backup)
        assertEquals("Corey", merged.active.name)
        assertEquals(230, merged.active.ftp)
        assertEquals(SkinMode.Compact, merged.startMode)
    }

    @Test fun bannerOnlyAnnouncesBeatenRecords() {
        assertNull(PersonalRecords.banner(null, listOf(NewRecord.Effort(5, 600, previous = null))))
        assertEquals("New FTP 230", PersonalRecords.banner(230, emptyList()))
        assertEquals(
            "New FTP 230  ·  New PR · 5 min 270 W · 30-min ride 320 kJ",
            PersonalRecords.banner(230, listOf(
                NewRecord.Effort(300, 270, previous = 250),
                NewRecord.ClassLength(30, 320, previous = 300),
                NewRecord.Effort(5, 610, previous = null),
            )),
        )
    }

    @Test fun riderInitials() {
        assertEquals("C", Profile("a", "Corey").initials)
        assertEquals("JS", Profile("a", "Jess  Smith").initials)
        assertEquals("MA", Profile("a", "mary ann lee").initials)
        assertEquals("?", Profile("a", "   ").initials)
    }

    @Test fun endingARideWaitsForYouToStopBeforeTheNextOne() {
        val finished = mutableListOf<RideSummary>()
        val t = RideTracker(onRideFinished = { r, _ -> finished += r })
        var sec = 0
        repeat(400) { t.onSample(sec++ * 1000L, BikeSample(180f, 88f, 40f, 19f)) }
        t.finish(byRider = true)
        assertEquals(1, finished.size)
        // Still spinning in the cool-down: no new ride yet.
        repeat(60) { t.onSample(sec++ * 1000L, BikeSample(80f, 70f, 25f, 12f)) }
        assertEquals(false, t.snapshot.riding)
        assertEquals(80f, t.snapshot.power, 0f) // live numbers still show
        // Stop, then pedal again: that's a new ride.
        t.onSample(sec++ * 1000L, BikeSample(0f, 0f, 25f, 0f))
        t.onSample(sec++ * 1000L, BikeSample(150f, 85f, 30f, 17f))
        assertTrue(t.snapshot.riding)
    }

    @Test fun onlyRidesWithAMinuteOfPedalingAreLongEnough() {
        val t = RideTracker()
        repeat(40) { t.onSample(it * 1000L, BikeSample(150f, 85f, 30f, 17f)) }
        assertEquals(false, t.longEnough)
        repeat(40) { t.onSample((40 + it) * 1000L, BikeSample(150f, 85f, 30f, 17f)) }
        assertTrue(t.longEnough)
    }
}
