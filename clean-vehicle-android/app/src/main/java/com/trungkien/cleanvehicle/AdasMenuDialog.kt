package com.trungkien.cleanvehicle

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class AdasMenuDialog(
    context: Context,
    private val licenseManager: AdasLicenseManager,
    private val currentDebugMode: Boolean,
    private val onDebugChanged: (Boolean) -> Unit,
    private val onLicenseActivated: () -> Unit,
) : Dialog(context) {
    private lateinit var statusText:
        TextView

    private lateinit var keyInput:
        EditText

    init {
        requestWindowFeature(
            Window.FEATURE_NO_TITLE
        )

        setContentView(
            buildContent()
        )

        window?.setBackgroundDrawable(
            ColorDrawable(
                Color.TRANSPARENT
            )
        )
    }

    override fun onStart() {
        super.onStart()

        val metrics =
            context.resources.displayMetrics

        window?.setLayout(
            (
                metrics.widthPixels *
                    0.82f
                )
                .toInt(),
            (
                metrics.heightPixels *
                    0.92f
                )
                .toInt(),
        )

        refreshStatus()
    }

    private fun buildContent(): ScrollView {
        val scroll =
            ScrollView(
                context
            ).apply {
                isFillViewport =
                    true

                setBackgroundColor(
                    Color.rgb(
                        9,
                        15,
                        22,
                    )
                )
            }

        val root =
            LinearLayout(
                context
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(24),
                    dp(20),
                    dp(24),
                    dp(28),
                )
            }

        scroll.addView(
            root,
            ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )

        root.addView(
            title(
                "TRUNGKIEN ADAS V2.3",
                26f,
            )
        )

        root.addView(
            subtitle(
                "MENU"
            )
        )

        // -----------------------------------------------------
        // LICENSE / DEVICE
        // -----------------------------------------------------
        root.addView(
            sectionTitle(
                "THIẾT BỊ & BẢN QUYỀN"
            )
        )

        statusText =
            body(
                ""
            ).apply {
                setTextColor(
                    Color.rgb(
                        34,
                        211,
                        197,
                    )
                )

                typeface =
                    Typeface.DEFAULT_BOLD
            }

        root.addView(
            statusText
        )

        root.addView(
            smallLabel(
                "MÃ THIẾT BỊ"
            )
        )

        val deviceCode =
            TextView(
                context
            ).apply {
                text =
                    licenseManager.deviceCode

                textSize =
                    25f

                setTextColor(
                    Color.WHITE
                )

                typeface =
                    Typeface.MONOSPACE

                gravity =
                    Gravity.CENTER

                setTextIsSelectable(
                    true
                )

                setPadding(
                    dp(12),
                    dp(14),
                    dp(12),
                    dp(14),
                )

                setBackgroundColor(
                    Color.rgb(
                        26,
                        34,
                        42,
                    )
                )
            }

        root.addView(
            deviceCode,
            fullWidth()
        )

        val copyDevice =
            button(
                "SAO CHÉP MÃ THIẾT BỊ"
            )

        copyDevice.setOnClickListener {
            copyText(
                "TRUNGKIEN DEVICE CODE",
                licenseManager.deviceCode,
            )

            statusText.text =
                "ĐÃ SAO CHÉP MÃ THIẾT BỊ"
        }

        root.addView(
            copyDevice,
            buttonParams(10)
        )

        // Key can be entered before trial expires too.
        keyInput =
            EditText(
                context
            ).apply {
                hint =
                    "DÁN KEY TỪ ADMIN (nếu đã có)"

                setHintTextColor(
                    Color.GRAY
                )

                setTextColor(
                    Color.WHITE
                )

                textSize =
                    13f

                minLines =
                    3

                gravity =
                    Gravity.TOP or
                        Gravity.START

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12),
                )

                setBackgroundColor(
                    Color.rgb(
                        26,
                        34,
                        42,
                    )
                )
            }

        root.addView(
            keyInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(100),
            ).apply {
                topMargin =
                    dp(12)
            }
        )

        val keyActions =
            LinearLayout(
                context
            ).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val pasteKey =
            button(
                "DÁN KEY"
            )

        pasteKey.setOnClickListener {
            val clipboard =
                context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

            val clip =
                clipboard.primaryClip

            if (
                clip != null &&
                clip.itemCount > 0
            ) {
                keyInput.setText(
                    clip.getItemAt(0)
                        .coerceToText(
                            context
                        )
                        .toString()
                        .trim()
                )

                statusText.text =
                    "ĐÃ DÁN KEY"
            } else {
                statusText.text =
                    "CLIPBOARD ĐANG TRỐNG"
            }
        }

        val activate =
            button(
                "KÍCH HOẠT"
            )

        activate.setOnClickListener {
            val result =
                licenseManager.activate(
                    keyInput.text
                        .toString()
                )

            statusText.text =
                result.message

            statusText.setTextColor(
                if (
                    result.valid
                ) {
                    Color.rgb(
                        50,
                        220,
                        120,
                    )
                } else {
                    Color.rgb(
                        255,
                        110,
                        90,
                    )
                }
            )

            if (
                result.valid
            ) {
                onLicenseActivated()
                dismiss()
            }
        }

        keyActions.addView(
            pasteKey,
            halfButtonParams(
                right =
                    5
            )
        )

        keyActions.addView(
            activate,
            halfButtonParams(
                left =
                    5
            )
        )

        root.addView(
            keyActions
        )

        // -----------------------------------------------------
        // DISPLAY MODE
        // -----------------------------------------------------
        root.addView(
            sectionTitle(
                "CHẾ ĐỘ HIỂN THỊ"
            )
        )

        val debugToggle =
            button(
                if (
                    currentDebugMode
                ) {
                    "CHUYỂN SANG DRIVE"
                } else {
                    "CHUYỂN SANG DEBUG"
                }
            )

        debugToggle.setOnClickListener {
            onDebugChanged(
                !currentDebugMode
            )

            dismiss()
        }

        root.addView(
            debugToggle,
            buttonParams(0)
        )

        // -----------------------------------------------------
        // QUICK START
        // -----------------------------------------------------
        root.addView(
            sectionTitle(
                "HƯỚNG DẪN NHANH CHO NGƯỜI MỚI"
            )
        )

        root.addView(
            guideCard(
                "1. GẮN ĐIỆN THOẠI",
                "Đặt điện thoại nằm ngang, camera sau nhìn thẳng về phía trước. " +
                    "Không để cần gạt, taplo hoặc vật khác che phần lớn mặt đường.",
            )
        )

        root.addView(
            guideCard(
                "2. MỞ APP",
                "Cho phép CAMERA và VỊ TRÍ. Bật âm lượng Media đủ nghe. " +
                    "Không cần tự căn bằng tay.",
            )
        )

        root.addView(
            guideCard(
                "3. CHỜ APP TỰ CĂN",
                "Đi trên đoạn đường có vạch làn rõ. Khi góc trên hiện CAL, " +
                    "app đã có đủ mẫu để tự căn camera. Nếu chưa CAL vẫn dùng được nhưng khoảng cách/làn có thể kém chính xác hơn.",
            )
        )

        root.addView(
            guideCard(
                "4. XE PHÍA TRƯỚC",
                "Khung đỏ là xe app đang chọn làm XE PHÍA TRƯỚC. " +
                    "Khung xanh là các xe khác. Khi đổi làn, Smart Lead sẽ nhả xe cũ và bám xe phía trước của làn mới.",
            )
        )

        root.addView(
            guideCard(
                "5. KHOẢNG CÁCH & TTC",
                "Dòng ≈ xx m là khoảng cách ước lượng. TTC là số giây ước lượng còn lại nếu khoảng cách đang giảm. " +
                    "TTC càng nhỏ thì tiếng bíp càng nhanh.",
            )
        )

        root.addView(
            guideCard(
                "6. DỪNG ĐÈN ĐỎ",
                "Khi xe bạn đứng yên và xe phía trước bắt đầu chạy, app phát BÍP-BÍP rồi Google TTS đọc: " +
                    "“Xe phía trước di chuyển”.",
            )
        )

        root.addView(
            guideCard(
                "7. LỆCH LÀN",
                "Ở tốc độ đủ cao, nếu app dự đoán xe đang trôi ra khỏi làn, app bíp và đọc “Chú ý lệch làn”.",
            )
        )

        // -----------------------------------------------------
        // ALERT EXPLANATION
        // -----------------------------------------------------
        root.addView(
            sectionTitle(
                "CÁC CẢNH BÁO CÓ NGHĨA GÌ?"
            )
        )

        root.addView(
            guideCard(
                "FCW • NGUY CƠ VA CHẠM",
                "Xe phía trước đang tiến lại gần nhanh. TTC thấp sẽ bíp dồn hơn. " +
                    "Mức cao có Google TTS: “Nguy cơ va chạm”.",
            )
        )

        root.addView(
            guideCard(
                "HMW • BÁM XE QUÁ GẦN",
                "Hai xe có thể đang chạy cùng tốc độ nhưng khoảng cách theo thời gian quá ngắn. " +
                    "Đây là cảnh báo giữ khoảng cách, không đồng nghĩa chắc chắn sắp va chạm.",
            )
        )

        root.addView(
            guideCard(
                "LDW / TLC • LỆCH LÀN",
                "App theo dõi vị trí xe so với hai vạch làn và xu hướng dịch ngang để cảnh báo trước khi cắt vạch.",
            )
        )

        root.addView(
            sectionTitle(
                "CẤP KEY"
            )
        )

        root.addView(
            body(
                "Bạn được dùng thử 5 phút thực tế. Khi hết thời gian, app hiện lại chính MÃ THIẾT BỊ ở trên. " +
                    "Bấm SAO CHÉP MÃ THIẾT BỊ → gửi Admin → nhận key → DÁN KEY → KÍCH HOẠT.",
            )
        )

        root.addView(
            warning(
                "LƯU Ý: Khoảng cách, TTC, HMW và cảnh báo là ước lượng bằng camera điện thoại. " +
                    "Luôn quan sát đường và tự chịu trách nhiệm điều khiển xe; không phụ thuộc hoàn toàn vào app.",
            )
        )

        val close =
            button(
                "ĐÓNG MENU"
            )

        close.setOnClickListener {
            dismiss()
        }

        root.addView(
            close,
            buttonParams(20)
        )

        return scroll
    }

    private fun refreshStatus() {
        statusText.setTextColor(
            Color.rgb(
                34,
                211,
                197,
            )
        )

        statusText.text =
            if (
                licenseManager.isLicensed()
            ) {
                "TRẠNG THÁI: " +
                    licenseManager.licenseSummary()
            } else {
                val totalSeconds =
                    (
                        licenseManager.remainingTrialMs() +
                            999L
                        ) /
                        1000L

                "TRẠNG THÁI: TRIAL %02d:%02d".format(
                    Locale.US,
                    totalSeconds / 60L,
                    totalSeconds % 60L,
                )
            }
    }

    private fun copyText(
        label: String,
        value: String,
    ) {
        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                label,
                value,
            )
        )
    }

    private fun title(
        value: String,
        size: Float,
    ): TextView =
        TextView(
            context
        ).apply {
            text =
                value

            textSize =
                size

            setTextColor(
                Color.WHITE
            )

            typeface =
                Typeface.DEFAULT_BOLD

            gravity =
                Gravity.CENTER
        }

    private fun subtitle(
        value: String,
    ): TextView =
        TextView(
            context
        ).apply {
            text =
                value

            textSize =
                15f

            setTextColor(
                Color.rgb(
                    34,
                    211,
                    197,
                )
            )

            typeface =
                Typeface.DEFAULT_BOLD

            gravity =
                Gravity.CENTER

            setPadding(
                0,
                dp(4),
                0,
                dp(10),
            )
        }

    private fun sectionTitle(
        value: String,
    ): TextView =
        TextView(
            context
        ).apply {
            text =
                value

            textSize =
                17f

            setTextColor(
                Color.rgb(
                    255,
                    225,
                    0,
                )
            )

            typeface =
                Typeface.DEFAULT_BOLD

            setPadding(
                0,
                dp(22),
                0,
                dp(9),
            )
        }

    private fun smallLabel(
        value: String,
    ): TextView =
        body(
            value
        ).apply {
            typeface =
                Typeface.DEFAULT_BOLD

            setPadding(
                0,
                dp(12),
                0,
                dp(7),
            )
        }

    private fun body(
        value: String,
    ): TextView =
        TextView(
            context
        ).apply {
            text =
                value

            textSize =
                14f

            setTextColor(
                Color.rgb(
                    210,
                    215,
                    220,
                )
            )
        }

    private fun guideCard(
        heading: String,
        description: String,
    ): LinearLayout =
        LinearLayout(
            context
        ).apply {
            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12),
            )

            setBackgroundColor(
                Color.rgb(
                    24,
                    31,
                    39,
                )
            )

            addView(
                body(
                    heading
                ).apply {
                    typeface =
                        Typeface.DEFAULT_BOLD

                    setTextColor(
                        Color.WHITE
                    )
                }
            )

            addView(
                body(
                    description
                ).apply {
                    setPadding(
                        0,
                        dp(5),
                        0,
                        0,
                    )
                }
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin =
                        dp(8)
                }
        }

    private fun warning(
        value: String,
    ): TextView =
        body(
            value
        ).apply {
            setTextColor(
                Color.rgb(
                    255,
                    193,
                    7,
                )
            )

            setPadding(
                0,
                dp(20),
                0,
                0,
            )
        }

    private fun button(
        value: String,
    ): Button =
        Button(
            context
        ).apply {
            text =
                value

            setTextColor(
                Color.WHITE
            )

            setBackgroundColor(
                Color.rgb(
                    0,
                    125,
                    100,
                )
            )
        }

    private fun fullWidth():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun buttonParams(
        top: Int,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply {
            topMargin =
                dp(top)
        }

    private fun halfButtonParams(
        left: Int = 0,
        right: Int = 0,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            dp(52),
            1f,
        ).apply {
            leftMargin =
                dp(left)

            rightMargin =
                dp(right)

            topMargin =
                dp(10)
        }

    private fun dp(
        value: Int,
    ): Int =
        (
            value *
                context.resources.displayMetrics.density
            )
            .toInt()
}
