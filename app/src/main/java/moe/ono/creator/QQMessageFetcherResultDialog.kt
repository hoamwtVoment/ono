package moe.ono.creator

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.core.BottomPopupView
import com.lxj.xpopup.util.XPopupUtils
import moe.ono.R
import moe.ono.config.CacheConfig
import moe.ono.hooks.base.util.Toasts
import moe.ono.ui.CommonContextWrapper
import moe.ono.ui.view.JsonViewer
import moe.ono.util.AppRuntimeHelper
import moe.ono.util.Logger
import moe.ono.util.analytics.ActionReporter
import org.json.JSONObject
import java.util.Locale

@SuppressLint("ResourceType")
class QQMessageFetcherResultDialog(context: Context) : BottomPopupView(context) {

    @SuppressLint("SetTextI18n", "ServiceCast")
    override fun onCreate() {
        super.onCreate()
        Handler(Looper.getMainLooper()).postDelayed({
            val jsonViewer = findViewById<JsonViewer>(R.id.rv_json)
            val btnCopy = findViewById<Button>(R.id.btn_copy)
            val rgType = findViewById<RadioGroup>(R.id.rg_type)
            val tvContent = findViewById<TextView>(R.id.tv_content)

            // 原布局只有 PB、PB (elem)、MsgRecord。这里在运行时插入第四项，
            // 不要求修改 XML，也能兼容不同分支上的同名布局文件。
            normalizeModeLabels(rgType)
            ensurePbHexOption(rgType)

            var copyText: String
            jsonViewer.setJson(content)
            copyText = jsonViewer.getJSONString()

            rgType.setOnCheckedChangeListener { group: RadioGroup, checkedId: Int ->
                val selected = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
                when (selected.text.toString().trim().lowercase(Locale.ROOT)) {
                    "pb" -> {
                        jsonViewer.setJson(content)
                        tvContent.visibility = GONE
                        jsonViewer.visibility = VISIBLE
                        copyText = jsonViewer.getJSONString()
                    }

                    "pb (elem)" -> {
                        val extraction = runCatching {
                            PbMessageTools.extractElements(content, sourceCommand)
                        }.onFailure {
                            Logger.e("QQMessageFetcherResultDialog.extractElements", it)
                        }.getOrNull()

                        val extracted = extraction?.elements
                        when (val value = extracted?.value) {
                            is org.json.JSONArray -> jsonViewer.setJson(value)
                            is JSONObject -> jsonViewer.setJson(value)
                            else -> {
                                jsonViewer.setJson(
                                    JSONObject().apply {
                                        put("error", extraction?.error ?: "PB Elem 提取失败")
                                        put("sourceCommand", sourceCommand ?: JSONObject.NULL)
                                        put("response", content ?: JSONObject.NULL)
                                    },
                                )
                            }
                        }
                        tvContent.visibility = GONE
                        jsonViewer.visibility = VISIBLE
                        copyText = jsonViewer.getJSONString()
                    }

                    "pb (hex)" -> {
                        val hexText = runCatching {
                            PbMessageTools.buildResponseHexText(content, sourceCommand)
                        }.onFailure {
                            Logger.e("QQMessageFetcherResultDialog.pbHex", it)
                        }.getOrElse { error ->
                            "PB (HEX) 生成失败\n${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}"
                        }
                        tvContent.text = hexText
                        tvContent.visibility = VISIBLE
                        jsonViewer.visibility = GONE
                        copyText = hexText
                    }

                    "msgrecord" -> {
                        tvContent.text = formatMsgRecord(CacheConfig.getMsgRecord().toString())
                        tvContent.visibility = VISIBLE
                        jsonViewer.visibility = GONE
                        copyText = tvContent.text.toString()
                    }
                }
            }

            btnCopy.setOnClickListener {
                copyToClipboard(copyText)
                Toasts.info(context, "已复制")
            }

            // 长按仍保留完整导出：响应和各 Elem 的重编码 HEX/Base64。
            btnCopy.setOnLongClickListener {
                val report = runCatching {
                    PbMessageTools.buildReencodedExportReport(content, sourceCommand)
                }.onFailure {
                    Logger.e("QQMessageFetcherResultDialog.export", it)
                }.getOrElse { error ->
                    JSONObject().apply {
                        put("error", "导出失败：${error.javaClass.simpleName}: ${error.message}")
                    }
                }
                copyToClipboard(report.toString(2))
                Toasts.info(context, "已复制完整 PB 导出报告")
                true
            }
        }, 100)
    }

    private fun normalizeModeLabels(group: RadioGroup) {
        for (index in 0 until group.childCount) {
            val button = group.getChildAt(index) as? RadioButton ?: continue
            when (button.text.toString().trim().lowercase(Locale.ROOT)) {
                "pb" -> button.text = "PB"
                "pb (elem)" -> button.text = "PB (elem)"
                "msgrecord" -> button.text = "MsgRecord"
            }
        }
    }

    private fun ensurePbHexOption(group: RadioGroup) {
        val children = (0 until group.childCount)
            .mapNotNull { group.getChildAt(it) as? RadioButton }

        if (children.any { it.text.toString().trim().equals("PB (HEX)", ignoreCase = true) }) {
            return
        }

        val template = children.firstOrNull()
        val msgRecordIndex = (0 until group.childCount).firstOrNull { index ->
            (group.getChildAt(index) as? RadioButton)
                ?.text
                ?.toString()
                ?.trim()
                ?.equals("MsgRecord", ignoreCase = true) == true
        } ?: group.childCount

        val button = RadioButton(context).apply {
            id = View.generateViewId()
            text = "PB (HEX)"

            if (template != null) {
                setTextColor(template.textColors)
                textSize = pixelsToSp(template.textSize, resources.displayMetrics)
                typeface = template.typeface
                gravity = template.gravity
                buttonTintList = template.buttonTintList
                minHeight = template.minHeight
                minimumHeight = template.minimumHeight
                setPadding(
                    template.paddingLeft,
                    template.paddingTop,
                    template.paddingRight,
                    template.paddingBottom,
                )
            }
        }

        val templateParams = template?.layoutParams
        val params = RadioGroup.LayoutParams(
            templateParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            templateParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (templateParams is RadioGroup.LayoutParams) {
            params.weight = templateParams.weight
            params.gravity = templateParams.gravity
            params.setMargins(
                templateParams.leftMargin,
                templateParams.topMargin,
                templateParams.rightMargin,
                templateParams.bottomMargin,
            )
        }
        group.addView(button, msgRecordIndex, params)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
    }

    private fun pixelsToSp(pixels: Float, metrics: DisplayMetrics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, pixels, metrics)
        } else {
            legacyPixelsToSp(pixels, metrics)
        }

    @Suppress("DEPRECATION")
    private fun legacyPixelsToSp(pixels: Float, metrics: DisplayMetrics): Float =
        pixels / metrics.scaledDensity

    private fun formatMsgRecord(input: String): String {
        var indentLevel = 0
        val indentStep = "    "
        val result = StringBuilder()
        input.forEach { char ->
            when (char) {
                '{' -> {
                    result.append(char)
                    result.append("\n")
                    indentLevel++
                    result.append(indentStep.repeat(indentLevel))
                }

                '}' -> {
                    result.append("\n")
                    indentLevel = if (indentLevel > 0) indentLevel - 1 else 0
                    result.append(indentStep.repeat(indentLevel))
                    result.append(char)
                }

                ',' -> {
                    result.append(char)
                    result.append("\n")
                    result.append(indentStep.repeat(indentLevel))
                }

                else -> result.append(char)
            }
        }
        return result.toString()
    }

    override fun getImplLayoutId(): Int = R.layout.layout_qq_message_fetcher_result

    companion object {
        private var popupView: BasePopupView? = null
        private var content: JSONObject? = null
        private var sourceCommand: String? = null

        fun setSourceCommand(command: String) {
            sourceCommand = command
        }

        fun createView(context: Context, content: JSONObject) {
            val fixContext = CommonContextWrapper.createAppCompatContext(context)
            val newPop = XPopup.Builder(fixContext)
                .moveUpToKeyboard(true)
                .isDestroyOnDismiss(true)
            newPop.maxHeight((XPopupUtils.getScreenHeight(context) * .90f).toInt())
            newPop.popupHeight((XPopupUtils.getScreenHeight(context) * .90f).toInt())
            Companion.content = content

            ActionReporter.reportVisitor(
                AppRuntimeHelper.getAccount(),
                "CreateView-QQMessageFetcherResultDialog",
            )

            popupView = newPop.asCustom(QQMessageFetcherResultDialog(fixContext))
            popupView?.show()
        }
    }
}
