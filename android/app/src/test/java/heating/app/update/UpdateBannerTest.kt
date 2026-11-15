package heating.app.update

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import heating.app.ui.TopBanners
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UpdateBannerTest {
    @get:Rule
    val compose = createComposeRule()

    // BDD: The update banner sits below the status bar
    @Test
    fun `the update banner sits below the status bar`() {
        compose.setContent {
            MaterialTheme {
                TopBanners(
                    updateState = UpdateState.Failed("0.9.0"), onUpdate = {},
                    brokerLost = true, brokerSince = null, onOpenConnection = {},
                    statusBar = WindowInsets(top = 24.dp),
                )
            }
        }
        val failed = compose.onNodeWithText("Update failed").getUnclippedBoundsInRoot()
        assertTrue(failed.top >= 24.dp, "banner text starts at ${failed.top}, under a 24 dp status bar")
        val again = compose.onNodeWithText("Try again").getUnclippedBoundsInRoot()
        assertTrue(again.top >= 24.dp, "button starts at ${again.top}")
        // The broker banner under it is not pushed down a second time.
        val connection = compose.onNodeWithText("Connection").getUnclippedBoundsInRoot()
        assertTrue(connection.top < failed.bottom + 40.dp, "broker banner at ${connection.top}, right under the update banner")
    }
}
