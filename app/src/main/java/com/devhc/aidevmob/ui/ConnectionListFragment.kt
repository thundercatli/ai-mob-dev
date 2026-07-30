package com.devhc.aidevmob.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.devhc.aidevmob.databinding.FragmentConnectionListBinding
import com.devhc.aidevmob.frp.FrpcRuntime
import com.devhc.aidevmob.ssh.ConnectionConfig
import com.devhc.aidevmob.ssh.ConnectionStore

class ConnectionListFragment : Fragment() {

    private var _binding: FragmentConnectionListBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: ConnectionStore
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
        store = ConnectionStore(requireContext().applicationContext)

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
        adapter.submit(profiles)
        binding.textEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE

        val runningTunnels = FrpcRuntime.runningTunnelIds().size
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
