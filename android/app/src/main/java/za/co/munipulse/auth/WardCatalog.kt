package za.co.munipulse.auth

data class WardOption(val code: String, val municipality: String, val name: String)

// Same demo wards as the API WardCatalog.
object WardCatalog {
    const val DEFAULT = "JHB-23"

    val all: List<WardOption> = listOf(
        WardOption("JHB-23", "Johannesburg", "Ward 23"),
        WardOption("JHB-24", "Johannesburg", "Ward 24"),
        WardOption("CPT-11", "Cape Town", "Ward 11"),
        WardOption("DBN-07", "eThekwini", "Ward 07"),
        WardOption("TSH-04", "Tshwane", "Ward 04"),
    )

    fun isKnown(code: String): Boolean = all.any { it.code == code }

    fun normalize(code: String?): String = if (code != null && isKnown(code)) code else DEFAULT
}
