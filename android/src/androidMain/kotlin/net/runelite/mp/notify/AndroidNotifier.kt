package net.runelite.mp.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.awt.TrayIcon
import java.util.function.Consumer
import net.runelite.client.config.NotificationSound
import net.runelite.client.eventbus.EventBus
import net.runelite.client.events.NotificationFired
import net.runelite.mp.R
import net.runelite.mp.ui.bridge.RuneLiteAccess

/**
 * Delivers RuneLite notifications (Idle Notifier, low HP, timers, any plugin calling
 * `Notifier.notify`) as real Android notifications.
 *
 * Upstream's `Notifier` ends in `TrayIcon.displayMessage`, `notify-send` or `osascript`,
 * none of which exist on Android, so every alert used to vanish. Rather than patching
 * `Notifier` (one more upstream file to carry through every RuneLite bump), this listens for
 * the `NotificationFired` event it posts for every enabled notification, before any of its
 * desktop delivery.
 *
 * Behaviour, mapped from the desktop settings in RuneLite's "Notification" config:
 *  - App in the background: a heads-up system notification when "Tray notification" is on
 *    (Android mirrors it to a paired watch), otherwise a vibration if sound is on.
 *  - App on screen: a vibration only, unless "Send notifications when focused" is on, in
 *    which case it's the full notification. On a phone the game stays on screen for a whole
 *    AFK session, so a buzz is the useful equivalent of the desktop's beep. When silent mode
 *    or Do Not Disturb would mute that buzz, the notification is shown instead.
 *
 * Stale alerts are cleared when the player comes back to the app.
 */
internal object AndroidNotifier
{
    private const val TAG = "AndroidNotifier"
    private const val CHANNEL_ALERTS = "runelite_alerts"
    private const val GROUP_ALERTS = "net.runelite.mp.ALERTS"
    /** Alerts are told apart by tag (their message, numbers masked); the id is shared. */
    private const val ALERT_ID = 0x4000
    private val DIGITS = Regex("\\d+")
    /** Plugins can fire several notifications in one tick; one buzz is enough. */
    private const val VIBRATE_DEBOUNCE_MS = 1_500L
    private val ALERT_PATTERN = longArrayOf(0, 180, 120, 180)

    @Volatile private var appContext: Context? = null
    @Volatile private var registered = false
    @Volatile private var inForeground = true
    @Volatile private var lastVibrate = 0L

    /** Call once the RuneLite injector exists (WindowImpl.bootComplete). Idempotent. */
    fun start(context: Context)
    {
        if (registered) return
        val ctx = context.applicationContext
        appContext = ctx
        createChannel(ctx)
        // Alerts left over from a previous run are stale once the game is back on screen.
        if (inForeground) clearAlerts()

        val bus = RuneLiteAccess.instance(EventBus::class.java)
        if (bus == null)
        {
            Log.w(TAG, "EventBus not available; notifications stay disabled this session")
            return
        }
        bus.register(NotificationFired::class.java, Consumer { onFired(it) }, 0f)
        registered = true
        Log.i(TAG, "listening for RuneLite notifications")
    }

    /** Driven by MainActivity: true while the game is on screen and the app in use. */
    fun setForeground(foreground: Boolean)
    {
        inForeground = foreground
        if (foreground) clearAlerts()
    }

    private fun onFired(event: NotificationFired)
    {
        val ctx = appContext ?: return
        val notification = event.notification ?: return
        val message = event.message?.takeIf { it.isNotBlank() } ?: return
        val soundOn = notification.sound != NotificationSound.OFF

        try
        {
            when
            {
                inForeground && !notification.isSendWhenFocused -> if (soundOn)
                {
                    // The buzz is the whole alert here; if the phone would mute it, show the
                    // notification so the alert isn't lost.
                    if (notification.isTray && buzzMuted(ctx)) post(ctx, message, event.type) else vibrate(ctx)
                }
                notification.isTray -> post(ctx, message, event.type)
                soundOn -> vibrate(ctx)
            }
        }
        catch (t: Throwable)
        {
            // Never let an alert take down the client thread that posted it.
            Log.w(TAG, "failed to deliver notification", t)
        }
    }

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled()
    private fun post(ctx: Context, message: String, type: TrayIcon.MessageType?)
    {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channelOff = manager.getNotificationChannel(CHANNEL_ALERTS)?.importance == NotificationManager.IMPORTANCE_NONE
        if (!manager.areNotificationsEnabled() || channelOff)
        {
            // POST_NOTIFICATIONS denied, or just "Game alerts" turned off: the notification
            // would be dropped, so still get the player's attention.
            vibrate(ctx)
            return
        }

        val title = when (type)
        {
            TrayIcon.MessageType.ERROR -> "RuneLite — alert"
            TrayIcon.MessageType.WARNING -> "RuneLite — warning"
            else -> "RuneLite"
        }
        val built = Notification.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_runelite)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setGroup(GROUP_ALERTS)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(openAppIntent(ctx))
            .build()

        // Tagged by message with numbers masked, so a repeat ("You are now idle!" again, or
        // "HP is 9" then "HP is 7") replaces the old alert and alerts afresh instead of stacking.
        manager.notify(message.replace(DIGITS, "#"), ALERT_ID, built)
        // The channel vibrates on its own; don't double-buzz right after.
        lastVibrate = SystemClock.elapsedRealtime()
    }

    private fun vibrate(ctx: Context)
    {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVibrate < VIBRATE_DEBOUNCE_MS) return
        lastVibrate = now

        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        }
        else
        {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        val effect = VibrationEffect.createWaveform(ALERT_PATTERN, -1)
        // Marked as a notification buzz so the phone's notification vibration, silent mode
        // and Do Not Disturb settings apply to it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        }
        else
        {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
    }

    /** Silent mode or Do Not Disturb, either of which mutes a notification vibration. */
    private fun buzzMuted(ctx: Context): Boolean
    {
        val ringerSilent = ctx.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_SILENT
        val filter = ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val dnd = filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return ringerSilent || dnd
    }

    /** Asks the system what's showing rather than remembering ids, so a restart doesn't strand any. */
    private fun clearAlerts()
    {
        val manager = appContext?.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.notification.channelId == CHANNEL_ALERTS }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun createChannel(ctx: Context)
    {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ALERTS) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Game alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "RuneLite notifications such as Idle Notifier and low HP alerts"
                enableVibration(true)
                vibrationPattern = ALERT_PATTERN
            }
        )
    }

    /** Brings the existing task forward instead of stacking a second MainActivity. */
    internal fun openAppIntent(ctx: Context): PendingIntent?
    {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            ctx, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
