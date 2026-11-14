package app.docscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DocScanTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot(vm: AppVM = viewModel()) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    // a downloaded update goes to Android's installer; if Android still needs the one-time permission, ask for it first
    fun tryInstall() {
        val apk = vm.pendingApk ?: return
        if (Updater.canInstall(ctx)) {
            vm.askInstallPerm = false
            vm.pendingApk = null
            Updater.install(ctx, apk)
        } else {
            vm.askInstallPerm = true
        }
    }
    LaunchedEffect(vm.pendingApk) { tryInstall() }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tryInstall() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    CompositionLocalProvider(LocalStr provides vm.str) {
        when (vm.screen) {
            Screen.Home -> HomeScreen(vm)
            Screen.Camera -> CameraScreen(vm)
            Screen.Crop -> CropScreen(vm)
            Screen.Review -> ReviewScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
        }
        BusyOverlay(vm.busy)
        UpdateDialogs(vm)
    }
}
