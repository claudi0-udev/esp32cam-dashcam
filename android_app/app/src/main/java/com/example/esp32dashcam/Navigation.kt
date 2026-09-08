package com.example.esp32dashcam

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.esp32dashcam.ui.main.MainScreen
import com.example.esp32dashcam.ui.player.PlayerScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(
                    onOpenPlayer = { filePath ->
                        backStack.add(Player(filePath))
                    }
                )
            }
            entry<Player> { playerKey ->
                PlayerScreen(
                    filePath = playerKey.filePath,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}
