package com.cleardictate.desktop.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies collision-free worker request identity within one process epoch.
 */
class WorkerRequestTokenAllocatorTest
{
    @Test
    fun `allocates positive monotonically increasing tokens`()
    {
        val allocator = WorkerRequestTokenAllocator()

        assertEquals(WorkerRequestToken(1), allocator.allocate())
        assertEquals(WorkerRequestToken(2), allocator.allocate())
    }

    @Test
    fun `requires worker restart rather than wrapping the token counter`()
    {
        val allocator = WorkerRequestTokenAllocator(nextTokenValue = Long.MAX_VALUE)

        assertEquals(WorkerRequestToken(Long.MAX_VALUE), allocator.allocate())
        assertFailsWith<WorkerRequestTokenExhaustedException> {
            allocator.allocate()
        }
    }

    @Test
    fun `worker token rejects zero and negative values`()
    {
        assertFailsWith<IllegalArgumentException> {
            WorkerRequestToken(0)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkerRequestToken(-1)
        }
    }
}
