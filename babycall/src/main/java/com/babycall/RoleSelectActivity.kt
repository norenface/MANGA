package com.babycall

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.babycall.databinding.ActivityRoleSelectBinding
import com.babycall.home.BabyHomeActivity
import com.babycall.home.ParentHomeActivity
import com.babycall.pairing.BabySetupActivity
import com.babycall.pairing.JoinFamilyActivity

class RoleSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (prefs.isPaired) {
            routeToHome()
            return
        }

        binding = ActivityRoleSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnJoinFamily.setOnClickListener {
            startActivity(Intent(this, JoinFamilyActivity::class.java))
        }
        binding.btnBaby.setOnClickListener {
            startActivity(Intent(this, BabySetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isPaired) routeToHome()
    }

    private fun routeToHome() {
        val target = if (prefs.role == "baby") BabyHomeActivity::class.java else ParentHomeActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
