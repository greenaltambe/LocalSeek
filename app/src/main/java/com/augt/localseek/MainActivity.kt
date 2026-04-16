package com.augt.localseek

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.ui.SearchViewModel
import com.augt.localseek.ui.theme.LocalSeekTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SearchViewModel by viewModels()

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                startIndexing()
            }
        }
    }

    // Launcher for older Android <= 10 storage permission dialog
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startIndexing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Seed our 4 dummy test documents (from Phase 1)
        lifecycleScope.launch {
            AppDatabase.seedTestData(this@MainActivity)
        }

        // Check permissions and start indexing real files!
        checkPermissionsAndIndex()

        // Set up the periodic 6-hour background indexer
        IndexScheduler.schedulePeriodicIndex(this)
        // --- TEMPORARY AI TEST ---
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val encoder = com.augt.localseek.ml.DenseEncoder(this@MainActivity)
                val vector = encoder.encode("pineapple")
                android.util.Log.d("DenseRetrieval", "✅ MiniLM Inference Success!")
                android.util.Log.d("DenseRetrieval", "Vector Shape: ${vector.size} (Should be 384)")
                android.util.Log.d("DenseRetrieval", "First 5 values: ${vector.take(5)}")
                encoder.close()
            } catch (e: Exception) {
                android.util.Log.e("DenseRetrieval", "❌ Inference Failed", e)
            }
        }
        // -------------------------
        setContent {
            LocalSeekTheme {
                SearchApp(viewModel = viewModel)
            }
        }
    }

    private fun checkPermissionsAndIndex() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            if (Environment.isExternalStorageManager()) {
                startIndexing()
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                manageStorageLauncher.launch(intent)
            }
        } else {
            // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                startIndexing()
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun startIndexing() {
        // Now that we have permission, trigger the WorkManager to scan the device!
        IndexScheduler.scheduleImmediateIndex(this)
    }
}