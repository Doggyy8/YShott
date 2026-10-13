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

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = java.io.File(compose.activity.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
        val output = java.io.File(directory, "$name.png")
        java.io.FileOutputStream(output).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        // Android's test runner removes app storage after the tests. Keep shell-owned copies for CI.
        val command = "mkdir -p /data/local/tmp/yshott-screenshots && cp '${output.absolutePath}' /data/local/tmp/yshott-screenshots/$name.png"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
