package bg.solar.nightcharge.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bg.solar.nightcharge.Engine
import bg.solar.nightcharge.R
import bg.solar.nightcharge.core.Planner
import bg.solar.nightcharge.core.Proposal
import bg.solar.nightcharge.core.Settings
import bg.solar.nightcharge.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private val ONLINE = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

object Scheduler {
    private const val NAME = "evening-proposal"

    /** Всяка вечер в часа на решението се прави ново предложение. */
    fun schedule(ctx: Context, s: Settings, replace: Boolean) {
        val now = ZonedDateTime.now(Planner.ZONE)
        var next = now.withHour(s.decisionHour).withMinute(s.decisionMinute).withSecond(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val work = PeriodicWorkRequestBuilder<EveningWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(ONLINE)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, policy, work)
    }
}

class EveningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Notifier.proposal(applicationContext, Engine(applicationContext).refresh())
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) return@withContext Result.retry()
            Notifier.message(applicationContext, "Няма предложение за тази вечер", e.message ?: e.toString())
            Result.success()
        }
    }
}

/** Записва в инвертора одобреното предложение или, при отказ, изключва зареждането от мрежата. */
class ApplyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val engine = Engine(applicationContext)
        val p = engine.store.pending() ?: return@withContext Result.success()
        val reject = inputData.getBoolean(KEY_REJECT, false)
        try {
            val text = if (reject) {
                engine.reject(p)
                "Зареждането от мрежата е изключено за тази нощ."
            } else {
                val done = engine.apply(p)
                if (done.charge) {
                    "Инверторът ще зареди до ${done.targetSoc}% между ${Planner.hh(done.nightStart)} и ${Planner.hh(done.nightEnd)}."
                } else {
                    "Зареждането от мрежата е изключено за тази нощ."
                }
            }
            Notifier.message(applicationContext, "Записано в инвертора", text)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) return@withContext Result.retry()
            Notifier.message(applicationContext, "Неуспешен запис в инвертора", e.message ?: e.toString())
            Result.success()
        }
    }

    companion object {
        const val KEY_REJECT = "reject"
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.cancel(ctx)
        val reject = when (intent.action) {
            ACTION_APPROVE -> false
            ACTION_REJECT -> true
            else -> return
        }
        val work = OneTimeWorkRequestBuilder<ApplyWorker>()
            .setInputData(workDataOf(ApplyWorker.KEY_REJECT to reject))
            .setConstraints(ONLINE)
            .build()
        WorkManager.getInstance(ctx).enqueue(work)
    }

    companion object {
        const val ACTION_APPROVE = "bg.solar.nightcharge.APPROVE"
        const val ACTION_REJECT = "bg.solar.nightcharge.REJECT"
    }
}

object Notifier {
    private const val CHANNEL = "proposals"
    private const val ID_PROPOSAL = 1
    private const val ID_MESSAGE = 2

    private fun manager(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Предложения за зареждане", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun proposal(ctx: Context, p: Proposal) {
        val title = if (p.charge) "Да заредя ли до ${p.targetSoc}% тази нощ?" else "Тази нощ без зареждане от мрежата?"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_sun)
            .setContentTitle(title)
            .setContentText(p.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.reason))
            .setContentIntent(openApp(ctx))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Одобри", action(ctx, ActionReceiver.ACTION_APPROVE, 1))
            .addAction(0, "Откажи", action(ctx, ActionReceiver.ACTION_REJECT, 2))
            .build()
        manager(ctx).notify(ID_PROPOSAL, n)
    }

    fun message(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_sun)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        manager(ctx).notify(ID_MESSAGE, n)
    }

    fun cancel(ctx: Context) = manager(ctx).cancel(ID_PROPOSAL)
}
