package dev.liteluncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun ManageLayoutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Gray))
}
