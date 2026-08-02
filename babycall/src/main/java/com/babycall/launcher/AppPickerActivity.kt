package com.babycall.launcher

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.appcompat.app.AppCompatActivity
import com.babycall.Prefs
import com.babycall.databinding.ActivityAppPickerBinding
import com.babycall.databinding.ItemAppPickerBinding

/**
 * Lets the caregiver choose which already-installed apps the baby is
 * allowed to open from the waiting screen. Reachable both from baby setup
 * and later from the hidden PIN-gated menu on the baby device itself --
 * this only ever runs on the baby's own device, since only it knows what's
 * installed locally.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppListAdapter
    private lateinit var apps: List<AppEntry>
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        selected.addAll(prefs.launcherAppPackages)
        apps = loadLaunchableApps()
        adapter = AppListAdapter()
        binding.listApps.adapter = adapter

        binding.btnSaveApps.setOnClickListener {
            prefs.launcherAppPackages = selected.toList()
            finish()
        }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(packageManager).toString(),
                    icon = it.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label }
    }

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val binding = if (convertView != null) {
                ItemAppPickerBinding.bind(convertView)
            } else {
                ItemAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            val entry = apps[position]
            binding.ivAppIcon.setImageDrawable(entry.icon)
            binding.tvAppLabel.text = entry.label
            binding.cbAppSelected.setOnCheckedChangeListener(null)
            binding.cbAppSelected.isChecked = selected.contains(entry.packageName)
            binding.cbAppSelected.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(entry.packageName) else selected.remove(entry.packageName)
            }
            binding.root.setOnClickListener {
                binding.cbAppSelected.isChecked = !binding.cbAppSelected.isChecked
            }
            return binding.root
        }
    }

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )
}
