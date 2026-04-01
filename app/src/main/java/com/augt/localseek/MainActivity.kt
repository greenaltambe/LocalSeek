package com.augt.localseek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ui.SearchViewModel
import com.augt.localseek.ui.theme.LocalSeekTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // This launches a coroutine tied to the Activity's lifecycle.
        // It will call seed function on a background thread.
        // Because the seed function checks if the DB is empty, this is safe
        // to run every time the app starts. It will only add data once.
        lifecycleScope.launch {
            AppDatabase.seedTestData(this@MainActivity)
        }
        setContent {
            LocalSeekTheme {
                SearchApp(viewModel = viewModel)
            }
        }
    }
}
