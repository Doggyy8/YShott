package nl.yshott.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appointmentSurvivesActivityRecreationAndCanBeExported() {
        compose.onNodeWithText("Voorbeeld bekijken").performScrollTo().performClick()
        compose.onNodeWithText("Agenda", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Toevoegen").performClick()
        compose.onNodeWithText("Naam").performTextInput("Rotatiecontrole")
        compose.onNodeWithText("Bewaren").performClick()
        compose.onNodeWithText("Rotatiecontrole").performScrollTo().assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Rotatiecontrole").performScrollTo().assertIsDisplayed()

        val loaded = LocalStore(compose.activity).load()!!
        val event = loaded.events.single { it.title == "Rotatiecontrole" }
        assertEquals("09:00", event.time)
        org.junit.Assert.assertTrue(CalendarExport.ics(listOf(event)).contains("SUMMARY:Rotatiecontrole"))
    }

    @Test fun localStoreEncryptsAndRoundTripsPlanner() {
        val store = LocalStore(compose.activity)
        val data = exampleData().copy(groceries = listOf(Grocery(name = "Opslagcontrole")))
        store.save(data)
        assertEquals(data, store.load())
        val encrypted = java.io.File(compose.activity.filesDir, "planner-demo.enc").readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("Opslagcontrole"))
    }
}
