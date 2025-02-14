package com.viscouspot.gitsync

import android.app.ActivityManager
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.viscouspot.gitsync.util.GitManager
import com.viscouspot.gitsync.util.Helper
import com.viscouspot.gitsync.util.LogType
import com.viscouspot.gitsync.util.Logger.log
import com.viscouspot.gitsync.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

class GitSyncService : Service() {
    private lateinit var fileObserver: FileObserver
    private lateinit var gitManager: GitManager
    private lateinit var settingsManager: SettingsManager

    private var isScheduled: Boolean = false
    private var isSyncing: Boolean = false
    private val debouncePeriod: Long = 10 * 1000

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "git_sync_service_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Git Sync Service"
        const val FORCE_SYNC = "FORCE_SYNC"
        const val APPLICATION_SYNC = "APPLICATION_SYNC"
        const val INTENT_SYNC = "INTENT_SYNC"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            return START_STICKY
        }

        when (intent.action) {
            FORCE_SYNC -> {
                log(LogType.ToServiceCommand, "Force Sync")
                debouncedSync(forced = true)
            }
            APPLICATION_SYNC -> {
                log(LogType.ToServiceCommand, "AccessibilityService Sync")
                debouncedSync()
            }
            INTENT_SYNC -> {
                log(LogType.ToServiceCommand, "Intent Sync")
                debouncedSync()
            }
        }

        return START_STICKY
    }


    override fun onCreate() {
        super.onCreate()
        gitManager = GitManager(this)
        settingsManager = SettingsManager(this)

        startForegroundService()
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = this.getSystemService(
                NotificationManager::class.java
            )
            manager?.createNotificationChannel(channel)
        }

        val buttonIntent = Intent(this, GitSyncService::class.java).apply {
            action = FORCE_SYNC
        }
        val buttonPendingIntent = PendingIntent.getService(this, Random.nextInt(0, 100), buttonIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.gitsync_notif)
            .addAction(NotificationCompat.Action(null, getString(R.string.force_sync), buttonPendingIntent))
            .build()

        startForeground(1, notification)
    }

    private fun debouncedSync(forced: Boolean = false) {
        if (isScheduled) {
            return
        } else {
            if (isSyncing) {
                isScheduled = true
                log(LogType.Sync, "Sync Scheduled")
                return
            } else {
                sync(forced)
            }
        }
    }

    private fun sync(forced: Boolean = false) {
        log(LogType.Sync, "Start Sync")
        isSyncing = true

        val job = CoroutineScope(Dispatchers.Default).launch {
            val authCredentials = settingsManager.getGitAuthCredentials()

            val gitDirUri = settingsManager.getGitDirUri() ?: return@launch
            val gitDirPath = Helper.getPathFromUri(applicationContext, gitDirUri)
            val file = File("${gitDirPath}/.git/config")

            if (gitDirUri == null || !file.exists()) {
                withContext(Dispatchers.Main) {
                    log(LogType.Sync, "Repository Not Found")
                    Toast.makeText(
                        applicationContext,
                        "Repository not found!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val fileContents = file.readText()

            val gitConfigUrlRegex = "url = (.*?)\\n".toRegex()
            var gitConfigUrlResult = gitConfigUrlRegex.find(fileContents)
            val repoUrl = gitConfigUrlResult?.groups?.get(1)?.value ?: run {
                withContext(Dispatchers.Main) {
                    log(LogType.Sync, "Invalid Repository URL")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.invalid_repository_url),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            var synced = false

            log(LogType.Sync, "Start Pull Repo")
            val pullResult = gitManager.pullRepository(
                gitDirUri,
                authCredentials.first,
                authCredentials.second
            ) {
                synced = true
                displaySyncMessage(getString(R.string.sync_start_pull))
            }

            when (pullResult) {
                null -> {
                    log(LogType.Sync, "Pull Repo Failed")
                    return@launch
                }

                true -> log(LogType.Sync, "Pull Complete")
                false -> log(LogType.Sync, "Pull Not Required")
            }

            while (File(gitDirPath, ".git/index.lock").exists()) {
                delay(1000)
            }

            log(LogType.Sync, "Start Push Repo")
            val pushResult = gitManager.pushAllToRepository(
                repoUrl,
                gitDirUri,
                authCredentials.first,
                authCredentials.second
            ) {
                if (!synced) {
                    displaySyncMessage(getString(R.string.sync_start_push))
                }
            }

            when (pushResult) {
                null -> {
                    log(LogType.Sync, "Push Repo Failed")
                    return@launch
                }

                true -> log(LogType.Sync, "Push Complete")
                false -> log(LogType.Sync, "Push Not Required")
            }

            while (File(gitDirPath, ".git/index.lock").exists()) {
                delay(1000)
            }

            if (!(pushResult || pullResult)) {
                if (forced) {
                    displaySyncMessage(getString(R.string.sync_not_required))
                }
                return@launch
            } else {
                displaySyncMessage(getString(R.string.sync_complete))
            }

            if (isForeground()) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(MainActivity.REFRESH)
                    LocalBroadcastManager.getInstance(this@GitSyncService).sendBroadcast(intent)
                }
            }
        }

        job.invokeOnCompletion {
            log(LogType.Sync, "Sync Complete")
            isSyncing = false
            if (isScheduled) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(debouncePeriod)
                    log(LogType.Sync, "Scheduled Sync Starting")
                    isScheduled = false
                    sync()
                }
            }
        }
    }

    private fun displaySyncMessage(msg: String) {
        if (settingsManager.getSyncMessageEnabled()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun isForeground(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val runningTaskInfo = manager.getRunningTasks(1)
        if (runningTaskInfo.isEmpty()) {
            return false
        }
        val componentInfo = runningTaskInfo[0].topActivity
        return componentInfo!!.packageName == packageName
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fileObserver.stopWatching()
        } catch (e: Exception) { e.printStackTrace() }
    }
}