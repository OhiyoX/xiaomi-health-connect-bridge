package id.harness.hcbridge

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** 外壳冒烟：三个顶级目的地都能打开，关键控件在位（构建/布局坏掉时先在这里红）。 */
@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {

    @Test
    fun 三个顶级页面都能打开() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.statusChip)).check(matches(isDisplayed()))

            onView(withId(R.id.nav_data)).perform(click())
            onView(withId(R.id.cardsBox)).check(matches(isDisplayed()))

            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.urlInput)).check(matches(isDisplayed()))

            onView(withId(R.id.nav_overview)).perform(click())
            onView(withId(R.id.statusChip)).check(matches(isDisplayed()))
        }
    }
}
