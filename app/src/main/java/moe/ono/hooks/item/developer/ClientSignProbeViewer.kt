package moe.ono.hooks.item.developer

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks.base.util.Toasts
import moe.ono.ui.CommonContextWrapper
import moe.ono.util.Logger
import moe.ono.util.SyncUtils
import moe.ono.util.SystemServiceUtils

@SuppressLint("DiscouragedApi")
@HookItem(
    path = "开发者选项/查看并复制移动端签名诊断",
    description = "显示并复制移动端身份、请求摘要以及最近一次 FEKit 签名"
)
class ClientSignProbeViewer : BaseClickableFunctionHookItem() {
    override fun entry(classLoader: ClassLoader) = Unit

    override fun onClick(context: Context) {
        Thread {
            val result = runCatching {
                QSignHook.diagnostics().toString(2)
            }
            SyncUtils.runOnUiThread {
                val fixedContext = CommonContextWrapper.createAppCompatContext(context)
                result.onSuccess { text ->
                    MaterialAlertDialogBuilder(fixedContext)
                        .setTitle("移动端签名诊断")
                        .setMessage(text)
                        .setNegativeButton("关闭", null)
                        .setPositiveButton("复制全部") { _, _ ->
                            SystemServiceUtils.copyToClipboard(fixedContext, text)
                            Toasts.success(fixedContext, "已复制")
                        }
                        .show()
                }.onFailure { error ->
                    Logger.e("ClientSignProbeViewer", error)
                    MaterialAlertDialogBuilder(fixedContext)
                        .setTitle("签名探针未运行")
                        .setMessage("请先启用“移动端身份与签名探针”，彻底结束 QQ 后重新启动。\n\n${error.message}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }.start()
    }
}
