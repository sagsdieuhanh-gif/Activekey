package com.trungkien.cleanvehicle

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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class V3SettingsDialog(
    context: Context,
    private val licenseManager: AdasLicenseManager,
    private val voice: GoogleAdasVoice,
    initialConfig: AdasFeatureConfig,
    private val metricsProvider: () -> String,
    private val onConfigChanged: (AdasFeatureConfig) -> Unit,
    private val onLicenseActivated: () -> Unit,
) : Dialog(context) {
    private var config = initialConfig
    private lateinit var statusText: TextView
    private lateinit var keyInput: EditText
    private lateinit var metricsText: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(build())
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onStart() {
        super.onStart()
        val dm=context.resources.displayMetrics
        window?.setLayout((dm.widthPixels*0.88f).toInt(),(dm.heightPixels*0.95f).toInt())
        refreshLicense()
    }

    private fun build(): View {
        val scroll=ScrollView(context).apply {
            isFillViewport=true
            setBackgroundColor(Color.rgb(5,12,20))
        }
        val root=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(24),dp(20),dp(24),dp(30))
        }
        scroll.addView(root,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(context).apply {
            text="TrungKien ADAS"
            textSize=28f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(context).apply {
            text="V3 • AI LAB"
            textSize=14f; setTextColor(Color.rgb(56,232,194)); typeface=Typeface.DEFAULT_BOLD
            setPadding(0,dp(2),0,dp(10))
        })

        root.addView(section("PHÒNG THỬ AI"))
        root.addView(body("Chọn 1 cấu hình để chạy cùng một cung đường. Mục tiêu là đo rõ Supercombo giúp hay làm giảm hiệu năng."))

        val presets=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
        presets.addView(presetButton("BASELINE\nV2.4",Color.rgb(34,91,125)){ applyPreset(AdasFeatureConfig.baseline()) },weight())
        presets.addView(presetButton("SUPERCOMBO\nONLY",Color.rgb(90,64,155)){ applyPreset(AdasFeatureConfig.supercombo()) },weight(8))
        presets.addView(presetButton("HYBRID\nFUSION",Color.rgb(0,126,103)){ applyPreset(AdasFeatureConfig.hybrid()) },weight(8))
        root.addView(presets,LinearLayout.LayoutParams(-1,dp(72)).apply{topMargin=dp(10)})

        root.addView(section("BẬT / TẮT TỪNG THÀNH PHẦN"))
        addToggle(root,"YOLOX-Tiny","Nhận diện xe + bounding box + tracking.",config.yolox){ update(config.copy(yolox=it)) }
        addToggle(root,"UFLD Lane","Lane model đang dùng từ V2.4.",config.ufld){ update(config.copy(ufld=it)) }
        addToggle(root,"Supercombo Engine","Bật model driving Supercombo và luồng inference riêng.",config.supercombo){ update(config.copy(supercombo=it)) }
        addToggle(root,"Supercombo Path / Lane","Hiển thị path, lane, road edge và dùng lane geometry Supercombo.",config.supercomboLanePath){ update(config.copy(supercomboLanePath=it,supercombo=config.supercombo||it)) }
        addToggle(root,"Supercombo Lead","Hiện lead advisor do Supercombo dự đoán.",config.supercomboLead){ update(config.copy(supercomboLead=it,supercombo=config.supercombo||it)) }
        addToggle(root,"Fusion Smart Lead","Cho Supercombo lead distance tham gia chấm điểm Smart Lead YOLOX.",config.fusionSmartLead){ update(config.copy(fusionSmartLead=it,supercombo=config.supercombo||it,supercomboLead=config.supercomboLead||it)) }
        addToggle(root,"FCW + HMW","Bíp/TTS nguy cơ va chạm và bám xe quá gần.",config.fcwHmw){ update(config.copy(fcwHmw=it)) }
        addToggle(root,"LDW + TLC","Bíp/TTS cảnh báo lệch làn.",config.ldwTlc){ update(config.copy(ldwTlc=it)) }
        addToggle(root,"Thông tin kỹ thuật","Hiện raw UFLD, horizon và số ms Supercombo.",config.technicalInfo){ update(config.copy(technicalInfo=it)) }

        root.addView(section("ĐO HIỆU QUẢ"))
        metricsText=body(metricsProvider()).apply {
            setTextColor(Color.rgb(174,228,220)); typeface=Typeface.MONOSPACE
            background=rounded(Color.rgb(12,27,36),18f)
            setPadding(dp(14),dp(12),dp(14),dp(12))
        }
        root.addView(metricsText,full())
        root.addView(action("LÀM MỚI THÔNG SỐ").apply {
            setOnClickListener { metricsText.text=metricsProvider() }
        },buttonParams())

        root.addView(section("THIẾT BỊ & BẢN QUYỀN"))
        statusText=body("")
        root.addView(statusText)
        root.addView(TextView(context).apply {
            text=licenseManager.deviceCode; textSize=24f; setTextColor(Color.WHITE)
            typeface=Typeface.MONOSPACE; gravity=Gravity.CENTER; setTextIsSelectable(true)
            background=rounded(Color.rgb(13,28,38),18f)
            setPadding(dp(12),dp(14),dp(12),dp(14))
        },full())
        root.addView(action("SAO CHÉP MÃ THIẾT BỊ").apply {
            setOnClickListener {
                val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TrungKien ADAS Device Code",licenseManager.deviceCode))
                statusText.text="ĐÃ SAO CHÉP MÃ THIẾT BỊ"
            }
        },buttonParams())

        keyInput=EditText(context).apply {
            hint="DÁN KEY TỪ ADMIN"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize=13f
            minLines=2; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background=rounded(Color.rgb(13,28,38),18f); setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        root.addView(keyInput,LinearLayout.LayoutParams(-1,dp(86)).apply{topMargin=dp(10)})

        val keyRow=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
        keyRow.addView(action("DÁN KEY").apply {
            setOnClickListener {
                val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.takeIf{it.itemCount>0}?.let{
                    keyInput.setText(it.getItemAt(0).coerceToText(context).toString().trim())
                }
            }
        },weight())
        keyRow.addView(action("KÍCH HOẠT").apply {
            setOnClickListener {
                val r=licenseManager.activate(keyInput.text.toString())
                statusText.text=r.message
                if(r.valid){onLicenseActivated();dismiss()}
            }
        },weight(8))
        root.addView(keyRow,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(8)})

        root.addView(section("NGHE THỬ GOOGLE TTS"))
        val voiceRow1=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
        voiceRow1.addView(smallAction("HIỆU CHỈNH"){voice.calibrationSuccess()},weight())
        voiceRow1.addView(smallAction("XE TRƯỚC ĐI"){voice.leadMoved()},weight(8))
        root.addView(voiceRow1,LinearLayout.LayoutParams(-1,dp(52)))
        val voiceRow2=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
        voiceRow2.addView(smallAction("VA CHẠM"){voice.collisionRisk()},weight())
        voiceRow2.addView(smallAction("QUÁ GẦN"){voice.headwayTooClose()},weight(8))
        voiceRow2.addView(smallAction("LỆCH LÀN"){voice.laneDeparture()},weight(8))
        root.addView(voiceRow2,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(7)})

        root.addView(section("CÁCH TEST V3"))
        root.addView(infoCard("1 • BASELINE","Chạy cùng đoạn đường với YOLOX + UFLD. Ghi cảm nhận độ ổn định lane, lead và nhiệt máy."))
        root.addView(infoCard("2 • SUPERCOMBO","Chạy lại cùng đoạn đường. Quan sát path cong, road edge, lead advisor và số ms SC."))
        root.addView(infoCard("3 • HYBRID","Chạy lần ba. Smart Lead dùng YOLOX tracking + hint Supercombo; lane dùng dữ liệu kết hợp khi cả hai hợp lệ."))
        root.addView(infoCard("4 • SO SÁNH","Nếu Supercombo làm lane/lead tốt hơn nhưng inference quá chậm, ta giảm tần suất hoặc chỉ dùng Lead/Path cần thiết ở bản sau."))

        root.addView(TextView(context).apply {
            text="V3 AI LAB là bản thử nghiệm. Supercombo dùng camera đơn và preprocessing điện thoại xấp xỉ openpilot; không coi khoảng cách/TTC là đo lường an toàn được chứng nhận."
            textSize=13f; setTextColor(Color.rgb(255,190,72)); setPadding(0,dp(16),0,0)
        })
        root.addView(action("ĐÓNG CÀI ĐẶT").apply{setOnClickListener{dismiss()}},buttonParams(18))
        return scroll
    }

    private fun applyPreset(v:AdasFeatureConfig){ config=v; onConfigChanged(v); dismiss() }
    private fun update(v:AdasFeatureConfig){ config=v; onConfigChanged(v) }

    private fun addToggle(root:LinearLayout,title:String,desc:String,checked:Boolean,onChange:(Boolean)->Unit){
        val card=LinearLayout(context).apply{
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            background=rounded(Color.rgb(12,25,35),20f); setPadding(dp(14),dp(10),dp(12),dp(10))
        }
        val texts=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        texts.addView(TextView(context).apply{text=title;textSize=15f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
        texts.addView(TextView(context).apply{text=desc;textSize=12f;setTextColor(Color.rgb(160,174,184));setPadding(0,dp(3),0,0)})
        card.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        val sw=Switch(context).apply{isChecked=checked;showText=false}
        sw.setOnCheckedChangeListener{_,v->onChange(v)}
        card.addView(sw,LinearLayout.LayoutParams(dp(56),dp(42)))
        root.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
    }

    private fun refreshLicense(){
        statusText.text=if(licenseManager.isLicensed()) "TRẠNG THÁI: ${licenseManager.licenseSummary()}" else {
            val s=(licenseManager.remainingTrialMs()+999)/1000
            "TRẠNG THÁI: DÙNG THỬ %02d:%02d".format(Locale.US,s/60,s%60)
        }
        statusText.setTextColor(Color.rgb(58,228,190))
    }
    private fun presetButton(t:String,color:Int,click:()->Unit)=Button(context).apply{
        text=t;textSize=12f;setTextColor(Color.WHITE);background=rounded(color,20f);setOnClickListener{click()}
    }
    private fun action(t:String)=Button(context).apply{
        text=t;textSize=13f;setTextColor(Color.WHITE);background=rounded(Color.rgb(0,118,100),18f)
    }
    private fun smallAction(t:String,click:()->Unit)=action(t).apply{textSize=11f;setOnClickListener{click()}}
    private fun section(t:String)=TextView(context).apply{
        text=t;textSize=15f;setTextColor(Color.rgb(84,235,204));typeface=Typeface.DEFAULT_BOLD
        setPadding(0,dp(20),0,dp(8))
    }
    private fun body(t:String)=TextView(context).apply{text=t;textSize=13.5f;setTextColor(Color.rgb(201,211,218))}
    private fun infoCard(h:String,b:String)=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL;background=rounded(Color.rgb(12,25,35),18f);setPadding(dp(14),dp(10),dp(14),dp(10))
        addView(TextView(context).apply{text=h;textSize=14f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
        addView(body(b).apply{setPadding(0,dp(4),0,0)})
    }.also{}
    private fun rounded(color:Int,radius:Float)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius.toInt()).toFloat()}
    private fun full()=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)}
    private fun buttonParams(top:Int=8)=LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(top)}
    private fun weight(left:Int=0)=LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(left)}
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
}
