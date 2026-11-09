package bg.solar.nightcharge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import bg.solar.nightcharge.ui.App
import bg.solar.nightcharge.core.AppPrefs
import bg.solar.nightcharge.ui.SolarTheme
import bg.solar.nightcharge.work.Scheduler

fun main() = application {
    val engine = remember {
        Engine().also {
            AppPrefs.language = it.store.appPref("lang", "auto")
            AppPrefs.theme = it.store.appPref("theme", "auto")
            Scheduler.start(it)
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "NightCharge",
        state = rememberWindowState(size = DpSize(1000.dp, 800.dp)),
    ) {
        SolarTheme(AppPrefs.theme) {
            Surface(Modifier.fillMaxSize()) { App(engine) }
        }
    }
}
