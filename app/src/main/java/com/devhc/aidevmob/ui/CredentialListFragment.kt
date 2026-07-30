package com.devhc.aidevmob.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.devhc.aidevmob.databinding.FragmentCredentialListBinding
import com.devhc.aidevmob.ssh.Credential
import com.devhc.aidevmob.ssh.ConnectionStore
import com.devhc.aidevmob.ssh.CredentialStore

/** Lists the saved SSH identities; connections pick one instead of storing their own copy. */
class CredentialListFragment : Fragment() {

    private var _binding: FragmentCredentialListBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: CredentialStore
    private lateinit var connectionStore: ConnectionStore
    private lateinit var adapter: CredentialAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCredentialListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appContext = requireContext().applicationContext
        store = CredentialStore(appContext)
        connectionStore = ConnectionStore(appContext)

        adapter = CredentialAdapter(onEdit = ::editCredential)
        binding.recyclerCredentials.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCredentials.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), CredentialEditActivity::class.java))
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
        val credentials = store.list()
        val usageByCredential = connectionStore.list()
            .mapNotNull { it.credentialId }
            .groupingBy { it }
            .eachCount()

        adapter.submit(
            credentials.map { CredentialAdapter.Row(it, usageByCredential[it.id] ?: 0) }
        )
        binding.textEmpty.visibility = if (credentials.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun editCredential(credential: Credential) {
        startActivity(
            Intent(requireContext(), CredentialEditActivity::class.java)
                .putExtra(CredentialEditActivity.EXTRA_CREDENTIAL_ID, credential.id)
        )
    }
}
