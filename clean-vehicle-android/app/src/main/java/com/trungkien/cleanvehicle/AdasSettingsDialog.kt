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

class AdasSettingsDialog(
    context: Context,
    private val licenseManager: AdasLicenseManager,
    private val voice: GoogleAdasVoice,
    private val technicalEnabled: Boolean,
    private val onTechnicalChanged: (Boolean) -> Unit,
    private val onLicenseActivated: () -> Unit,
) : Dialog(context) {
    private lateinit var status:
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
                    0.84f
                )
                .toInt(),
            (
                metrics.heightPixels *
                    0.94f
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
                        8,
                        15,
                        23,
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
                    dp(30),
                )
            }

        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )

        root.addView(
            title(
                "TrungKien ADAS",
                27f,
            )
        )

        root.addView(
            smallCenter(
                "CÀI ĐẶT"
            )
        )

        root.addView(
            section(
                "THIẾT BỊ & BẢN QUYỀN"
            )
        )

        status =
            body(
                ""
            ).apply {
                setTextColor(
                    Color.rgb(
                        38,
                        214,
                        178,
                    )
                )

                typeface =
                    Typeface.DEFAULT_BOLD
            }

        root.addView(
            status
        )

        root.addView(
            label(
                "MÃ THIẾT BỊ"
            )
        )

        val code =
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
                        25,
                        34,
                        43,
                    )
                )
            }

        root.addView(
            code,
            fullWidth()
        )

        root.addView(
            button(
                "SAO CHÉP MÃ THIẾT BỊ"
            ).apply {
                setOnClickListener {
                    copy(
                        licenseManager.deviceCode
                    )

                    status.text =
                        "ĐÃ SAO CHÉP MÃ THIẾT BỊ"
                }
            },
            buttonParams(
                10
            )
        )

        keyInput =
            EditText(
                context
            ).apply {
                hint =
                    "DÁN KEY TỪ ADMIN"

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
                        25,
                        34,
                        43,
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

        val keyRow =
            LinearLayout(
                context
            ).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        keyRow.addView(
            button(
                "DÁN KEY"
            ).apply {
                setOnClickListener {
                    val manager =
                        context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager

                    val clip =
                        manager.primaryClip

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

                        status.text =
                            "ĐÃ DÁN KEY"
                    }
                }
            },
            halfParams(
                right =
                    5
            )
        )

        keyRow.addView(
            button(
                "KÍCH HOẠT"
            ).apply {
                setOnClickListener {
                    val result =
                        licenseManager.activate(
                            keyInput.text
                                .toString()
                        )

                    status.text =
                        result.message

                    status.setTextColor(
                        if (
                            result.valid
                        ) {
                            Color.rgb(
                                60,
                                220,
                                125,
                            )
                        } else {
                            Color.rgb(
                                255,
                                105,
                                85,
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
            },
            halfParams(
                left =
                    5
            )
        )

        root.addView(
            keyRow
        )

        root.addView(
            section(
                "HIỂN THỊ"
            )
        )

        root.addView(
            button(
                if (
                    technicalEnabled
                ) {
                    "ẨN THÔNG TIN KỸ THUẬT"
                } else {
                    "HIỆN THÔNG TIN KỸ THUẬT"
                }
            ).apply {
                setOnClickListener {
                    onTechnicalChanged(
                        !technicalEnabled
                    )

                    dismiss()
                }
            },
            buttonParams(
                0
            )
        )

        root.addView(
            body(
                "Thông tin kỹ thuật dùng để kiểm tra raw lane vàng/cyan, horizon, hood và thông số AI. " +
                    "Khi sử dụng bình thường nên để ẩn.",
            ).apply {
                setPadding(
                    0,
                    dp(7),
                    0,
                    0,
                )
            }
        )

        root.addView(
            section(
                "NGHE THỬ CẢNH BÁO"
            )
        )

        root.addView(
            body(
                "Giọng ưu tiên: ${voice.engineLabel()}. Bấm từng câu để nghe thử đúng giọng sẽ dùng khi chạy.",
            )
        )

        addVoiceButton(
            root,
            "▶ HIỆU CHỈNH CAMERA THÀNH CÔNG"
        ) {
            voice.calibrationSuccess()
        }

        addVoiceButton(
            root,
            "▶ XE PHÍA TRƯỚC DI CHUYỂN"
        ) {
            voice.leadMoved()
        }

        addVoiceButton(
            root,
            "▶ NGUY CƠ VA CHẠM"
        ) {
            voice.collisionRisk()
        }

        addVoiceButton(
            root,
            "▶ KHOẢNG CÁCH QUÁ GẦN"
        ) {
            voice.headwayTooClose()
        }

        addVoiceButton(
            root,
            "▶ CHÚ Ý LỆCH LÀN"
        ) {
            voice.laneDeparture()
        }

        root.addView(
            section(
                "HƯỚNG DẪN SỬ DỤNG"
            )
        )

        addGuide(
            root,
            "1. Gắn điện thoại",
            "Đặt điện thoại nằm ngang, camera sau nhìn thẳng về phía trước. " +
                "Không để taplo hoặc vật khác che quá nhiều mặt đường.",
        )

        addGuide(
            root,
            "2. Mở ứng dụng",
            "Cho phép Camera và Vị trí, sau đó bật âm lượng Media đủ nghe. " +
                "Ứng dụng tự nhận diện xe, làn và tốc độ.",
        )

        addGuide(
            root,
            "3. Hiệu chỉnh camera",
            "Đi trên đoạn đường có vạch làn rõ. Ứng dụng tự học góc camera. " +
                "Khi đủ dữ liệu sẽ hiện và đọc: “Hiệu chỉnh camera thành công”.",
        )

        addGuide(
            root,
            "4. Xe phía trước",
            "Khung mục tiêu màu cam-đỏ là xe đang được bám làm xe phía trước. " +
                "Khi đổi làn, Smart Lead tự chuyển sang xe phù hợp ở làn mới.",
        )

        addGuide(
            root,
            "5. Cảnh báo khoảng cách",
            "≈ xx m là khoảng cách ước lượng. HMW dùng để nhắc bám xe quá gần. " +
                "TTC càng nhỏ thì nguy cơ va chạm càng cao và tiếng bíp càng dồn.",
        )

        addGuide(
            root,
            "6. Dừng đèn đỏ",
            "Khi xe bạn đứng yên và xe phía trước bắt đầu chạy, ứng dụng phát BÍP-BÍP rồi đọc: " +
                "“Xe phía trước di chuyển”.",
        )

        addGuide(
            root,
            "7. Lệch làn",
            "Khi đủ tốc độ và ứng dụng dự đoán xe đang trôi khỏi làn, biên làn nguy hiểm chuyển đỏ " +
                "và ứng dụng đọc: “Chú ý lệch làn”.",
        )

        root.addView(
            section(
                "Ý NGHĨA CẢNH BÁO"
            )
        )

        addGuide(
            root,
            "FCW",
            "Nguy cơ va chạm phía trước. TTC thấp sẽ làm tiếng bíp nhanh hơn.",
        )

        addGuide(
            root,
            "HMW",
            "Khoảng cách theo thời gian với xe trước quá ngắn, dù hai xe có thể đang chạy cùng tốc độ.",
        )

        addGuide(
            root,
            "LDW / TLC",
            "Ứng dụng theo dõi vị trí trong làn và dự đoán thời gian xe có thể cắt vạch.",
        )

        root.addView(
            warning(
                "LƯU Ý: Đây là ADAS thử nghiệm bằng camera điện thoại. Khoảng cách và thời gian cảnh báo là ước lượng. " +
                    "Luôn quan sát đường và không phụ thuộc hoàn toàn vào ứng dụng.",
            )
        )

        root.addView(
            button(
                "ĐÓNG CÀI ĐẶT"
            ).apply {
                setOnClickListener {
                    dismiss()
                }
            },
            buttonParams(
                20
            )
        )

        return scroll
    }

    private fun refreshStatus() {
        status.setTextColor(
            Color.rgb(
                38,
                214,
                178,
            )
        )

        status.text =
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

                "TRẠNG THÁI: DÙNG THỬ %02d:%02d".format(
                    Locale.US,
                    totalSeconds /
                        60L,
                    totalSeconds %
                        60L,
                )
            }
    }

    private fun addVoiceButton(
        root: LinearLayout,
        text: String,
        action: () -> Unit,
    ) {
        root.addView(
            button(
                text
            ).apply {
                setOnClickListener {
                    action()
                }
            },
            buttonParams(
                8
            )
        )
    }

    private fun addGuide(
        root: LinearLayout,
        heading: String,
        description: String,
    ) {
        root.addView(
            LinearLayout(
                context
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(13),
                    dp(11),
                    dp(13),
                    dp(11),
                )

                setBackgroundColor(
                    Color.rgb(
                        23,
                        31,
                        40,
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
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin =
                    dp(8)
            }
        )
    }

    private fun copy(
        text: String,
    ) {
        val manager =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        manager.setPrimaryClip(
            ClipData.newPlainText(
                "TrungKien ADAS Device Code",
                text,
            )
        )
    }

    private fun title(
        text: String,
        size: Float,
    ): TextView =
        TextView(
            context
        ).apply {
            this.text =
                text

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

    private fun smallCenter(
        text: String,
    ): TextView =
        body(
            text
        ).apply {
            gravity =
                Gravity.CENTER

            setTextColor(
                Color.rgb(
                    38,
                    214,
                    178,
                )
            )

            typeface =
                Typeface.DEFAULT_BOLD

            setPadding(
                0,
                dp(4),
                0,
                dp(6),
            )
        }

    private fun section(
        text: String,
    ): TextView =
        TextView(
            context
        ).apply {
            this.text =
                text

            textSize =
                17f

            setTextColor(
                Color.rgb(
                    255,
                    215,
                    45,
                )
            )

            typeface =
                Typeface.DEFAULT_BOLD

            setPadding(
                0,
                dp(21),
                0,
                dp(8),
            )
        }

    private fun label(
        text: String,
    ): TextView =
        body(
            text
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
        text: String,
    ): TextView =
        TextView(
            context
        ).apply {
            this.text =
                text

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

    private fun warning(
        text: String,
    ): TextView =
        body(
            text
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
                dp(18),
                0,
                0,
            )
        }

    private fun button(
        text: String,
    ): Button =
        Button(
            context
        ).apply {
            this.text =
                text

            setTextColor(
                Color.WHITE
            )

            setBackgroundColor(
                Color.rgb(
                    0,
                    121,
                    98,
                )
            )
        }

    private fun fullWidth() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun buttonParams(
        top: Int,
    ) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply {
            topMargin =
                dp(top)
        }

    private fun halfParams(
        left: Int = 0,
        right: Int = 0,
    ) =
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
