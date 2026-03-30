package com.augt.localseek

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.augt.localseek.ui.SearchScreen
import com.augt.localseek.ui.SearchViewModel


@Composable
fun SearchApp(viewModel: SearchViewModel, modifier: Modifier = Modifier) {

    Scaffold(modifier = modifier) { innerPadding ->
        SearchScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
    }
}