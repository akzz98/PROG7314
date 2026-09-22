package za.co.munipulse.ui.home

sealed interface WardPulseUi {
    data object Idle : WardPulseUi
    data object Loading : WardPulseUi
    data class Ready(val openCount: Int, val items: List<WardPulseItem>) : WardPulseUi
    data class Failed(val message: String) : WardPulseUi
}

data class WardPulseItem(
    val id: String,
    val category: String,
    val place: String,
    val upvoteCount: Int,
)
