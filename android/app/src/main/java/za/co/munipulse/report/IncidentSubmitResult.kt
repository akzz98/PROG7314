package za.co.munipulse.report

sealed interface IncidentSubmitResult {
    data class Created(val incidentId: String) : IncidentSubmitResult
    data object AlreadySubmitted : IncidentSubmitResult
    data object Queued : IncidentSubmitResult
    data class Failed(val message: String) : IncidentSubmitResult
}
