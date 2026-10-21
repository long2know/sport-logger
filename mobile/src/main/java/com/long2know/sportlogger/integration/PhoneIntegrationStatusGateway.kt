package com.long2know.sportlogger.integration

interface PhoneIntegrationStatusGateway {
    fun snapshot(): IntegrationSnapshot

    fun observe(onChanged: () -> Unit): AutoCloseable

    fun perform(action: IntegrationAction): IntegrationActionResult
}

data class IntegrationSnapshot(
    val healthConnect: ConnectorUiStatus,
    val endurain: ConnectorUiStatus,
)

data class ConnectorUiStatus(
    val id: String,
    val title: String,
    val state: ConnectorUiState,
    val detail: String,
    val actions: List<IntegrationAction>,
)

enum class ConnectorUiState {
    AVAILABLE,
    NOT_CONFIGURED,
    PERMISSION_REQUIRED,
    OFFLINE,
    RETRYABLE_ERROR,
    WORKING,
    CONNECTED,
    PARTIAL,
    UNSUPPORTED,
}

data class IntegrationAction(
    val id: String,
    val label: String,
)

sealed interface IntegrationActionResult {
    data class Completed(
        val message: String,
    ) : IntegrationActionResult

    data class Failed(
        val message: String,
    ) : IntegrationActionResult
}

class UnboundPhoneIntegrationStatusGateway : PhoneIntegrationStatusGateway {
    override fun snapshot(): IntegrationSnapshot =
        IntegrationSnapshot(
            healthConnect = ConnectorUiStatus(
                id = "health-connect",
                title = "Health Connect",
                state = ConnectorUiState.NOT_CONFIGURED,
                detail = "Health Connect support is not available in this build.",
                actions = emptyList(),
            ),
            endurain = ConnectorUiStatus(
                id = "endurain",
                title = "Endurain",
                state = ConnectorUiState.NOT_CONFIGURED,
                detail = "No Endurain connection is configured.",
                actions = emptyList(),
            ),
        )

    override fun observe(onChanged: () -> Unit): AutoCloseable = AutoCloseable {}

    override fun perform(action: IntegrationAction): IntegrationActionResult =
        IntegrationActionResult.Failed("This integration action is unavailable.")
}
