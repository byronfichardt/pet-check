package io.github.byronfichardt.petcheck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.github.byronfichardt.petcheck.R

enum class Tab(val label: String) {
    MONITOR("Monitor"),
    ACTIVITY("Activity"),
    HISTORY("History"),
    SETTINGS("Settings"),
}

@Composable
fun PetCheckScreen(viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.MONITOR) }
    PetCheckScaffold(tab, onTabChange = { tab = it }) {
        when (tab) {
            Tab.MONITOR -> MonitorRoute(viewModel)
            Tab.ACTIVITY -> ActivityRoute(viewModel)
            Tab.HISTORY -> HistoryRoute(viewModel)
            Tab.SETTINGS -> SettingsRoute(viewModel)
        }
    }
}

@Composable
fun PetCheckScaffold(tab: Tab, onTabChange: (Tab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { onTabChange(item) },
                        icon = { TabIcon(item) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

@Composable
private fun TabIcon(tab: Tab) {
    when (tab) {
        Tab.MONITOR -> Icon(painterResource(R.drawable.ic_mic), contentDescription = null)
        Tab.ACTIVITY -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
        Tab.HISTORY -> Icon(painterResource(R.drawable.ic_chart), contentDescription = null)
        Tab.SETTINGS -> Icon(Icons.Filled.Settings, contentDescription = null)
    }
}
