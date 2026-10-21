package com.long2know.sportlogger.phone

import com.long2know.sportlogger.export.CanonicalTcxExportSource
import com.long2know.sportlogger.integration.IntegrationSnapshot
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.utilities.contract.proto.MeasurementStatus
import java.io.File

enum class PhoneDestination {
    HISTORY,
    TRENDS,
    STATUS,
}

data class ActivityKey(
    val sessionId: String,
    val revision: Long,
)

data class ActivityDetailUi(
    val detail: PhoneActivityRepository.ActivityDetail,
    val exportSource: CanonicalTcxExportSource,
    val availability: List<MeasurementAvailabilityUi>,
    val exportWarnings: List<String>,
)

data class MeasurementAvailabilityUi(
    val label: String,
    val status: MeasurementStatus,
)

data class MetadataEditorUi(
    val title: String,
    val notes: String,
    val saving: Boolean = false,
    val error: String? = null,
)

data class WeeklyTrendUi(
    val weekStartUtcMs: Long,
    val activityCount: Int,
    val durationMs: Long,
    val distanceM: Double,
)

data class ActivityTypeTrendUi(
    val activityType: Int,
    val count: Int,
)

data class TrendsUi(
    val activityCount: Int,
    val totalDurationMs: Long,
    val totalDistanceM: Double,
    val weekly: List<WeeklyTrendUi>,
    val byType: List<ActivityTypeTrendUi>,
    val capped: Boolean,
)

data class BackfillUi(
    val running: Boolean = false,
    val sourceFiles: Int = 0,
    val imported: Int = 0,
    val alreadyProcessed: Int = 0,
    val partialOrFailed: Int = 0,
    val error: String? = null,
)

data class StatusUi(
    val canonicalActivityCount: Long = 0,
    val countCapped: Boolean = false,
    val committedCount: Int = 0,
    val partialImportCount: Int = 0,
    val availability: List<MeasurementAvailabilityUi> = emptyList(),
    val integrations: IntegrationSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface PhoneUiEvent {
    val id: Long

    data class CreateDocument(
        override val id: Long,
        val suggestedFileName: String,
    ) : PhoneUiEvent

    data class ShareFile(
        override val id: Long,
        val file: File,
        val fileName: String,
    ) : PhoneUiEvent
}

data class PhoneUiState(
    val destination: PhoneDestination = PhoneDestination.HISTORY,
    val history: List<PhoneActivityRepository.ActivitySummary> = emptyList(),
    val historyLoading: Boolean = true,
    val historyRefreshing: Boolean = false,
    val historyHasMore: Boolean = false,
    val historyError: String? = null,
    val selectedKey: ActivityKey? = null,
    val detail: ActivityDetailUi? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val editor: MetadataEditorUi? = null,
    val trends: TrendsUi? = null,
    val trendsLoading: Boolean = false,
    val trendsError: String? = null,
    val status: StatusUi = StatusUi(),
    val backfill: BackfillUi = BackfillUi(),
    val exportBusy: Boolean = false,
    val notice: String? = null,
    val event: PhoneUiEvent? = null,
)
