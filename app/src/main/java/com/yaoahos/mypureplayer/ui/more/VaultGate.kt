package com.yaoahos.mypureplayer.ui.more

import com.yaoahos.mypureplayer.ui.components.GlassDialogButton
import com.yaoahos.mypureplayer.ui.components.GlassTextField
import com.yaoahos.mypureplayer.ui.components.GlassButtonTone
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yaoahos.mypureplayer.data.Graph
import com.yaoahos.mypureplayer.data.VaultConfig
import com.yaoahos.mypureplayer.data.VaultRepo
import com.yaoahos.mypureplayer.ui.components.alertDialogGlassSurface
import com.yaoahos.mypureplayer.ui.components.appDialogSurface
import com.yaoahos.mypureplayer.ui.components.PageMetrics
import com.yaoahos.mypureplayer.ui.components.PasswordEye
import kotlinx.coroutines.launch

/** 从 Compose 的 Context 里找出宿主 Activity */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/** 指纹解锁封装（不依赖 androidx.biometric 的界面，直接调用系统弹窗） */
object VaultBiometric {

    fun available(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(
        activity: FragmentActivity,
        cipher: javax.crypto.Cipher?,
        onSuccess: (javax.crypto.Cipher) -> Unit,
        onError: (String) -> Unit,
        title: String = "解锁保险箱",
        subtitle: String = "验证指纹以查看隐藏的视频",
        negativeText: String = "使用密码",
        // 用户主动取消时也要有人收尾。设密码时的「开启指纹」如果被取消而没有回调，
        // 弹窗会永远停在「设置中…」（实测就是这样卡住的）。
        onCancel: (() -> Unit)? = null,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked != null) onSuccess(unlocked)
                    else onError("验证成功但无法解开密钥，请改用密码")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户主动取消不弹错误，但必须回调 —— 调用方可能正卡在"进行中"
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onCancel?.invoke()
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    onError("指纹未识别，请重试")
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        val crypto = cipher?.let { BiometricPrompt.CryptoObject(it) }
        runCatching {
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        }.onFailure { onError(it.message ?: "无法启动指纹验证") }
    }

    /** 纯身份验证：不绑定密钥，只确认是本人（用于开启指纹解锁等敏感开关）。 */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    onError("指纹未识别，请重试")
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("验证指纹")
            .setSubtitle("确认是本人后开启指纹解锁")
            .setNegativeButtonText("取消")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { onError(it.message ?: "无法启动指纹验证") }
    }

    /**
     * 开启指纹解锁。
     *
     * 必须先用生物密钥 init 出一个**加密** Cipher，把它交给 BiometricPrompt；
     * 认证成功后系统才放行这把密钥，此时才能真正封装数据密钥。
     * 直接拿 unauthenticated 的密钥做加密会抛 UserNotAuthenticatedException，
     * 这正是过去「开启指纹解锁失败」的原因。
     */
    fun enroll(
        activity: FragmentActivity,
        onAuthed: (javax.crypto.Cipher) -> Unit,
        onError: (String) -> Unit,
    ) {
        val cipher = Graph.vault.beginBioEnrollCipher()
        if (cipher == null) {
            onError("系统未提供可用的指纹密钥，请重试")
            return
        }
        prompt(
            activity = activity,
            cipher = cipher,
            onSuccess = onAuthed,
            onError = onError,
            title = "开启指纹解锁",
            subtitle = "验证指纹后即可用指纹进入保险箱",
            negativeText = "暂不开启",
            // 「暂不开启」= 放弃指纹、密码已经生效，流程照常收尾
            onCancel = { onError("") },
        )
    }
}

/** 首次设置保险箱密码（[currentPin] 用于「改密码」时重包同一把数据密钥） */
@Composable
fun VaultSetupDialog(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    currentPin: String? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var biometric by remember { mutableStateOf(VaultBiometric.available(context)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showPin by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val valid = Graph.vault.isPinValid(pin) && pin == confirm

    AlertDialog(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.alertDialogGlassSurface(),
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text("设置保险箱密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "隐藏的视频会被加密存放在应用私有目录，媒体库与文件管理器都不会显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 长度规则写在输入框上方，而不是塞进 label：
                // label 里带一长串括号，输入时会被浮到框上方挤成一团，也像校验报错。
                Text(
                    "密码 4-20 位，可用字母和数字（4 位数字也可以）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GlassTextField(
                    value = pin,
                    onValueChange = { pin = Graph.vault.normalizePin(it); error = null },
                    label = "密码",
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { PasswordEye(showPin) { showPin = !showPin } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassTextField(
                    value = confirm,
                    onValueChange = { confirm = Graph.vault.normalizePin(it); error = null },
                    label = "再次输入",
                    visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { PasswordEye(showConfirm) { showConfirm = !showConfirm } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "启用指纹快捷解锁",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = biometric,
                        onCheckedChange = { biometric = it },
                        enabled = VaultBiometric.available(context),
                    )
                }
                if (pin.isNotEmpty() && pin != confirm && confirm.isNotEmpty()) {
                    Text("两次输入的密码不一致", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            GlassDialogButton(
                enabled = valid && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        // setPin 内部有 Keystore/DataStore 操作，个别机型会抛异常。
                        // 必须兜底，否则协程异常直接冒泡成应用闪退。
                        val ok = runCatching { Graph.vault.setPin(pin, biometric, currentPin) }
                            .onFailure { android.util.Log.e("VaultGate", "设置密码失败", it) }
                            .getOrDefault(false)
                        if (!ok) {
                            saving = false
                            // 直接用仓库给的原因：可能是「已有加密文件、需先解锁」这种
                            // 必须让用户看懂才能动手的情况，不能统一糊成"请重试"。
                            error = Graph.vault.lastSetPinError ?: "密码设置失败，请重试"
                            return@launch
                        }
                        val host = context.findFragmentActivity()
                        if (biometric && host != null && VaultBiometric.available(context)) {
                            // 密码已落库，紧接着用一次指纹认证把数据密钥封装给生物密钥。
                            // 中途放弃（取消/失败）不影响密码进入，只是暂时只能密码解锁。
                            VaultBiometric.enroll(
                                host,
                                onAuthed = { cipher ->
                                    scope.launch {
                                        Graph.vault.setBiometricWithCipher(cipher)
                                        saving = false
                                        onDone()
                                    }
                                },
                                onError = {
                                    saving = false
                                    onDone()
                                },
                            )
                        } else {
                            saving = false
                            onDone()
                        }
                    }
                },
            tone = GlassButtonTone.Accent,
            ) { Text(if (saving) "设置中…" else "确定") }
        },
        dismissButton = { GlassDialogButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 保险箱锁定态：密码 + 指纹 */
@Composable
fun VaultUnlockPanel(
    config: VaultConfig,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    // 锁定倒计时，仅用于提示，不驱动状态机
    var lockTick by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(lockTick) {
        if (Graph.vault.lockRemainingMs() <= 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(1_000L)
        lockTick++
    }

    val activity = remember(context) { context.findFragmentActivity() }

    fun reportFailure(message: String) {
        error = message
        Graph.vault.vibrateWrongPin(context)
        scope.launch {
            shake.animateTo(-10f, androidx.compose.animation.core.tween(60))
            shake.animateTo(10f, androidx.compose.animation.core.tween(90))
            shake.animateTo(-6f, androidx.compose.animation.core.tween(90))
            shake.animateTo(0f, androidx.compose.animation.core.tween(90))
        }
    }

    fun unlockWithBiometric() {
        val host = activity ?: return
        val cipher = Graph.vault.bioCipher()
        if (cipher == null) {
            reportFailure("指纹密钥不可用，请改用密码")
            return
        }
        VaultBiometric.prompt(
            host,
            cipher,
            onSuccess = { unlocked ->
                if (Graph.vault.onBiometricUnlocked(unlocked)) {
                    onUnlocked()
                } else {
                    reportFailure("指纹解锁失败，请改用密码")
                }
            },
            onError = { message -> if (message.isNotBlank()) reportFailure(message) },
        )
    }

    // 开启了指纹就默认先用指纹：进入锁定页立刻弹系统指纹框，密码退为备选。
    //
    // 只自动弹一次（autoPrompted）：用户点「使用密码」或直接取消后，不能每次重组
    // 都把弹窗再顶出来；之后想用指纹，输入框右侧的指纹按钮随时可以重试。
    var autoPrompted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(config.enabled, config.biometric, activity) {
        if (autoPrompted) return@LaunchedEffect
        if (!config.enabled || !config.biometric) return@LaunchedEffect
        if (activity == null || !VaultBiometric.available(context)) return@LaunchedEffect
        autoPrompted = true
        unlockWithBiometric()
    }

    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(PageMetrics.PanelRadius))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("保险箱已锁定", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            if (config.biometric && VaultBiometric.available(context)) {
                "验证指纹即可进入，也可以输入密码"
            } else {
                "输入 4-20 位密码后查看隐藏的视频"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

        val fingerprintSlot: (@Composable () -> Unit)? =
            if (config.biometric && activity != null && VaultBiometric.available(context)) {
                {
                    IconButton(onClick = { unlockWithBiometric() }) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = "指纹解锁")
                    }
                }
            } else {
                null
            }

        GlassTextField(
            value = pin,
            // 同一套归一：全角数字会被转成半角，否则"设得进去、解不开"
            onValueChange = {
                pin = Graph.vault.normalizePin(it)
                error = null
            },
            label = "密码",
            visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                // 指纹 + 眼睛：一个负责快捷解锁，一个负责临时看一眼输入了什么
                Row(verticalAlignment = Alignment.CenterVertically) {
                    fingerprintSlot?.invoke()
                    PasswordEye(showPin) { showPin = !showPin }
                }
            },
            isError = error != null,
            modifier = Modifier.fillMaxWidth().offset(x = shake.value.dp),
        )
        val remaining = Graph.vault.lockRemainingMs()
        if (remaining > 0L) {
            Spacer(Modifier.height(8.dp))
            Text(
                "输错次数过多，请在 ${(remaining + 999) / 1000} 秒后重试",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(18.dp))
        // 与弹窗底部、表单行尾同一套玻璃按钮：M3 Button 的实色片在玻璃面板上是一个
        // 突兀的深蓝块，而这一屏除此之外全是玻璃
        com.yaoahos.mypureplayer.ui.components.GlassButton(
            text = "解锁",
            loading = checking,
            onClick = {
                if (!Graph.vault.isPinValid(pin)) return@GlassButton
                checking = true
                scope.launch {
                    val ok = Graph.vault.verifyPin(pin)
                    checking = false
                    if (ok) {
                        pin = ""
                        onUnlocked()
                    } else {
                        reportFailure("密码不正确")
                    }
                }
            },
            // 解锁沿用与设置一致的下限（4 位），真实合法性由 verifyPin 判定
            enabled = pin.length in VaultRepo.MIN_PIN..VaultRepo.MAX_PIN &&
                !checking && Graph.vault.lockRemainingMs() <= 0L,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「隐藏到保险箱」在保险箱已锁定时弹出的解锁框。
 *
 * 为什么需要它：数据密钥按设计只在解锁后驻留内存（冷启动后为空），
 * 于是"锁着状态直接隐藏"必然失败 —— 之前只是失败，不给入口。
 * 现在把解锁面板放进一个对话框，解完立刻继续隐藏，一次动作不被打断。
 */
@Composable
fun VaultUnlockDialog(
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
    title: String = "解锁保险箱",
) {
    val config by Graph.vault.config.collectAsStateWithLifecycle()
    // 与其它弹窗同一策略：有应用内浮层就不开新窗口。
    // 独立窗口自带一层 dim（实测 0.6），只压页面不压状态栏 —— 打开时会看到
    // 顶部状态栏比下面亮一截；开窗本身还要 50~65ms。
    val host = com.yaoahos.mypureplayer.ui.components.LocalAppOverlayHost.current
    if (host != null) {
        com.yaoahos.mypureplayer.ui.components.AppOverlaySlot {
            UnlockBody(config, title, onDismiss, onUnlocked)
        }
        return
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        com.yaoahos.mypureplayer.ui.components.DisableDialogWindowDim()
        UnlockBody(config, title, onDismiss, onUnlocked)
    }
}

@Composable
private fun UnlockBody(
    config: VaultConfig,
    title: String,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        // 遮罩：早先这个弹窗没有遮罩，全靠对话框窗口的 dim。走浮层后必须自己画，
        // 否则背后页面仍然是亮的，看不出"这是一个需要先处理掉的框"。
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .widthIn(max = com.yaoahos.mypureplayer.ui.components.PageMetrics.DialogMaxWidth)
                // 与全站弹窗同一块面板：早先用 panelStrong（近实色白片），在浅色下比
                // 其它弹窗明显更白更实，看起来像"另一套弹窗"。改成同一张玻璃面板。
                .appDialogSurface(
                    shape = RoundedCornerShape(PageMetrics.PanelRadius),
                    elevation = 8.dp,
                )
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 14.dp),
            )
            VaultUnlockPanel(config = config, onUnlocked = onUnlocked)
            com.yaoahos.mypureplayer.ui.components.GlassButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.padding(bottom = 8.dp, top = 2.dp),
                tone = com.yaoahos.mypureplayer.ui.components.GlassButtonTone.Neutral,
            )
        }
    }
}
