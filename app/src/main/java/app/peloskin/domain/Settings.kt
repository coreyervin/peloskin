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

data class Settings(
    val ftp: Int? = null,
    val ftpSource: FtpSource = FtpSource.Manual,
    val ftpSetAt: Long = 0L,
    val autoFtp: Boolean = true,
    val startMode: SkinMode = SkinMode.Full,
    val compactEdge: CompactEdge = CompactEdge.Bottom,
    val compactMetrics: List<CompactMetric> = DefaultCompactMetrics,
    val startOnBoot: Boolean = true,
    val units: Units = Units.Imperial,
    val rides: List<RideSummary> = emptyList(),
) {
    companion object {
        val DefaultCompactMetrics = listOf(
            CompactMetric.Cadence, CompactMetric.Output, CompactMetric.Resistance,
            CompactMetric.Speed, CompactMetric.TotalOutput,
        )
        const val MaxRides = 100
    }
}
