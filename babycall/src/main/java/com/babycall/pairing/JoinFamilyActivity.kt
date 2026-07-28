package com.babycall.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivityJoinFamilyBinding
import com.babycall.home.ParentHomeActivity
import kotlinx.coroutines.launch

/**
 * Lets a relative who lives elsewhere (grandparent, uncle, ...) join an
 * existing family as an additional viewer, using an invite code generated
 * from the primary parent's ParentHomeActivity. Cloud mode only — the
 * whole point is being reachable from a different network.
 */
class JoinFamilyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJoinFamilyBinding
    private lateinit var prefs: Prefs
    private val repo = PairingRepository()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        binding.tvPermissionWarning.visibility =
            if (result.values.all { it }) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinFamilyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())

        binding.btnJoin.setOnClickListener { onJoinClicked() }
    }

    private fun onJoinClicked() {
        val code = binding.etCode.text?.toString()?.replace(Regex("[^0-9]"), "").orEmpty()
        val name = binding.etName.text?.toString()?.trim().orEmpty().ifEmpty { "家族" }

        if (code.length != 6) {
            showError(getString(R.string.error_code_format))
            return
        }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnJoin.isEnabled = false

        lifecycleScope.launch {
            try {
                AuthGate.ensureSignedIn()
                val deviceId = prefs.deviceId
                val familyId = repo.joinFamilyAsParent(code, deviceId, name)

                prefs.role = "parent"
                prefs.transportMode = Prefs.TRANSPORT_CLOUD
                prefs.familyId = familyId
                prefs.deviceName = name

                startActivity(Intent(this@JoinFamilyActivity, ParentHomeActivity::class.java))
                finish()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
                binding.btnJoin.isEnabled = true
            }
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }
}
