package com.cleardictate.inference.service

/**
 * Keeps the in-memory client generation and its coordinator registration in one serialized
 * transition. This prevents a stale Binder registration or death callback from unregistering a
 * newer endpoint that reused the same logical client identifier.
 */
internal class SerializedClientRegistrationRegistry<ClientKey : Any, Registration : Any>
{
    private val coordinationLock = Any()
    private val registrations = mutableMapOf<ClientKey, Registration>()

    /**
     * Deactivates the previous generation before publishing and activating the replacement.
     * Returning false from activation removes and deactivates the rejected replacement.
     */
    fun replace(
        clientKey: ClientKey,
        registration: Registration,
        activate: (Registration) -> Boolean,
        deactivate: (Registration) -> Unit
    ): Boolean
    {
        return synchronized(coordinationLock)
        {
            registrations.remove(clientKey)?.let(deactivate)
            registrations[clientKey] = registration

            val activationSucceeded = try
            {
                activate(registration)
            }
            catch (failure: Throwable)
            {
                registrations.remove(clientKey)
                deactivate(registration)
                throw failure
            }

            if (!activationSucceeded)
            {
                registrations.remove(clientKey)
                deactivate(registration)
            }
            activationSucceeded
        }
    }

    /**
     * Removes only the expected generation when supplied, then completes deactivation before a
     * replacement registration is permitted to begin.
     */
    fun remove(
        clientKey: ClientKey,
        expectedRegistration: ((Registration) -> Boolean)? = null,
        deactivate: (Registration) -> Unit
    ): Boolean
    {
        return synchronized(coordinationLock)
        {
            val currentRegistration = registrations[clientKey]
            if (currentRegistration == null ||
                (expectedRegistration != null && !expectedRegistration(currentRegistration)))
            {
                return@synchronized false
            }

            registrations.remove(clientKey)
            deactivate(currentRegistration)
            true
        }
    }

    fun find(clientKey: ClientKey): Registration?
    {
        return synchronized(coordinationLock)
        {
            registrations[clientKey]
        }
    }

    fun drain(): List<Registration>
    {
        return synchronized(coordinationLock)
        {
            registrations.values.toList().also {
                registrations.clear()
            }
        }
    }
}
