package com.cleardictate.inference.service

import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves that coordinator-facing registration transitions cannot cross between generations.
 */
class SerializedClientRegistrationRegistryTest
{
    @Test
    fun `replacement cannot activate until the previous generation transition completes`()
    {
        val registry = SerializedClientRegistrationRegistry<String, String>()
        val lifecycleEvents = Collections.synchronizedList(mutableListOf<String>())
        val firstActivationEntered = CountDownLatch(1)
        val firstActivationMayFinish = CountDownLatch(1)
        val secondActivationEntered = CountDownLatch(1)
        val workerPool = Executors.newFixedThreadPool(2)

        try
        {
            val firstRegistration = workerPool.submit<Boolean> {
                registry.replace(
                    clientKey = "keyboard",
                    registration = "generation-one",
                    activate = {
                        lifecycleEvents += "activate-$it"
                        firstActivationEntered.countDown()
                        firstActivationMayFinish.await()
                        true
                    },
                    deactivate = { lifecycleEvents += "deactivate-$it" }
                )
            }
            assertTrue(firstActivationEntered.await(1, TimeUnit.SECONDS))

            val secondRegistration = workerPool.submit<Boolean> {
                registry.replace(
                    clientKey = "keyboard",
                    registration = "generation-two",
                    activate = {
                        lifecycleEvents += "activate-$it"
                        secondActivationEntered.countDown()
                        true
                    },
                    deactivate = { lifecycleEvents += "deactivate-$it" }
                )
            }

            assertFalse(secondActivationEntered.await(100, TimeUnit.MILLISECONDS))
            firstActivationMayFinish.countDown()

            assertTrue(firstRegistration.get(1, TimeUnit.SECONDS))
            assertTrue(secondRegistration.get(1, TimeUnit.SECONDS))
            assertEquals("generation-two", registry.find("keyboard"))
            assertEquals(
                listOf(
                    "activate-generation-one",
                    "deactivate-generation-one",
                    "activate-generation-two"
                ),
                lifecycleEvents
            )
        }
        finally
        {
            firstActivationMayFinish.countDown()
            workerPool.shutdownNow()
        }
    }

    @Test
    fun `replacement cannot publish while an old death cleanup is deactivating`()
    {
        val registry = SerializedClientRegistrationRegistry<String, String>()
        val oldDeactivationEntered = CountDownLatch(1)
        val oldDeactivationMayFinish = CountDownLatch(1)
        val replacementActivationEntered = CountDownLatch(1)
        val workerPool = Executors.newFixedThreadPool(2)
        registry.replace("keyboard", "generation-one", activate = { true }, deactivate = {})

        try
        {
            val oldDeathCleanup = workerPool.submit<Boolean> {
                registry.remove(
                    clientKey = "keyboard",
                    expectedRegistration = { it == "generation-one" },
                    deactivate = {
                        oldDeactivationEntered.countDown()
                        oldDeactivationMayFinish.await()
                    }
                )
            }
            assertTrue(oldDeactivationEntered.await(1, TimeUnit.SECONDS))

            val replacementRegistration = workerPool.submit<Boolean> {
                registry.replace(
                    clientKey = "keyboard",
                    registration = "generation-two",
                    activate = {
                        replacementActivationEntered.countDown()
                        true
                    },
                    deactivate = {}
                )
            }

            assertFalse(replacementActivationEntered.await(100, TimeUnit.MILLISECONDS))
            oldDeactivationMayFinish.countDown()

            assertTrue(oldDeathCleanup.get(1, TimeUnit.SECONDS))
            assertTrue(replacementRegistration.get(1, TimeUnit.SECONDS))
            assertEquals("generation-two", registry.find("keyboard"))
        }
        finally
        {
            oldDeactivationMayFinish.countDown()
            workerPool.shutdownNow()
        }
    }
}
