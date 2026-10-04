package nl.vanvrouwerff.iptv.data.reminders

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.db.ChannelDao
import nl.vanvrouwerff.iptv.data.db.ReminderEntity
import nl.vanvrouwerff.iptv.data.repo.runCatchingCancellable

/**
 * Programme reminders: stored in Room, checked while the app runs, and surfaced as a prompt
 * [LEAD_MS] before the programme starts.
 */
class Reminders(
    private val dao: ChannelDao,
    private val scope: CoroutineScope,
) {

    private val _due = MutableStateFlow<ReminderEntity?>(null)
    /** The reminder to show now, or null. Cleared by [dismiss]. */
    val due: StateFlow<ReminderEntity?> = _due.asStateFlow()

    val all = dao.observeReminders()

    fun start() {
        scope.launch {
            while (true) {
                runCatchingCancellable { check(System.currentTimeMillis()) }
                    .onFailure { Log.w("Reminders", "Could not check reminders", it) }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun check(now: Long) {
        dao.deleteRemindersBefore(now - STALE_AFTER_MS)
        if (_due.value != null) return
        _due.value = dao.nextDueReminder(dueBy = now + LEAD_MS, notBefore = now - STALE_AFTER_MS)
    }

    /** Sets a reminder, or removes it when one already exists. Returns true when it is now set. */
    suspend fun toggle(channel: Channel, title: String, startMs: Long, stopMs: Long, existing: Boolean): Boolean {
        return if (existing) {
            dao.deleteReminder(channel.id, startMs)
            false
        } else {
            dao.insertReminder(ReminderEntity(channel.id, startMs, stopMs, title, channel.name))
            true
        }
    }

    fun dismiss(reminder: ReminderEntity) {
        if (_due.value == reminder) _due.value = null
        scope.launch {
            runCatchingCancellable {
                dao.deleteReminder(reminder.channelId, reminder.startMs)
                check(System.currentTimeMillis())
            }.onFailure { Log.w("Reminders", "Could not dismiss reminder", it) }
        }
    }

    companion object {
        const val LEAD_MS = 60_000L
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val STALE_AFTER_MS = 10L * 60_000L
    }
}
