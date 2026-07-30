package com.devhc.aidevmob.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.devhc.aidevmob.databinding.FragmentConnectionListBinding
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.FrpcRuntime
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.CredentialStore
import com.devhc.aidevmob.ssh.withCredential

class ConnectionListFragment : Fragment() {

    private var _binding: FragmentConnectionListBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: ConnectionStore
    private lateinit var credentialStore: CredentialStore
    private lateinit var adapter: ConnectionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appContext = requireContext().applicationContext
        store = ConnectionStore(appContext)
        credentialStore = CredentialStore(appContext)

        adapter = ConnectionAdapter(onOpen = ::openTerminal, onEdit = ::editConnection)
        binding.recyclerConnections.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerConnections.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), ConnectionEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val profiles = store.list()
        val tunnelNames = FrpcConfigStore(requireContext().applicationContext)
            .list()
            .associate { it.id to it.displayName }
        val runningIds = FrpcRuntime.runningTunnelIds()

        // Resolve credentials for display so a renamed login (or changed username) shows up here
        // without having to re-save every connection that uses it.
        val credentials = credentialStore.list().associateBy { it.id }

        adapter.submit(
            profiles.map { profile ->
                val config = profile.withCredential(credentials[profile.credentialId])
                ConnectionAdapter.Row(
                    config = config,
                    credentialName = credentials[profile.credentialId]?.displayName,
                    tunnelName = config.tunnelId?.let { tunnelNames[it] },
                    tunnelRunning = config.tunnelId != null && config.tunnelId in runningIds
                )
            }
        )
        binding.textEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE

        val runningTunnels = runningIds.size
        binding.textSubtitle.text = if (runningTunnels > 0) {
            "已有 $runningTunnels 条隧道在运行，点连接进入终端"
        } else {
            "点连接进入终端，需要的隧道会自动启动"
        }
    }

    private fun openTerminal(config: ConnectionConfig) {
        store.lastUsedId = config.id
        startActivity(
            Intent(requireContext(), TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_CONNECTION_ID, config.id)
        )
    }

    private fun editConnection(config: ConnectionConfig) {
        startActivity(
            Intent(requireContext(), ConnectionEditActivity::class.java)
                .putExtra(ConnectionEditActivity.EXTRA_CONNECTION_ID, config.id)
        )
    }
}
