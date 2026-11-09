package bg.solar.nightcharge

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "NightCharge") {
        App()
    }
}

@Composable
fun App() {
    MaterialTheme {
        Column(Modifier.padding(16.dp)) {
            // TODO: перенасяне на екраните от ui/MainActivity.kt
            Text("NightCharge за Windows: скелет")
        }
    }
}
