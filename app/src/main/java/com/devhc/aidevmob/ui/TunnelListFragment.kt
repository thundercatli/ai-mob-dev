package com.devhc.aidevmob.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.devhc.aidevmob.databinding.FragmentTunnelListBinding
import com.devhc.aidevmob.frp.FrpcConfig
import com.devhc.aidevmob.frp.FrpcConfigStore
import com.devhc.aidevmob.frp.FrpcRuntime
import com.devhc.aidevmob.frp.FrpcVisitorService

class TunnelListFragment : Fragment() {

    private var _binding: FragmentTunnelListBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: FrpcConfigStore
    private lateinit var adapter: TunnelAdapter

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val runtimeListener: () -> Unit = { activity?.runOnUiThread { refresh() } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTunnelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = FrpcConfigStore(requireContext().applicationContext)

        adapter = TunnelAdapter(onToggle = ::toggleTunnel, onEdit = ::editTunnel)
        binding.recyclerTunnels.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTunnels.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), TunnelEditActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        FrpcRuntime.addListener(runtimeListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        FrpcRuntime.removeListener(runtimeListener)
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        if (_binding == null) return
        val tunnels = store.list()
        adapter.submit(tunnels)
        binding.textEmpty.visibility = if (tunnels.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleTunnel(config: FrpcConfig, start: Boolean) {
        val context = requireContext().applicationContext
        if (start) {
            requestNotificationPermissionIfNeeded()
            FrpcVisitorService.start(context, config.id)
        } else {
            FrpcVisitorService.stop(context, config.id)
        }
    }

    private fun editTunnel(config: FrpcConfig) {
        startActivity(
            Intent(requireContext(), TunnelEditActivity::class.java)
                .putExtra(TunnelEditActivity.EXTRA_TUNNEL_ID, config.id)
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
