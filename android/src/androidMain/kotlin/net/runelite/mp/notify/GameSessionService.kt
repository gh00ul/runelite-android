package net.runelite.mp.notify

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import net.runelite.mp.R

/**
 * Keeps the RuneLite process running while the app is in the background.
 *
 * Without a foreground service Android freezes a backgrounded app's process within seconds
 * (the cached-app freezer), so the client stops ticking: the session drops and nothing
 * fires — no Idle Notifier, no low HP alert. This holds the process in the foreground
 * state for as long as the task exists and shows a quiet ongoing notification while it does.
 *
 * `stopWithTask` in the manifest ends it when the player swipes RuneLite away in Recents.
 */
class GameSessionService : Service()
{
    companion object
    {
        private const val TAG = "GameSessionService"
        private const val CHANNEL = "runelite_session"
        private const val ID = 0x5E55
        private const val ACTION_QUIT = "net.runelite.mp.notify.QUIT"

        fun start(context: Context)
        {
            try
            {
                context.startForegroundService(Intent(context, GameSessionService::class.java))
            }
            catch (t: Throwable)
            {
                // ForegroundServiceStartNotAllowedException if we were somehow started from
                // the background; the game still works, it just won't survive backgrounding.
                Log.w(TAG, "could not start background session service", t)
            }
        }

        fun stop(context: Context)
        {
            context.stopService(Intent(context, GameSessionService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        if (intent?.action == ACTION_QUIT)
        {
            // The notification's Quit button: like swiping RuneLite away in Recents, but
            // exiting normally so the client's shutdown hooks still run.
            getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() }
            stopSelf()
            Runtime.getRuntime().exit(0)
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null)
        {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Background session", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while RuneLite keeps your game running in the background"
                    setShowBadge(false)
                }
            )
        }

        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_runelite)
            .setContentTitle("RuneLite is running")
            .setContentText("Your session and alerts stay active in the background")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(AndroidNotifier.openAppIntent(this))
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_runelite), "Quit",
                PendingIntent.getService(this, 0, Intent(this, GameSessionService::class.java).setAction(ACTION_QUIT),
                    PendingIntent.FLAG_IMMUTABLE)
            ).build())
            .build()

        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
            else
            {
                startForeground(ID, notification)
            }
        }
        catch (t: Throwable)
        {
            Log.w(TAG, "startForeground failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?)
    {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
