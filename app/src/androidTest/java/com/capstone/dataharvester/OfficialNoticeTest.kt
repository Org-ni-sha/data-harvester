package com.capstone.dataharvester

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingRootException
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capstone.dataharvester.util.ConsentManager
import com.capstone.dataharvester.util.StudyManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-time "Official Collection" notice on the dashboard: shown on the first visit
 * (after Get Started, or after updating to 1.6.5), never again once dismissed.
 * Run on a device or emulator: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class OfficialNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ConsentManager(context).accept()
        context.getSharedPreferences("study_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(DataCollectionService.PREF_IS_COLLECTING, false).commit()
    }

    /** The notice opens after the database does (off the main thread), so poll for it. */
    private fun noticeShown(timeoutMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                onView(withText("Official Collection")).inRoot(isDialog()).check(matches(isDisplayed()))
                return true
            } catch (e: NoMatchingRootException) {
            } catch (e: NoMatchingViewException) {
            }
            Thread.sleep(250)
        }
        return false
    }

    @Test
    fun noticeIsShownOnce_andNotAgainAfterItIsDismissed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("notice on the first visit", noticeShown())
            onView(withText("LATER")).inRoot(isDialog()).perform(click())
        }
        assertTrue(StudyManager(context).isNoticeSeen())

        ActivityScenario.launch(MainActivity::class.java).use {
            assertFalse("notice must not come back", noticeShown(timeoutMs = 4_000))
        }
    }
}
