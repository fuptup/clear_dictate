package com.cleardictate.inference.service

/**
 * Remembers a user terminal action that can arrive before Android dispatches a foreground-service
 * start. Cancellation always dominates Stop, and storage is bounded per service process.
 */
class PendingOperationTerminalRegistry(
    private val maximumRetainedActions: Int = 32
)
{
    private val registryLock = Any()
    private val pendingActions = LinkedHashMap<OperationKey, PendingOperationTerminalAction>()

    init
    {
        require(maximumRetainedActions > 0) {
            "At least one pending terminal action must be retainable."
        }
    }

    fun recordCancel(clientIdentifier: String, operationIdentifier: String)
    {
        record(
            OperationKey(clientIdentifier, operationIdentifier),
            PendingOperationTerminalAction.CANCEL
        )
    }

    fun recordStop(clientIdentifier: String, operationIdentifier: String)
    {
        val operationKey = OperationKey(clientIdentifier, operationIdentifier)

        synchronized(registryLock)
        {
            if (pendingActions[operationKey] != PendingOperationTerminalAction.CANCEL)
            {
                pendingActions[operationKey] = PendingOperationTerminalAction.STOP
                trimOldestActions()
            }
        }
    }

    fun consume(clientIdentifier: String, operationIdentifier: String): PendingOperationTerminalAction?
    {
        return synchronized(registryLock)
        {
            pendingActions.remove(OperationKey(clientIdentifier, operationIdentifier))
        }
    }

    fun clearClient(clientIdentifier: String)
    {
        synchronized(registryLock)
        {
            val matchingKeys = pendingActions.keys.filter { operationKey ->
                operationKey.clientIdentifier == clientIdentifier
            }
            matchingKeys.forEach(pendingActions::remove)
        }
    }

    private fun record(operationKey: OperationKey, action: PendingOperationTerminalAction)
    {
        synchronized(registryLock)
        {
            pendingActions[operationKey] = action
            trimOldestActions()
        }
    }

    private fun trimOldestActions()
    {
        while (pendingActions.size > maximumRetainedActions)
        {
            val oldestKey = pendingActions.keys.first()
            pendingActions.remove(oldestKey)
        }
    }

    private data class OperationKey(
        val clientIdentifier: String,
        val operationIdentifier: String
    )
}

enum class PendingOperationTerminalAction
{
    STOP,
    CANCEL
}
