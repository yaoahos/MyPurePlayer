package com.yaoahos.mypureplayer.ui.more

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.navigation.NavHostController
import com.yaoahos.mypureplayer.ui.MainScaffold
import com.yaoahos.mypureplayer.ui.Routes
import com.yaoahos.mypureplayer.ui.components.PageTopBar
import com.yaoahos.mypureplayer.ui.components.appGlass
import com.yaoahos.mypureplayer.ui.components.appGlassSurface
import com.yaoahos.mypureplayer.ui.components.mediaCardOutline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(navController: NavHostController) {
    MainScaffold(
        topBar = {
            PageTopBar(title = "更多", subtitle = "设置、历史、回收站、保险箱、串流与关于")
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 与顶栏拉开距离：卡片紧贴标题会显得挤，也让「更多」这一页没呼吸感
            Spacer(Modifier.height(10.dp))
            // 顶部是「身份 + 用了什么」，**不再是「关于」的入口**。
            // 之前整张卡可点、点开就是关于页，而下面的「关于」行也指向同一页：
            // 两个入口通向同一个页面，用户点哪个都像是「介绍页」，重复且费解。
            TechSummaryCard()
            Spacer(Modifier.height(10.dp))

            MoreRow(
                icon = Icons.Rounded.Settings,
                title = "设置",
                subtitle = "外观、播放、缓存",
                onClick = { navController.navigate(Routes.SETTINGS) },
            )
            MoreRow(
                icon = Icons.Rounded.History,
                title = "历史记录",
                subtitle = "继续上次观看到的位置",
                onClick = { navController.navigate(Routes.HISTORY) },
            )
            MoreRow(
                icon = Icons.Rounded.DeleteOutline,
                title = "回收站",
                subtitle = "删除的视频先放这里，可恢复或彻底删除",
                onClick = { navController.navigate(Routes.TRASH) },
            )
            MoreRow(
                icon = Icons.Rounded.Shield,
                title = "保险箱",
                subtitle = "密码或指纹进入，加密隐藏视频",
                onClick = { navController.navigate(Routes.VAULT) },
            )
            MoreRow(
                icon = Icons.Rounded.Podcasts,
                title = "串流",
                subtitle = "新建 http / https / rtsp / rtsps 串流地址",
                onClick = { navController.navigate(Routes.STREAMS) },
            )
            MoreRow(
                icon = Icons.Rounded.Info,
                title = "关于",
                subtitle = "版本、权限说明与开源协议",
                onClick = { navController.navigate(Routes.ABOUT) },
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 顶部技术摘要：应用身份 + 一句话 + 框架清单。
 *
 * 尺寸：图标 48dp、标题 titleLarge —— 比 40dp/titleMedium 再放大一档，
 * 品牌区更醒目；仍保持三行短文字，不摊开成表格。
 *
 * 版本号取自 BuildConfig；框架版本与 build.gradle.kts 的依赖一致，升级依赖时同步改这一处。
 */
@Composable
private fun TechSummaryCard() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .appGlassSurface(RoundedCornerShape(20.dp), strong = true)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(size = 48.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "MyPurePlayer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "v${com.yaoahos.mypureplayer.BuildConfig.VERSION_NAME} · 本地 / 网络 / 字幕 / 投屏",
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Media3 1.11.1 · libass 0.6.0-beta01 · Compose M3",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mediaCardOutline()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(appGlass().tintSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
