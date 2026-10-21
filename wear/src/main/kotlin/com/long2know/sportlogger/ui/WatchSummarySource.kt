package com.long2know.sportlogger.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.long2know.sportlogger.persistence.watch.WatchActivityRepository
import java.util.concurrent.atomic.AtomicBoolean

class WatchSummarySource(
    context: Context,
) : AutoCloseable {
    private val repository = WatchActivityRepository.open(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private var observer: AutoCloseable? = null
    private var listener: ((WorkoutSummaryUi?) -> Unit)? = null
    private var errorListener: ((Throwable) -> Unit)? = null

    fun start(
        listener: (WorkoutSummaryUi?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (closed.get()) {
            return
        }
        this.listener = listener
        errorListener = onError
        if (observer == null) {
            observer = repository.observeChanges(
                { command -> mainHandler.post(command) },
                ::refreshLatest,
            )
        }
        refreshLatest()
    }

    fun refreshLatest() {
        if (closed.get()) {
            return
        }
        repository.latestCompleted().whenComplete { completed, error ->
            if (closed.get()) {
                return@whenComplete
            }
            if (error != null) {
                publishError(error)
                return@whenComplete
            }
            if (completed == null) {
                publish(null)
                return@whenComplete
            }
            runCatching {
                CanonicalSummaryMapper.fromCompleted(completed)
            }.onSuccess(::publish).onFailure(::publishError)
        }
    }

    private fun publish(summary: WorkoutSummaryUi?) {
        mainHandler.post {
            if (!closed.get()) {
                listener?.invoke(summary)
            }
        }
    }

    private fun publishError(error: Throwable) {
        mainHandler.post {
            if (!closed.get()) {
                errorListener?.invoke(error)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        observer?.close()
        observer = null
        listener = null
        errorListener = null
        repository.close()
    }
}
