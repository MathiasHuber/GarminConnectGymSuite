package io.github.mathiashuber.gymsuite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mathiashuber.gymsuite.garmin.LoginFlow
import io.github.mathiashuber.gymsuite.ui.AppViewModel
import io.github.mathiashuber.gymsuite.ui.EraDetailScreen
import io.github.mathiashuber.gymsuite.ui.ErasScreen
import io.github.mathiashuber.gymsuite.ui.ExerciseScreen
import io.github.mathiashuber.gymsuite.ui.GymSuiteTheme
import io.github.mathiashuber.gymsuite.ui.HomeScreen
import io.github.mathiashuber.gymsuite.ui.LoginScreen
import io.github.mathiashuber.gymsuite.ui.SettingsScreen

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Login(val flow: LoginFlow) : Screen
    data class Exercise(val seriesId: String) : Screen
    data object Eras : Screen
    data class Era(val key: Long) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GymSuiteTheme { App() } }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = pop)

    when (val screen = stack.last()) {
        Screen.Home -> HomeScreen(
            vm = vm,
            onLogin = { stack.add(Screen.Login(it)) },
            onOpenExercise = { stack.add(Screen.Exercise(it)) },
            onOpenSettings = { stack.add(Screen.Settings) },
            onOpenEras = { stack.add(Screen.Eras) },
        )
        Screen.Settings -> SettingsScreen(vm = vm, onBack = pop)
        is Screen.Login -> LoginScreen(vm = vm, flow = screen.flow, onDone = pop)
        is Screen.Exercise -> ExerciseScreen(
            vm = vm,
            seriesId = screen.seriesId,
            onBack = pop,
            onReplace = { stack[stack.lastIndex] = Screen.Exercise(it) },
        )
        Screen.Eras -> ErasScreen(vm = vm, onBack = pop, onOpenEra = { stack.add(Screen.Era(it)) })
        is Screen.Era -> EraDetailScreen(
            vm = vm,
            eraKey = screen.key,
            onBack = pop,
            onReplace = { stack[stack.lastIndex] = Screen.Era(it) },
            onOpenExercise = { stack.add(Screen.Exercise(it)) },
        )
    }
}
