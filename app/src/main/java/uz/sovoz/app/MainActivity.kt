package uz.sovoz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }
}

private val LANGUAGES = listOf(
    "uz" to "O'zbekcha",
    "ru" to "Русский",
    "tr" to "Türkçe",
    "en" to "English"
)

@Composable
private fun Screen() {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }

    var text by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("uz") }
    var status by remember { mutableStateOf("Tayyor") }
    var speaking by remember { mutableStateOf(false) }

    val onStatus: (String) -> Unit = { s ->
        status = s
        speaking = s != "Tugadi" && s != "Tugadi (oflayn)" && s != "Matn bo'sh"
    }

    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Sovoz",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Matnni yozing — tanlangan tilda ovoz chiqarib o'qiydi.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LANGUAGES.forEach { (code, label) ->
                FilterChip(
                    selected = lang == code,
                    onClick = { lang = code },
                    label = { Text(label) }
                )
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Matn") },
            placeholder = { Text("Bu yerga matn kiriting…") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    speaking = true
                    speaker.speak(text, lang, onStatus)
                },
                modifier = Modifier.weight(1f),
                enabled = text.isNotBlank() && !speaking
            ) {
                Text("O'qish")
            }

            OutlinedButton(
                onClick = {
                    speaker.stop()
                    speaking = false
                    status = "To'xtatildi"
                },
                modifier = Modifier.weight(1f),
                enabled = speaking
            ) {
                Text("To'xtatish")
            }
        }

        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
