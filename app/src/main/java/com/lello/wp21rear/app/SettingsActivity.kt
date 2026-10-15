package com.lello.wp21rear.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.lello.wp21rear.R
import com.lello.wp21rear.core.Prefs

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rear screen settings"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Frag()).commit()
        }
    }

    class Frag : PreferenceFragmentCompat() {
        private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                keep(uri)
                prefs().sp.edit().putString(Prefs.K_PHOTO_FOLDER, uri.toString()).apply()
                updateSummaries()
            }
        }
        private val pickIdle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                keep(uri)
                prefs().sp.edit().putString(Prefs.K_IDLE_MEDIA, uri.toString()).apply()
                updateSummaries()
            }
        }

        private fun prefs() = Prefs(requireContext())

        private fun keep(uri: Uri) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            findPreference<Preference>("photo_folder_pick")?.setOnPreferenceClickListener {
                pickFolder.launch(null); true
            }
            findPreference<Preference>("idle_media_pick")?.setOnPreferenceClickListener {
                pickIdle.launch(arrayOf("image/gif", "image/webp", "image/*", "video/*")); true
            }
            findPreference<MultiSelectListPreference>(Prefs.K_NOTIF_MUTED)?.let { p ->
                val pm = requireContext().packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }.sortedBy { it.second.lowercase() }
                p.entries = apps.map { it.second }.toTypedArray()
                p.entryValues = apps.map { it.first }.toTypedArray()
            }
            updateSummaries()
        }

        private fun updateSummaries() {
            val p = prefs()
            findPreference<Preference>("photo_folder_pick")?.summary =
                p.photoFolder?.let { Uri.parse(it).lastPathSegment } ?: "Not set (newest 40 photos)"
            findPreference<Preference>("idle_media_pick")?.summary =
                p.idleMedia?.let { Uri.parse(it).lastPathSegment } ?: "Not set"
        }
    }
}
