package com.winters.cleanphotos.feature.allphotos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.DevelopingPrintsArt
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.Eyebrow
import com.winters.cleanphotos.core.design.PrimaryButton
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.permission.PermissionChecker

private val permItems = listOf(
    Triple("01", "只在这台手机上处理", "照片不会上传，App 不联网。"),
    Triple("02", "移到垃圾桶随时能找回", "只是 App 里的标记，清空时才真正删除。"),
    Triple("03", "删除前会再确认一次", "永久删除由系统弹窗授权，你说了算。"),
)

/**
 * 首次授权。三张相纸用打包在 App 里的示意图（授权前读不到相册），反复显影。
 * Android 14+ 才显示「只选部分照片」；拒绝且不再询问时主按钮改为「去系统设置开启」。
 */
@Composable
fun PhotoPermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(DarkroomColors.Room)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Dimens.PermSide)) {
            DevelopingPrintsArt(
                left = painterResource(R.drawable.perm_print_left),
                center = painterResource(R.drawable.perm_print_center),
                right = painterResource(R.drawable.perm_print_right),
                modifier = Modifier.padding(top = 32.dp),
            )
            Box(Modifier.padding(top = 8.dp, bottom = 4.dp)) { Eyebrow("开始之前") }
            Text("允许访问你的相册", style = DarkroomText.PermH4)
            Column(Modifier.padding(top = 24.dp)) {
                permItems.forEach { (no, title, body) ->
                    Box(Modifier.fillMaxWidth().height(Dimens.Hairline).background(DarkroomColors.Rim))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(DarkroomColors.Tray)
                                .border(Dimens.Hairline, DarkroomColors.Rim, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(no, style = DarkroomText.PermItemNo) }
                        Column {
                            Text(title, style = DarkroomText.PermItemTitle)
                            Text(body, style = DarkroomText.PermItemBody)
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.PermSide),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(if (permanentlyDenied) "去系统设置开启" else "允许访问照片", onClick = onRequest)
            // .perm-go 距底 72、.perm-alt 距底 36（都从系统导航条上沿量）
            Box(Modifier.fillMaxWidth().height(Dimens.PermGoBottom), contentAlignment = Alignment.BottomCenter) {
                if (PermissionChecker.supportsPartialAccess && !permanentlyDenied) {
                    Text(
                        "只选部分照片",
                        style = DarkroomText.TextButton,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = Dimens.PermAltBottom).plainClickable(onClick = onRequest),
                    )
                }
            }
        }
    }
}
