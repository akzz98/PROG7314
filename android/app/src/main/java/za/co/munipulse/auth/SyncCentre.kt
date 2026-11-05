package za.co.munipulse.auth

data class QueuedReportRow(
    val id: String,
    val category: String,
    val status: String,
    val retryCount: Int,
    val errorCode: String?,
)

sealed interface SyncCentreUi {
    data object Loading : SyncCentreUi
    data class Ready(
        val lastSyncAt: Long?,
        val items: List<QueuedReportRow>,
        val note: String,
        val busy: Boolean,
    ) : SyncCentreUi
    data class Failed(val message: String) : SyncCentreUi
}
