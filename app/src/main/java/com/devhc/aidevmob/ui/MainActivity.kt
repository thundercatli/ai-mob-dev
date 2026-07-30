package com.devhc.aidevmob.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.devhc.aidevmob.R
import com.devhc.aidevmob.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyContentInsets(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.navTunnel -> TunnelListFragment()
                R.id.navCredential -> CredentialListFragment()
                else -> ConnectionListFragment()
            }
            showFragment(fragment)
            true
        }

        if (savedInstanceState == null) {
            val startTab = if (intent.getStringExtra(EXTRA_OPEN_TAB) == TAB_TUNNEL) {
                R.id.navTunnel
            } else {
                R.id.navConnect
            }
            binding.bottomNav.selectedItemId = startTab
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_OPEN_TAB) == TAB_TUNNEL) {
            binding.bottomNav.selectedItemId = R.id.navTunnel
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_TUNNEL = "tunnel"
    }
}
