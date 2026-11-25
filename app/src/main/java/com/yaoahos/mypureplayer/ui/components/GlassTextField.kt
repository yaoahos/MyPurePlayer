package com.yaoahos.mypureplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃风格的输入框，全站唯一规格。
 *
 * 为什么不用 M3 的 `OutlinedTextField`：
 *
 * 1) **首帧太贵**。它内部是 label 上浮动画 + 指示线 + 容器 + 图标插槽，单个首次组合
 *    实测 10~15ms。配置备份页有 7 个，同一帧组合把首帧拖到 300ms —— 那一帧正好压在
 *    转场动画上，用户看到的就是「进页面卡一下」。这里换成 BasicTextField + 自绘容器，
 *    单字段约 3ms。
 *
 * 2) **风格不属于这套界面**。本项目所有面都是玻璃（页面渐变 + 淡色卡片 + 亮边），
 *    而 Outline 输入框是 Material 的描边样式，描边色是灰、聚焦时是主题纯色，夹在
 *    玻璃卡里像贴了一张塑料膜。
 *
 * 3) **标签常驻，不做上浮**。上浮动画除了贵，还会在「值先空后有」时当着用户的面跳一次
 *    （备份页早先为它专门做过延迟渲染的补丁）。标签始终在框上方，值区高度固定，
 *    空值用占位文字，任何时刻都不会重排。
 *
 * 交互与系统行为仍由 [BasicTextField] 提供：光标、选择、粘贴、输入法、无障碍都照旧。
 *
 * @param label 常驻标签，传 null 则不画标签行（搜索框这类自带语义的输入区）
 * @param minHeight 值区最小高度；搜索框这类紧凑控件可调小
 * @param shape 外框圆角；搜索框用胶囊（28dp）与其它控件区分
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    /** 框尾的固定后缀（如重命名对话框里的「.mp4」），不可编辑，随输入文字一起压暗 */
    suffix: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    minHeight: Dp = 52.dp,
) {
    val glass = appGlass()
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val labelColor = when {
        isError -> cs.error
        focused -> cs.primary
        else -> cs.onSurfaceVariant
    }
    // 凹槽的刃口要**上暗下亮**，与卡片（上亮下暗）相反：
    // 光从上打下来，上檐自己挡住光、在槽口内侧留下一条影；下唇迎着光、反出一条亮边。
    // 单色描边（白或中性灰）两边同色，无论调多深都只是"一圈线"，读不出厚度。
    //
    // 色相借主题色：日间用 glass.tint（primary 去饱和后的着色剂）做上沿阴影，
    // 近白底上叠出来是一条很淡的蓝灰刃，与页面、卡片同一族，不会冒出中性灰；
    // 下沿是白亮线 —— 那才是"玻璃被挖下去"的那道反光。
    val edgeTop = when {
        isError -> cs.error.copy(alpha = 0.75f)
        focused -> cs.primary.copy(alpha = 0.70f)
        glass.isDark -> Color.Black.copy(alpha = 0.30f)
        else -> glass.tint.copy(alpha = 0.22f)
    }
    val edgeBottom = when {
        isError -> cs.error.copy(alpha = 0.55f)
        focused -> cs.primary.copy(alpha = 0.45f)
        glass.isDark -> Color.White.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.72f)
    }
    val edgeWidth = if (focused || isError) 1.5.dp else 1.dp
    // 常态是渐变刃口；聚焦/错误用同色实线，状态反馈要一眼看出来，
    // 且粗一点（1.5dp）时渐变会显脏。
    val edgeBrush = if (focused || isError) {
        null
    } else {
        Brush.verticalGradient(listOf(edgeTop, edgeBottom))
    }
    // 上檐投在下方的影：贴着槽口、向下三成高度内衰减到透明。
    // 只画等宽的一条线仍会读成"描边"；有了这道衰减的影，槽口才有体积。
    // 聚焦时用主题色的一点点雾替代（焦点本身要显眼，不再压暗）。
    val shadeTop = when {
        isError -> cs.error.copy(alpha = 0.10f)
        focused -> cs.primary.copy(alpha = 0.06f)
        glass.isDark -> Color.Black.copy(alpha = 0.16f)
        else -> cs.onSurface.copy(alpha = 0.045f)
    }

    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                // 禁用态（如保险箱未配置指纹时的字段）压暗，否则看起来仍可输入
                color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.45f),
            ),
            cursorBrush = SolidColor(cs.primary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 「凹进去」：在玻璃卡上压一层极淡的暗色，而不是再叠一块更白的玻璃，
                        // 否则输入框与卡片同亮度，只剩一圈描边可看
                        .clip(shape)
                        .background(
                            when {
                                // 错误态整块都归到红色系：只把框和标签染红、填充还是灰的，
                                // 读起来像「两种状态叠在一起」
                                isError -> cs.error.copy(alpha = if (glass.isDark) 0.12f else 0.05f)
                                focused -> glass.tintSoft
                                else -> cs.onSurface.copy(alpha = glass.fieldAlpha())
                            },
                        )
                        // 画在填充之上、文字之下：形状已被 clip，渐变不会越出圆角
                        .drawBehind {
                            val h = size.height
                            if (h > 0f) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(shadeTop, Color.Transparent),
                                        startY = 0f,
                                        endY = h * 0.34f,
                                    ),
                                    size = size,
                                )
                            }
                        }
                        .then(
                            if (edgeBrush != null) {
                                Modifier.border(edgeWidth, edgeBrush, shape)
                            } else {
                                Modifier.border(edgeWidth, edgeTop, shape)
                            },
                        )
                        .heightIn(min = minHeight)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.let {
                        Box(Modifier.padding(end = 8.dp)) { it() }
                    }
                    // 竖向 padding 放在文字侧而不是整行：右侧密码眼睛是 48dp 的 IconButton，
                    // 若整行再各留 12dp，带眼睛的框会比普通框高出 24dp、一排框参差不齐。
                    //
                    // 文本区始终撑满：BasicTextField 自己是 fillMaxWidth，尾巴上的图标
                    // （清空/眼睛/后缀）才会落在框的右边缘。试过让它按内容收缩以让后缀
                    // 紧跟文字，代价是框内空白处不能点击聚焦，得不偿失。
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        }
                        inner()
                    }
                    suffix?.invoke()
                    // 直接调用：PasswordEye 是 IconButton（48dp 点击区），
                    // 套一层小尺寸 Box 会把它裁成 24dp，点击精度反而变差
                    trailingIcon?.invoke()
                }
            },
        )
    }
}

/** 输入框「凹面」的暗度：日间靠一层极淡的黑，夜间反过来要提亮一点才看得见边界 */
private fun AppGlassColors.fieldAlpha(): Float = if (isDark) 0.12f else 0.055f
