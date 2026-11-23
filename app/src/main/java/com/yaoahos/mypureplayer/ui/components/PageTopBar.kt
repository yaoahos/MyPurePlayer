package com.yaoahos.mypureplayer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

/**
 * 四个底栏页面共用的小标题栏。
 *
 * 它现在**只是一段标题文字**，不画底色、不画分隔线、不投阴影 —— 与页面同为一体。
 *
 * 早期版本把它做成一条玻璃栏：半透明底 + 内容侧双线 + 向下阴影。
 * 结果是页面顶部被横切出一条"带子"，无论那条带子多淡，都像是另一个控件贴在了页面上，
 * 用户原话是"顶栏与页面应该是一体的，现在很突兀"。
 *
 * 为什么可以完全透明：内容区（Scaffold body）在顶栏**下方**开始，不会滚动到标题底下，
 * 所以不需要不透明底去盖住内容。背景由 AppRoot 的常驻层统一绘制，
 * 顶栏透明之后，标题就直接落在同一张渐变背景上，天然是一页。
 *
 * 层次改由排版承担：标题 titleLarge、副标题 labelSmall + 次级色，
 * 与下方第一张卡片之间留 10dp，靠字号和留白区分区域，而不是靠一条色块。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTopBar(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onBack: (() -> Unit)? = null,
    // 多选态换成关闭图标：返回在这里的语义是「退出选择」而不是「退页」。
    backIcon: ImageVector? = null,
    backLabel: String = "返回",
) {
    TopAppBar(
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(backIcon ?: Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = backLabel)
                }
            }
        },
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = actions,
        modifier = Modifier.fillMaxWidth(),
        colors = TopAppBarDefaults.topAppBarColors(
            // 全透明：栏位不存在，标题直接是页面的一部分
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
    )
}
