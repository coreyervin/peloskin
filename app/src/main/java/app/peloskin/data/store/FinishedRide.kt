package app.peloskin.data.store

import app.peloskin.domain.NewRecord
import app.peloskin.domain.RideDetail
import app.peloskin.domain.RideSummary

/** A ride that just ended, with what the summary card needs to show. */
data class FinishedRide(
    val profileId: String,
    val profileName: String,
    val ride: RideSummary,
    val detail: RideDetail?,
    /** The rider's last ride of the same length, for comparison. */
    val previous: RideSummary?,
    val newRecords: List<NewRecord>,
    val newFtp: Int?,
    /** When the ride ended, by the wall clock. */
    val finishedAt: Long = System.currentTimeMillis(),
)
