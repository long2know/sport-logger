package com.long2know.sportlogger.phone

import android.content.Context
import com.long2know.sportlogger.export.CanonicalTcxExportSource
import com.long2know.sportlogger.persistence.phone.LegacyPhoneInboxBackfill
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import java.io.File
import java.util.concurrent.Executor

class CanonicalPhoneDataSource private constructor(
    val repository: PhoneActivityRepository,
    private val applicationContext: Context?,
    private val inboxDirectory: File?,
    private val ownsRepository: Boolean,
) : AutoCloseable {
    fun summaries(offset: Int, limit: Int): List<PhoneActivityRepository.ActivitySummary> =
        repository.summaries(offset, limit)

    fun detail(
        sessionId: String,
        revision: Long,
    ): PhoneActivityRepository.ActivityDetail? = repository.detail(sessionId, revision)

    fun updateMetadata(
        sessionId: String,
        title: String?,
        notes: String?,
        tags: List<String>,
        updatedAtUtcMs: Long,
    ) {
        repository.setMetadata(sessionId, title, notes, tags, updatedAtUtcMs)
    }

    fun backfill(nowUtcMs: Long): LegacyPhoneInboxBackfill.BackfillResult {
        val backfill = LegacyPhoneInboxBackfill(repository)
        return if (applicationContext != null) {
            backfill.backfillDefault(applicationContext, nowUtcMs)
        } else {
            backfill.backfill(checkNotNull(inboxDirectory), nowUtcMs)
        }
    }

    fun observeChanges(
        executor: Executor,
        listener: Runnable,
    ): AutoCloseable = repository.observeChanges(executor, listener)

    fun exportSource(
        detail: PhoneActivityRepository.ActivityDetail,
    ): CanonicalTcxExportSource = CanonicalTcxExportSource(repository, detail)

    fun summaryCount(): Long = repository.summaryCount()

    override fun close() {
        if (ownsRepository) {
            repository.close()
        }
    }

    companion object {
        fun open(context: Context): CanonicalPhoneDataSource =
            CanonicalPhoneDataSource(
                repository = PhoneActivityRepository.open(context),
                applicationContext = context.applicationContext,
                inboxDirectory = null,
                ownsRepository = true,
            )

        fun create(
            repository: PhoneActivityRepository,
            inboxDirectory: File,
        ): CanonicalPhoneDataSource =
            CanonicalPhoneDataSource(
                repository = repository,
                applicationContext = null,
                inboxDirectory = inboxDirectory,
                ownsRepository = false,
            )
    }
}
