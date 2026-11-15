package app.docscan

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual crop of the finished page (opened from the edit screen): perspective crop with corner dots and side squares. */
@Composable
fun TrimScreen(vm: AppVM) {
    StatusBarIcons(lightIcons = true)
    val page = vm.trimId?.let { vm.page(it) }
    if (page?.cropped == null) {
        LaunchedEffect(Unit) { vm.screen = Screen.Review }
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12)))
        return
    }
    key(page.id, page.cropped) { TrimBody(vm, page.id, page.cropped) }
}

@Composable
private fun TrimBody(vm: AppVM, id: String, path: String) {
    val s = LocalStr.current
    val scope = rememberCoroutineScope()
    val dims by produceState<Pair<Int, Int>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }
    }
    val bmp by produceState<ImageBitmap?>(cachedBitmap(path, 1800), path) {
        value = withContext(Dispatchers.IO) { decodeBitmap(path, 1800) }
    }
    val quad = remember(path) { mutableStateListOf<Offset>() }
    LaunchedEffect(dims) {
        val d = dims ?: return@LaunchedEffect
        if (quad.isEmpty()) quad.addAll(fullFrame(d.first, d.second))
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { atFullSpeed { enter.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) } }

    fun leave() { vm.screen = Screen.Review }
    BackHandler { leave() }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0D12)).systemBarsPadding()
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 36f },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { leave() }) { Text(s.cancel, color = Color(0xFFFCA5A5)) }
            Spacer(Modifier.weight(1f))
            Text(s.trimTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
            val d = dims
            if (d != null) QuadCropper(bmp, d.first, d.second, quad, Modifier.fillMaxSize())
        }
        Text(
            s.trimHint, color = Color(0xB3FFFFFF), fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                val d = dims
                if (d != null) { quad.clear(); quad.addAll(fullFrame(d.first, d.second)) }
            }) { Text(s.trimReset, color = Color.White) }
            Button(
                onClick = {
                    scope.launch {
                        vm.busy = s.working
                        try {
                            vm.applyTrim(id, quad.toList())
                            vm.screen = Screen.Review
                        } catch (e: Exception) {
                            vm.toast(s.errPrefix + (e.message ?: ""))
                        } finally { vm.busy = null }
                    }
                },
                enabled = quad.size == 4 && dims != null,
                modifier = Modifier.weight(1f).height(50.dp),
            ) { Text(s.cropApply, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
