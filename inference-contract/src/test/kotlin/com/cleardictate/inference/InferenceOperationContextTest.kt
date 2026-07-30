package com.cleardictate.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies mandatory operation identity and privacy that can only become more restrictive.
 */
class InferenceOperationContextTest
{
    @Test
    fun `blank identifiers cannot create an operation context`()
    {
        assertFailsWith<IllegalArgumentException> { ClientSessionIdentifier("") }
        assertFailsWith<IllegalArgumentException> { OperationIdentifier("   ") }
        assertFailsWith<IllegalArgumentException> { ClientSessionIdentifier("dictated transcript contents") }
        assertFailsWith<IllegalArgumentException> { OperationIdentifier("operation/with/path") }
    }

    @Test
    fun `private classification is monotonic`()
    {
        assertEquals(
            OperationPrivacy.PRIVATE,
            OperationPrivacy.STANDARD.restrictWith(OperationPrivacy.PRIVATE)
        )
        assertEquals(
            OperationPrivacy.PRIVATE,
            OperationPrivacy.PRIVATE.restrictWith(OperationPrivacy.STANDARD)
        )
    }
}
