package dev.liteluncher.ui

import androidx.compose.runtime.Composable
import dev.liteluncher.data.LauncherState
import dev.liteluncher.settings.ScreenManagerScreen

@Composable
fun ManageLayoutScreen(state: LauncherState, onUpdate: ((LauncherState) -> LauncherState) -> Unit, onBack: () -> Unit) {
    ScreenManagerScreen(state, onUpdate, onBack)
}
