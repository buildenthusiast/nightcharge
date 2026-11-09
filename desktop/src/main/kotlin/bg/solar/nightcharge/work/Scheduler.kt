package bg.solar.nightcharge.work

import bg.solar.nightcharge.Engine
import bg.solar.nightcharge.core.Planner
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/**
 * Вечерното предложение на компютъра. Телефонът го прави с WorkManager; тук е нишка,
 * която проверява веднъж в минута. Работи само докато програмата е отворена.
 */
object Scheduler {
    @Volatile private var lastRunDate: LocalDate? = null

    fun start(engine: Engine) {
        thread(isDaemon = true, name = "evening-proposal") {
            while (true) {
                runCatching { tick(engine) }
                Thread.sleep(60_000L)
            }
        }
    }

    private fun tick(engine: Engine) {
        val s = engine.store.settings()
        val now = ZonedDateTime.now(Planner.ZONE)
        val today = now.toLocalDate()
        val due = now.hour > s.decisionHour || (now.hour == s.decisionHour && now.minute >= s.decisionMinute)
        if (!due || lastRunDate == today) return
        lastRunDate = today
        val p = engine.refresh()
        runCatching { engine.backupToDownloads() }
        if (s.autoApply) engine.apply(p)
    }
}
