package com.trungkien.cleanvehicle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AdasLicenseGateView(
    context: Context,
    private val licenseManager: AdasLicenseManager,
    private val onActivated: () -> Unit,
) : ScrollView(context) {
    private val keyInput:
        EditText

    private lateinit var message:
        TextView

    init {
        isFillViewport =
            true

        setBackgroundColor(
            Color.rgb(
                5,
                8,
                12,
            )
        )

        val root =
            LinearLayout(
                context
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    dp(32),
                    dp(36),
                    dp(32),
                    dp(40),
                )
            }

        addView(
            root,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )

        root.addView(
            title(
                "TRUNGKIEN ADAS V2.2",
                28f,
                Color.WHITE,
            )
        )

        root.addView(
            title(
                "HẾT THỜI GIAN DÙNG THỬ 5 PHÚT",
                20f,
                Color.rgb(
                    255,
                    100,
                    80,
                ),
            ).apply {
                setPadding(
                    0,
                    dp(14),
                    0,
                    dp(8),
                )
            }
        )

        root.addView(
            body(
                "Gửi MÃ THIẾT BỊ bên dưới cho Admin để cấp key. " +
                    "Sau khi nhận key, dán key và bấm KÍCH HOẠT.",
            )
        )

        root.addView(
            body(
                "MÃ THIẾT BỊ"
            ).apply {
                setPadding(
                    0,
                    dp(24),
                    0,
                    dp(8),
                )
            }
        )

        val deviceCodeView =
            TextView(
                context
            ).apply {
                text =
                    licenseManager.deviceCode

                textSize =
                    28f

                setTextColor(
                    Color.rgb(
                        34,
                        211,
                        197,
                    )
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
                    dp(18),
                    dp(12),
                    dp(18),
                )

                setBackgroundColor(
                    Color.rgb(
                        25,
                        31,
                        37,
                    )
                )
            }

        root.addView(
            deviceCodeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        val copyButton =
            button(
                "SAO CHÉP MÃ THIẾT BỊ"
            )

        copyButton.setOnClickListener {
            val clipboard =
                context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "TRUNGKIEN DEVICE CODE",
                    licenseManager.deviceCode,
                )
            )

            message.text =
                "ĐÃ SAO CHÉP MÃ THIẾT BỊ"
        }

        root.addView(
            copyButton,
            buttonParams(
                12
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
                    14f

                minLines =
                    4

                gravity =
                    Gravity.TOP or
                        Gravity.START

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14),
                )

                setBackgroundColor(
                    Color.rgb(
                        25,
                        31,
                        37,
                    )
                )
            }

        root.addView(
            keyInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(130),
            ).apply {
                topMargin =
                    dp(22)
            }
        )

        val pasteButton =
            button(
                "DÁN KEY"
            )

        pasteButton.setOnClickListener {
            runCatching {
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

                    message.text =
                        "ĐÃ DÁN KEY"
                }
            }
        }

        root.addView(
            pasteButton,
            buttonParams(
                10
            )
        )

        val activateButton =
            button(
                "KÍCH HOẠT"
            ).apply {
                textSize =
                    18f
            }

        activateButton.setOnClickListener {
            val result =
                licenseManager.activate(
                    keyInput.text
                        .toString()
                )

            message.text =
                result.message

            message.setTextColor(
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
                        100,
                        80,
                    )
                }
            )

            if (
                result.valid
            ) {
                postDelayed(
                    {
                        onActivated()
                    },
                    650L,
                )
            }
        }

        root.addView(
            activateButton,
            buttonParams(
                10
            )
        )

        message =
            body(
                "Admin dùng TRUNGKIEN ADMIN KEY V1.1 để tạo key."
            ).apply {
                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(18),
                    0,
                    0,
                )
            }

        root.addView(
            message
        )
    }

    private fun title(
        value: String,
        size: Float,
        color: Int,
    ): TextView =
        TextView(
            context
        ).apply {
            text =
                value

            textSize =
                size

            setTextColor(
                color
            )

            gravity =
                Gravity.CENTER

            typeface =
                Typeface.DEFAULT_BOLD
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
                    205,
                    210,
                    215,
                )
            )

            gravity =
                Gravity.CENTER
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

    private fun buttonParams(
        topDp: Int,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54),
        ).apply {
            topMargin =
                dp(topDp)
        }

    private fun dp(
        value: Int,
    ): Int =
        (
            value *
                resources.displayMetrics.density
            )
            .toInt()
}
