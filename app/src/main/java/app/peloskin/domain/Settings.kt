package app.peloskin.domain

enum class SkinMode { Full, Compact }
enum class CompactEdge { Top, Bottom }

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
