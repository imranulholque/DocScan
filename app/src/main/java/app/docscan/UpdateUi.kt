package app.docscan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

/**
 * The "Update now?" question, the download progress and the install permission.
 * The question shows on Home and Settings only (never in the middle of a scan): "Check for updates" in Settings answers right there,
 * and an automatic find waits until the person is back on Home.
 */
@Composable
fun UpdateDialogs(vm: AppVM) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val info = vm.update

    if (vm.updateAsk && info != null && (vm.screen == Screen.Home || vm.screen == Screen.Settings) && vm.updateProgress == null) {
        AlertDialog(
            onDismissRequest = { vm.updateAsk = false },
            title = { Text(s.updAvail(info.build), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    if (info.size > 0) Text(mb(info.size), fontSize = 13.sp, color = cs.onSurfaceVariant)
                    if (info.notes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(s.updNew, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(info.notes, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { Button(onClick = { vm.startUpdate() }) { Text(s.updNow, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { vm.updateAsk = false }) { Text(s.updLater) } },
        )
    }

    vm.updateProgress?.let { (done, total) ->
        Dialog(onDismissRequest = { }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(24.dp), color = cs.surface) {
                Column(Modifier.padding(22.dp)) {
                    Text(s.updDownloading, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (total > 0) "${mb(done)} / ${mb(total)}" else mb(done), fontSize = 13.sp, color = cs.onSurfaceVariant)
                        TextButton(onClick = { vm.cancelUpdateDownload() }) { Text(s.cancel) }
                    }
                }
            }
        }
    }

    if (vm.askInstallPerm && vm.pendingApk != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(s.updPermTitle, fontWeight = FontWeight.Bold) },
            text = { Text(s.updPermText, fontSize = 14.sp) },
            confirmButton = { Button(onClick = { Updater.openInstallSettings(ctx) }) { Text(s.updOpenSettings) } },
            dismissButton = { TextButton(onClick = { vm.askInstallPerm = false; vm.pendingApk = null }) { Text(s.cancel) } },
        )
    }
}

/** Card on the Home screen while a newer build is waiting. */
@Composable
fun UpdateCard(vm: AppVM, modifier: Modifier = Modifier) {
    val s = LocalStr.current
    val cs = MaterialTheme.colorScheme
    val info = vm.update ?: return
    Surface(shape = RoundedCornerShape(20.dp), color = cs.primaryContainer, modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.updAvail(info.build), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cs.onPrimaryContainer)
            Button(onClick = { vm.updateAsk = true }) { Text(s.updNow, fontWeight = FontWeight.Bold) }
        }
    }
}
