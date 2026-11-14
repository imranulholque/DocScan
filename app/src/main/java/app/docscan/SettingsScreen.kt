package app.docscan

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The folder picker (any folder of the phone's memory). Returns a function that opens it. */
@Composable
fun rememberFolderPicker(vm: AppVM): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) { }
            vm.setTree(uri.toString())
        }
    }
    return { launcher.launch(null) }
}

@Composable
fun LocationPicker(vm: AppVM) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val openPicker = rememberFolderPicker(vm)
    SectionLabel(s.location)
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.folderName() ?: s.defaultLoc, fontSize = 13.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
            if (vm.treeUri != null) TextButton(onClick = { vm.setTree(null) }) { Text(s.reset) }
            TextButton(onClick = openPicker) { Text(s.change, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppVM) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    BackHandler { vm.screen = Screen.Home }
    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) }
            Text(s.settings, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionLabel(s.language)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("বাংলা", vm.lang == "bn", Modifier.weight(1f)) { vm.chooseLang("bn") }
                Choice("English", vm.lang == "en", Modifier.weight(1f)) { vm.chooseLang("en") }
            }
            FormatPicker(vm)
            LocationPicker(vm)

            SectionLabel(s.autoCrop)
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.autoCrop, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(s.autoCropSub, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = vm.autoCrop, onCheckedChange = { vm.chooseAutoCrop(it) })
                }
            }

            SectionLabel(s.updTitle)
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.updAuto, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(s.updAutoSub, fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = vm.autoUpdate, onCheckedChange = { vm.chooseAutoUpdate(it) })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (Updater.canSelfUpdate()) s.updVersion(BuildConfig.BUILD) else s.updDev,
                        fontSize = 12.sp, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { vm.checkUpdate(true) } }, enabled = !vm.checkingUpdate) {
                        Text(s.updCheck)
                    }
                }
            }
            SectionLabel(s.aboutTitle)
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("DocScan", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = cs.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(s.aboutText, fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(s.credit, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.onSurface)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
