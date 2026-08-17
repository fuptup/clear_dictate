package com.cleardictate.android.accessibility

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Verifies that repeated Android connection callbacks cannot allocate duplicate service-owned resources.
 */
class AccessibilityServiceConnectionOwnerTest
{
    @Test
    fun `repeated connection callbacks initialize one owned lifecycle`()
    {
        val owner = AccessibilityServiceConnectionOwner()
        var initializationCount = 0

        owner.initialize { initializationCount += 1 }
        owner.initialize { initializationCount += 1 }

        assertEquals(1, initializationCount)
    }
}
