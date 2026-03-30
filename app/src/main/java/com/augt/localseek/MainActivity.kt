package com.augt.localseek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.augt.localseek.ui.SearchViewModel
import com.augt.localseek.ui.theme.LocalSeekTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalSeekTheme {
                SearchApp(viewModel = viewModel)
            }
        }
    }
}
