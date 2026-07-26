package com.babycall

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * We initialize Firebase manually from string resources instead of using
 * google-services.json + the Google Services Gradle plugin. This keeps the
 * project buildable without a real Firebase project wired in, and setup for
 * a real deployment is just pasting 4 values into strings_firebase.xml
 * (see README-babycall.md).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (FirebaseApp.getApps(this).isNotEmpty()) return

        val apiKey = getString(R.string.firebase_api_key)
        val appId = getString(R.string.firebase_application_id)
        val projectId = getString(R.string.firebase_project_id)
        val databaseUrl = getString(R.string.firebase_database_url)

        if (apiKey.startsWith("YOUR_") || appId.startsWith("YOUR_")) {
            Log.w(TAG, "Firebase is not configured yet. Fill in strings_firebase.xml " +
                "(see README-babycall.md) before pairing devices or making calls.")
            return
        }

        val options = FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(appId)
            .setProjectId(projectId)
            .setDatabaseUrl(databaseUrl)
            .build()

        FirebaseApp.initializeApp(this, options)
    }

    companion object {
        private const val TAG = "BabyCallApp"

        fun isFirebaseConfigured(context: android.content.Context): Boolean {
            val apiKey = context.getString(R.string.firebase_api_key)
            return !apiKey.startsWith("YOUR_")
        }
    }
}
