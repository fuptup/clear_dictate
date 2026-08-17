package com.cleardictate.inference.service

/**
 * Owns the single operation identifier that an Android inference client may have in flight.
 */
internal class InferenceClientOperationOwnership
{
    private val ownershipLock = Any()
    private var activeOperationIdentifier: String? = null

    fun hasActiveOperation(): Boolean
    {
        return synchronized(ownershipLock) { activeOperationIdentifier != null }
    }

    fun tryActivate(operationIdentifier: String): Boolean
    {
        return synchronized(ownershipLock)
        {
            if (activeOperationIdentifier != null)
            {
                false
            }
            else
            {
                activeOperationIdentifier = operationIdentifier
                true
            }
        }
    }

    fun activeOperationIdentifier(): String?
    {
        return synchronized(ownershipLock) { activeOperationIdentifier }
    }

    fun isActive(operationIdentifier: String): Boolean
    {
        return synchronized(ownershipLock) { activeOperationIdentifier == operationIdentifier }
    }

    fun cancelActiveOperation(): String?
    {
        return synchronized(ownershipLock)
        {
            val currentOperationIdentifier = activeOperationIdentifier ?: return@synchronized null
            activeOperationIdentifier = null
            currentOperationIdentifier
        }
    }

    fun completeSuccessfulOperation(operationIdentifier: String): Boolean
    {
        return synchronized(ownershipLock)
        {
            if (activeOperationIdentifier != operationIdentifier)
            {
                false
            }
            else
            {
                activeOperationIdentifier = null
                true
            }
        }
    }

    fun completeCancellation(operationIdentifier: String): Boolean
    {
        return synchronized(ownershipLock)
        {
            val operationWasActive = activeOperationIdentifier == operationIdentifier
            if (operationWasActive)
            {
                activeOperationIdentifier = null
            }
            operationWasActive
        }
    }

    fun clear()
    {
        synchronized(ownershipLock)
        {
            activeOperationIdentifier = null
        }
    }

    internal fun retainedOperationCount(): Int
    {
        return synchronized(ownershipLock) { if (activeOperationIdentifier == null) 0 else 1 }
    }
}
