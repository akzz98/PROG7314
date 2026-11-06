package za.co.munipulse.data

import java.io.IOException

// A failed socket, DNS lookup, or timeout means the report never left the phone.
object TransportFailure {
    fun isOffline(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
