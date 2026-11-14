package app.docscan

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: AppVM) {
    val s = LocalStr.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.importUris(uris) }
    }
    val cs = MaterialTheme.colorScheme
    val ready = vm.pages.count { it.cropped != null }

    Column(
        Modifier.fillMaxSize().background(cs.background).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DocScan", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = cs.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.chooseLang(if (vm.lang == "bn") "en" else "bn") }) {
                Text(if (vm.lang == "bn") "English" else "বাংলা", fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { vm.screen = Screen.Settings }) {
                Icon(Icons.Filled.Settings, contentDescription = s.settings, tint = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (vm.update != null && vm.autoUpdate) {
            UpdateCard(vm)
            Spacer(Modifier.height(10.dp))
        }

        // hero
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Indigo, Violet)))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.tagline, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                HeroDoc()
            }
        }
        Spacer(Modifier.height(18.dp))

        BigAction(
            title = s.scan, sub = s.scanSub, primary = true,
            onClick = { vm.screen = Screen.Camera },
        )
        Spacer(Modifier.height(12.dp))
        BigAction(
            title = s.gallery, sub = s.gallerySub, primary = false,
            onClick = { pick.launch("image/*") },
        )

        if (ready > 0) {
            Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = cs.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.ongoing(ready), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cs.onSecondaryContainer)
                    }
                    TextButton(onClick = { vm.clearAll() }) { Text(s.startNew, color = cs.onSecondaryContainer) }
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = cs.secondary,
                        modifier = Modifier.clickable { vm.current = 0; vm.screen = Screen.Review },
                    ) {
                        Text(s.resume, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = cs.onSecondary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        Text(s.howTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Step("1", s.how1, Modifier.weight(1f))
            Step("2", s.how2, Modifier.weight(1f))
            Step("3", s.how3, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HeroDoc() {
    Canvas(Modifier.size(72.dp, 84.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(Color.White, Offset(w * 0.14f, h * 0.08f), Size(w * 0.72f, h * 0.84f), CornerRadius(10f, 10f))
        for (i in 0 until 4) {
            drawLine(Indigo, Offset(w * 0.26f, h * (0.28f + i * 0.14f)), Offset(w * (if (i == 3) 0.5f else 0.74f), h * (0.28f + i * 0.14f)),
                strokeWidth = 5f, cap = StrokeCap.Round)
        }
        val c = Color(0xFF5EEAD4); val sw = 7f; val L = w * 0.22f
        val corners = listOf(Offset(0f, 0f) to (1f to 1f), Offset(w, 0f) to (-1f to 1f), Offset(w, h) to (-1f to -1f), Offset(0f, h) to (1f to -1f))
        for ((p, d) in corners) {
            drawLine(c, p, Offset(p.x + d.first * L, p.y), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(c, p, Offset(p.x, p.y + d.second * L), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun BigAction(title: String, sub: String, primary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (primary) cs.primary else cs.surface,
        shadowElevation = if (primary) 6.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (primary) Color(0x33FFFFFF) else cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (primary) {
                    Canvas(Modifier.size(24.dp)) {
                        drawCircle(Color.White, radius = size.minDimension * 0.22f)
                        drawCircle(Color.White, radius = size.minDimension * 0.46f, style = Stroke(width = 4f))
                    }
                } else {
                    Icon(Icons.Filled.Add, null, tint = cs.primary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (primary) cs.onPrimary else cs.onSurface)
                Text(sub, fontSize = 13.sp, color = if (primary) Color(0xCCFFFFFF) else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Step(n: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surface, modifier = modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text(n, fontWeight = FontWeight.Bold, color = cs.primary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
