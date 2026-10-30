package app.peloskin.domain

enum class SkinMode { Full, Compact }
enum class CompactEdge { Top, Bottom }

/** How speed and distance are shown. Ride math always works in miles internally. */
enum class Units(val speedLabel: String, val distanceLabel: String) {
    Imperial("mph", "mi"),
    Metric("km/h", "km");

    fun speed(mph: Float): Float = if (this == Metric) mph * KmPerMile else mph
    fun distance(miles: Float): Float = if (this == Metric) miles * KmPerMile else miles

    private companion object {
        const val KmPerMile = 1.609344f
    }
}

enum class CompactMetric(val label: String) {
    Cadence("Cadence"),
    Output("Output"),
    Resistance("Resistance"),
    Speed("Speed"),
    Distance("Distance"),
    TotalOutput("Total"),
    Calories("Calories"),
}

/** Everything PeloSkin remembers. Riders have their own FTP and history; layout is shared. */
data class Settings(
    val profiles: List<Profile> = listOf(Profile.first()),
    val activeProfileId: String = Profile.FirstId,
    val startMode: SkinMode = SkinMode.Full,
    val compactEdge: CompactEdge = CompactEdge.Bottom,
    val compactMetrics: List<CompactMetric> = DefaultCompactMetrics,
    val startOnBoot: Boolean = true,
    val units: Units = Units.Imperial,
) {
    /** The rider on the bike now. Falls back to the first rider if the id is stale. */
    val active: Profile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    /** Changes the active rider. */
    fun withActive(change: (Profile) -> Profile): Settings {
        val id = active.id
        return copy(profiles = profiles.map { if (it.id == id) change(it) else it })
    }

    companion object {
        val DefaultCompactMetrics = listOf(
            CompactMetric.Cadence, CompactMetric.Output, CompactMetric.Resistance,
            CompactMetric.Speed, CompactMetric.TotalOutput,
        )
        const val MaxRides = 100
    }
}
