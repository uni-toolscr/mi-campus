package cr.micampus.app.data.banner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicProgressStoreVersionTest {
    @Test fun onlyVersionTwoPayloadsAreAccepted() {
        assertFalse(hasSupportedAcademicProgressVersion(ByteArray(20).also { it[0] = 1 }))
        assertTrue(hasSupportedAcademicProgressVersion(ByteArray(20).also { it[0] = 2 }))
        assertFalse(hasSupportedAcademicProgressVersion(byteArrayOf(2)))
    }
}
