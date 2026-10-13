package nl.yshott.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        screenshot("vandaag")
        compose.onNodeWithText("Agenda", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Toevoegen").performClick()
        compose.onNodeWithText("Naam").performTextInput("Rotatiecontrole")
        compose.onNodeWithText("Bewaren").performClick()
        compose.onNodeWithText("Rotatiecontrole").performScrollTo().assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Rotatiecontrole").performScrollTo().assertIsDisplayed()
        screenshot("agenda-na-herstel")
        compose.onNodeWithText("Week").performClick()
        screenshot("weekagenda")

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

    @Test fun recipeIngredientsReachShoppingList() {
        compose.onNodeWithText("Voorbeeld bekijken").performScrollTo().performClick()
        compose.onNodeWithText("Meer").performClick()
        compose.onNodeWithText("📖 Recepten").performScrollTo().performClick()
        compose.onNodeWithText("🛒 Ingrediënten op de lijst").performScrollTo().performClick()
        compose.onNodeWithText("400 g pasta").performScrollTo().assertIsDisplayed()
        val groceries = LocalStore(compose.activity).load()!!.groceries
        org.junit.Assert.assertTrue(groceries.any { it.name == "400 g pasta" && !it.done })
        screenshot("boodschappen")
    }

    @Test fun overlappingAppointmentShowsAWarningBeforeSaving() {
        compose.onNodeWithText("Voorbeeld bekijken").performScrollTo().performClick()
        compose.onNodeWithText("Agenda", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Toevoegen").performClick()
        compose.onNodeWithText("Naam").performTextInput("Eerste overlapcontrole")
        compose.onNodeWithText("Bewaren").performClick()
        compose.onNodeWithContentDescription("Toevoegen").performClick()
        compose.onNodeWithText("Naam").performTextInput("Tweede overlapcontrole")
        compose.onNodeWithText("Let op: op deze dag overlapt dit met", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Annuleren").performClick()
        org.junit.Assert.assertFalse(LocalStore(compose.activity).load()!!.events.any { it.title == "Tweede overlapcontrole" })
    }

    @Test fun childModeUsesIllustrationsAndKeepsParentGate() {
        val store=LocalStore(compose.activity)
        store.childProfile=""
        store.save(exampleData())
        store.setPin("2468")
        try {
            compose.onNodeWithText("Voorbeeld bekijken").performScrollTo().performClick()
            screenshot("vandaag")
            compose.onNodeWithText("Agenda",useUnmergedTree=true).performClick()
            screenshot("maandagenda")
            compose.onNodeWithText("Week").performClick()
            screenshot("weekagenda")
            compose.onNodeWithText("Vandaag",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Bij papa").performClick()
            compose.onNodeWithText("Kindmodus: Mila").performClick()
            compose.onNodeWithText("Vier cijfers").performTextInput("2468")
            compose.onNodeWithText("Verder").performClick()
            compose.onNodeWithContentDescription("Klusje klaar").performScrollTo().assertIsDisplayed()
            screenshot("kindmodus")
            compose.onNodeWithContentDescription("Ouderinstellingen openen met pincode").performClick()
            compose.onNodeWithText("Vier cijfers").assertIsDisplayed()
        } finally { store.childProfile="" }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("mkdir -p /data/local/tmp/yshott-screenshots")
        ).use { it.readBytes() }
        // Write through shell stdin; app storage is removed by the Android test runner.
        val target = "/data/local/tmp/yshott-screenshots/$name.png"
        val streams = automation.executeShellCommandRw("dd of=$target")
        val bitmap = automation.takeScreenshot()
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(streams[1]).use {
            org.junit.Assert.assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[0]).use { it.readBytes() }
        val size = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("wc -c $target")
        ).use { it.bufferedReader().readText().trim().takeWhile(Char::isDigit).toLongOrNull() ?: 0L }
        org.junit.Assert.assertTrue("The device screenshot must not be empty", size > 0)
    }
}
