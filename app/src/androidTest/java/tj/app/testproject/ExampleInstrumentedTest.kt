package tj.app.testproject

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherOpensTheRealComposeFeed() {
        onView(withId(R.id.open_compose_demo)).perform(click())
        compose.onNodeWithText("Compose demo").assertIsDisplayed()
        compose.onNodeWithTag("compose_feed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Quiet morning").assertIsDisplayed()
    }
}
