package heating.app

import heating.app.notify.createChannel
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import heating.app.ui.HeatingApp
import heating.app.ui.theme.HeatingTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createChannel(this)
        setContent {
            HeatingTheme {
                // Paint the theme's background behind every screen, so text colours always match it
                // (without this, sign-in showed dark-mode text on the light window background).
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HeatingApp(vm)
                }
            }
        }
    }
}
