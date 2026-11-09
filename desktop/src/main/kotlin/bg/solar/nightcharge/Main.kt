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
import bg.solar.nightcharge.ui.SolarTheme

fun main() = application {
    val engine = remember { Engine() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "NightCharge",
        state = rememberWindowState(size = DpSize(1000.dp, 800.dp)),
    ) {
        SolarTheme {
            Surface(Modifier.fillMaxSize()) { App(engine) }
        }
    }
}
