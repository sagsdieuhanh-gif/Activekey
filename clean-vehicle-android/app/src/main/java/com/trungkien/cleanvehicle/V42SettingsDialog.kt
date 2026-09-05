package com.trungkien.cleanvehicle

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class V42SettingsDialog(
    context: Context,
    private val licenseManager: AdasLicenseManager,
    private val voice: GoogleAdasVoice,
    initialConfig: AdasFeatureConfig,
    private val metricsProvider: () -> String,
    private val onConfigChanged: (AdasFeatureConfig) -> Unit,
    private val onLicenseActivated: () -> Unit,
    private val onBlackScreenCooling: () -> Unit,
) : Dialog(context) {
    private var config = initialConfig
    private lateinit var status: TextView
    private lateinit var keyInput: EditText
    private lateinit var pager: HorizontalScrollView
    private lateinit var pageStrip: LinearLayout
    private var pageWidth = 0
    private val gap by lazy { dp(12) }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(build())
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onStart() {
        super.onStart()
        val dm = context.resources.displayMetrics
        window?.setLayout(
            (dm.widthPixels * .94f).toInt(),
            (dm.heightPixels * .92f).toInt(),
        )
        refresh()
    }

    private fun build(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.rgb(6, 14, 22), 24)
            setPadding(dp(18), dp(13), dp(18), dp(13))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleBox.addView(title("TrungKien ADAS", 23f, Color.WHITE))
        titleBox.addView(
            body("V4.3.1  •  VUỐT NGANG ĐỂ CHUYỂN TRANG").apply {
                textSize = 11.5f
                setTextColor(Color.rgb(82, 232, 197))
            }
        )

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, -2, 1f),
        )

        header.addView(
            action("✕", compact = true).apply {
                textSize = 18f
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(dp(48), dp(42)),
        )

        root.addView(header)

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, dp(9))
        }

        val labels = listOf(
            "LÁI XE",
            "AI / HỆ THỐNG",
            "BẢN QUYỀN",
            "ÂM THANH",
        )

        for (i in labels.indices) {
            tabs.addView(
                chip(labels[i]).apply {
                    setOnClickListener { goPage(i) }
                },
                LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (i > 0) leftMargin = dp(7)
                },
            )
        }

        root.addView(tabs)

        pager = HorizontalScrollView(context).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        pageStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        pager.addView(
            pageStrip,
            ViewGroup.LayoutParams(-2, -1),
        )

        val dm = context.resources.displayMetrics
        pageWidth = (dm.widthPixels * .76f).toInt()

        addPage(drivePage())
        addPage(systemPage())
        addPage(licensePage())
        addPage(voicePage())

        root.addView(
            pager,
            LinearLayout.LayoutParams(-1, 0, 1f),
        )

        return root
    }

    private fun addPage(view: View) {
        pageStrip.addView(
            view,
            LinearLayout.LayoutParams(pageWidth, -1).apply {
                rightMargin = gap
            },
        )
    }

    private fun goPage(index: Int) {
        pager.post {
            pager.smoothScrollTo(index * (pageWidth + gap), 0)
        }
    }

    private fun drivePage(): View {
        val root = page(
            "LÁI XE",
            "Các điều khiển cần thiết khi chạy.",
        )

        toggle(
            root,
            "SPC Lead",
            "Khoảng cách lead + TTC/HMW/FCW.",
            config.supercomboLead,
        ) {
            save(config.copy(supercomboLead = it))
        }

        toggle(
            root,
            "UFLD Lane",
            "Vạch xanh lấy từ UFLD; không dùng SPC lane để vẽ.",
            config.ufld,
        ) {
            save(config.copy(ufld = it))
        }

        toggle(
            root,
            "FCW + HMW",
            "Va chạm và khoảng cách quá gần.",
            config.fcwHmw,
        ) {
            save(config.copy(fcwHmw = it))
        }

        toggle(
            root,
            "LDW + TLC",
            "Lệch làn và thời gian cắt làn.",
            config.ldwTlc,
        ) {
            save(config.copy(ldwTlc = it))
        }

        toggle(
            root,
            "Bảo vệ nhiệt",
            "Giảm tải helper khi máy nóng; SPC temporal vẫn liên tục.",
            config.thermalProtection,
        ) {
            save(config.copy(thermalProtection = it))
        }

        root.addView(
            note("SPC = path/distance  •  UFLD = lane  •  SSD = loại phương tiện"),
            lp(9),
        )

        return root
    }

    private fun systemPage(): View {
        val root = page(
            "AI / HỆ THỐNG",
            "Theo dõi model, nhiệt và cập nhật.",
        )

        toggle(
            root,
            "Thông tin kỹ thuật",
            "FPS, pair, calibration, UFLD, SSD.",
            config.technicalInfo,
        ) {
            save(config.copy(technicalInfo = it))
        }

        val metrics = body(metricsProvider()).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            background = round(Color.rgb(10, 27, 36), 15)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxLines = 8
        }

        root.addView(
            metrics,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(8)
            },
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        actions.addView(
            action("LÀM MỚI").apply {
                setOnClickListener {
                    metrics.text = metricsProvider()
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )

        actions.addView(
            action("CẬP NHẬT").apply {
                setOnClickListener {
                    (context as? Activity)?.let { activity ->
                        AdasAutoUpdater.checkNow(activity)
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(7)
            },
        )

        actions.addView(
            action("MÀN ĐEN").apply {
                setOnClickListener {
                    dismiss()
                    onBlackScreenCooling()
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(7)
            },
        )

        root.addView(actions, lp(9))
        return root
    }

    private fun licensePage(): View {
        val root = page(
            "THIẾT BỊ & BẢN QUYỀN",
            "Mã thiết bị và key kích hoạt.",
        )

        status = body("").apply {
            textSize = 12.5f
            setTextColor(Color.rgb(108, 238, 207))
        }
        root.addView(status)

        root.addView(
            title(
                licenseManager.deviceCode,
                20f,
                Color.WHITE,
            ).apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                background = round(Color.rgb(11, 29, 40), 15)
                setTextIsSelectable(true)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            },
            lp(8),
        )

        root.addView(
            action("SAO CHÉP MÃ THIẾT BỊ").apply {
                setOnClickListener {
                    val cm = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText(
                            "Device",
                            licenseManager.deviceCode,
                        )
                    )
                }
            },
            LinearLayout.LayoutParams(-1, dp(42)).apply {
                topMargin = dp(7)
            },
        )

        keyInput = EditText(context).apply {
            hint = "DÁN KEY TỪ ADMIN"
            setHintTextColor(Color.rgb(116, 133, 145))
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 3
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = round(Color.rgb(11, 29, 40), 15)
            setPadding(dp(11), dp(8), dp(11), dp(8))
        }

        root.addView(
            keyInput,
            LinearLayout.LayoutParams(-1, dp(70)).apply {
                topMargin = dp(8)
            },
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            action("DÁN KEY").apply {
                setOnClickListener {
                    val cm = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                    cm.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.let {
                            keyInput.setText(
                                it.getItemAt(0)
                                    .coerceToText(context)
                                    .toString()
                                    .trim()
                            )
                        }
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )

        row.addView(
            action("KÍCH HOẠT", accent = true).apply {
                setOnClickListener {
                    val result = licenseManager.activate(
                        keyInput.text.toString()
                    )
                    status.text = result.message
                    if (result.valid) {
                        onLicenseActivated()
                        dismiss()
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(8)
            },
        )

        root.addView(row, lp(8))
        return root
    }

    private fun voicePage(): View {
        val root = page(
            "ÂM THANH & THÔNG TIN",
            "Kiểm tra giọng cảnh báo và kiến trúc V4.",
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            action("VA CHẠM").apply {
                setOnClickListener { voice.collisionRisk() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )

        row.addView(
            action("QUÁ GẦN").apply {
                setOnClickListener { voice.headwayTooClose() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(7)
            },
        )

        row.addView(
            action("LỆCH LÀN").apply {
                setOnClickListener { voice.laneDeparture() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(7)
            },
        )

        root.addView(row, lp(8))

        root.addView(
            infoCard(
                "V4.3.1 CRASH-SAFE",
                "SPC và UFLD khởi động trước. SSD nhận dạng loại phương tiện nạp trễ, CPU 1 thread; nếu SSD lỗi thì app vẫn tiếp tục chạy SPC + UFLD.",
            ),
            lp(12),
        )

        root.addView(
            infoCard(
                "BOX NHẬN DIỆN",
                "Ô TÔ / XE MÁY / XE TẢI / XE BUÝT / XE ĐẠP / NGƯỜI. Chỉ box khớp VERIFIED SPC lead mới gắn khoảng cách.",
            ),
            lp(8),
        )

        root.addView(
            note(
                "Camera đơn là hệ thống thử nghiệm, không thay thế ADAS được chứng nhận."
            ),
            lp(12),
        )

        root.addView(
            action("ĐÓNG CÀI ĐẶT", accent = true).apply {
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(12)
            },
        )

        return root
    }

    private fun page(
        pageTitle: String,
        subTitle: String,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.rgb(9, 24, 33), 21)
            setPadding(dp(15), dp(13), dp(15), dp(13))
            addView(title(pageTitle, 17f, Color.WHITE))
            addView(
                body(subTitle).apply {
                    textSize = 11.5f
                    setTextColor(Color.rgb(135, 157, 170))
                    setPadding(0, dp(2), 0, dp(8))
                }
            )
        }

    private fun toggle(
        root: LinearLayout,
        titleText: String,
        description: String,
        checked: Boolean,
        callback: (Boolean) -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Color.rgb(13, 34, 44), 15)
            setPadding(dp(11), dp(7), dp(7), dp(7))
        }

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        textBox.addView(title(titleText, 13f, Color.WHITE))
        textBox.addView(
            body(description).apply {
                textSize = 10.5f
                maxLines = 2
            }
        )

        row.addView(
            textBox,
            LinearLayout.LayoutParams(0, -2, 1f),
        )

        val sw = Switch(context).apply {
            isChecked = checked
            scaleX = .82f
            scaleY = .82f
        }

        sw.setOnCheckedChangeListener { _, value ->
            callback(value)
        }

        row.addView(sw)

        root.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            },
        )
    }

    private fun infoCard(
        heading: String,
        text: String,
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.rgb(10, 40, 43), 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(
                title(
                    heading,
                    13.5f,
                    Color.rgb(91, 238, 205),
                )
            )
            addView(
                body(text).apply {
                    textSize = 11.5f
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }

    private fun save(value: AdasFeatureConfig) {
        config = value.copy(
            yolox = false,
            supercombo = true,
            fusionSmartLead = false,
            distanceMode = LeadDistanceMode.SUPERCOMBO,
        )
        onConfigChanged(config)
    }

    private fun refresh() {
        status.text =
            if (licenseManager.isLicensed()) {
                "TRẠNG THÁI  •  ${licenseManager.licenseSummary()}"
            } else {
                val seconds =
                    (licenseManager.remainingTrialMs() + 999) / 1000
                "DÙNG THỬ  •  %02d:%02d".format(
                    Locale.US,
                    seconds / 60,
                    seconds % 60,
                )
            }
    }

    private fun chip(textValue: String): TextView =
        TextView(context).apply {
            text = textValue
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(197, 216, 224))
            typeface = Typeface.DEFAULT_BOLD
            background = round(Color.rgb(14, 39, 49), 14)
        }

    private fun action(
        textValue: String,
        accent: Boolean = false,
        compact: Boolean = false,
    ): TextView =
        TextView(context).apply {
            text = textValue
            textSize = if (compact) 14f else 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = round(
                if (accent) {
                    Color.rgb(0, 145, 121)
                } else {
                    Color.rgb(15, 57, 67)
                },
                14,
            )
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }

    private fun title(
        value: String,
        size: Float,
        color: Int,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun body(value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 12f
            setTextColor(Color.rgb(196, 209, 217))
        }

    private fun note(value: String): TextView =
        body(value).apply {
            textSize = 11f
            setTextColor(Color.rgb(255, 193, 92))
        }

    private fun round(
        color: Int,
        radiusDp: Int = 18,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun lp(top: Int = 7): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
