package com.devhc.aidevmob.ui

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devhc.aidevmob.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the app open at all?
 *
 * That is the entire point of this test. v0.2.0 and v0.2.1 both shipped a crash in MainActivity's
 * field initializers - the kind that compiles cleanly, passes every static check, and then dies on
 * every single launch. Nothing short of actually starting the activity catches it, so CI opens the
 * app on an emulator before a release is allowed out.
 *
 * Keep this test dumb and dependency-free: no SSH, no tunnel, nothing that needs a reachable host.
 * It only has to answer "does it start, and can you reach each tab".
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearOptOut() {
        // Start from the state a fresh install is in, so the startup permission dialog is in play for
        // [opensFromAColdStart] - it is the code that crashed, and it has to survive a device where
        // nothing has been granted yet.
        setPermissionPromptOptedOut(false)
    }

    @Test
    fun opensFromAColdStart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    /**
     * Each tab's fragment reads its own EncryptedSharedPreferences store on first draw, so walking the
     * three of them also covers the keystore-backed stores and the credential migration in
     * Application.onCreate.
     */
    @Test
    fun everyTabLoadsItsFragment() {
        // With the dialog up, taps would land on it instead of the nav bar.
        setPermissionPromptOptedOut(true)

        val tabs = listOf(
            R.id.navConnect to ConnectionListFragment::class.java,
            R.id.navCredential to CredentialListFragment::class.java,
            R.id.navTunnel to TunnelListFragment::class.java
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            tabs.forEach { (itemId, fragmentClass) ->
                scenario.onActivity { activity ->
                    activity.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = itemId
                    activity.supportFragmentManager.executePendingTransactions()
                    val shown = activity.supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                    assertTrue(
                        "expected ${fragmentClass.simpleName}, got ${shown?.javaClass?.simpleName}",
                        fragmentClass.isInstance(shown)
                    )
                }
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun setPermissionPromptOptedOut(optedOut: Boolean) {
        context.getSharedPreferences(StartupPermissionCheck.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(StartupPermissionCheck.KEY_OPTED_OUT, optedOut)
            .commit()
    }
}
