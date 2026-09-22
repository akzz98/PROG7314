package za.co.munipulse.ui.report

sealed interface NearbyUi {
    data object Idle : NearbyUi
    data object Loading : NearbyUi
    data class Ready(val items: List<NearbyDuplicate>) : NearbyUi
    data class Failed(val message: String) : NearbyUi
}

data class NearbyDuplicate(
    val id: String,
    val category: String,
    val place: String,
    val upvoteCount: Int,
    val distanceMeters: Int,
)
