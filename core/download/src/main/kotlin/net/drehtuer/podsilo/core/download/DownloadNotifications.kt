// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Fixed id: one active download at a time, so a running download replaces the previous notification. */
const val DOWNLOAD_NOTIFICATION_ID = 4711

private const val CHANNEL_ID = "podsilo.downloads"
private const val PERCENT = 100

/**
 * The foreground-service notification for an active download (CLAUDE.md §11: Doze and background
 * limits will otherwise stop a long download dead).
 *
 * Uses the platform [Notification.Builder] rather than `NotificationCompat`: `minSdk` is 33, well
 * past the API 26 channel requirement, so the compat layer would buy nothing and cost a dependency.
 */
class DownloadNotifications(
    private val context: Context,
) {
    private val manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)

    /** Idempotent — creating an existing channel is a no-op, and the user's own channel settings win. */
    fun ensureChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_notification_channel_name),
                // LOW: a download running is ambient information, not something to interrupt for.
                NotificationManager.IMPORTANCE_LOW,
            )
        manager?.createNotificationChannel(channel)
    }

    /**
     * @param totalBytes `null` when the server disclosed no `Content-Length` — the bar goes
     *   indeterminate rather than inventing a percentage.
     */
    fun buildProgress(
        episodeTitle: String,
        bytesWritten: Long,
        totalBytes: Long?,
    ): Notification {
        ensureChannel()
        val indeterminate = totalBytes == null || totalBytes <= 0
        val progress = if (indeterminate) 0 else ((bytesWritten * PERCENT) / totalBytes).toInt()
        return Notification
            .Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(episodeTitle)
            // The brand mark, not the platform's download arrow: in a shade full of other apps'
            // progress notifications, a stock glyph makes ours the one the user cannot pick out
            // (UI.adoc §C3).
            .setSmallIcon(R.drawable.ic_podsilo_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT, progress, indeterminate)
            .build()
    }

    fun showProgress(
        episodeTitle: String,
        bytesWritten: Long,
        totalBytes: Long?,
    ) {
        manager?.notify(DOWNLOAD_NOTIFICATION_ID, buildProgress(episodeTitle, bytesWritten, totalBytes))
    }

    fun clear() {
        manager?.cancel(DOWNLOAD_NOTIFICATION_ID)
    }
}
