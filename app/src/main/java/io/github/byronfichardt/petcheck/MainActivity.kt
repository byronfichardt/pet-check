package io.github.byronfichardt.petcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.byronfichardt.petcheck.ui.AppViewModel
import io.github.byronfichardt.petcheck.ui.PetCheckScreen
import io.github.byronfichardt.petcheck.ui.PetCheckTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory((application as PetCheckApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PetCheckTheme {
                PetCheckScreen(viewModel)
            }
        }
    }
}
