package za.co.munipulse.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class TransportFailureTest {
    @Test
    fun treatsANetworkExceptionAsOffline() {
        assertTrue(TransportFailure.isOffline(UnknownHostException("api")))
        assertTrue(TransportFailure.isOffline(IllegalStateException("wrapped", IOException("down"))))
    }

    @Test
    fun keepsAnApiRejectionOnline() {
        assertFalse(TransportFailure.isOffline(IllegalStateException("HTTP_400")))
    }
}
