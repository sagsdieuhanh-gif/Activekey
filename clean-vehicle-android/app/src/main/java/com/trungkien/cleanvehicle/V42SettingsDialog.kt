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
import android.widget.*
import java.util.Locale

class V42SettingsDialog(
    context:Context,
    private val licenseManager:AdasLicenseManager,
    private val voice:GoogleAdasVoice,
    initialConfig:AdasFeatureConfig,
    private val metricsProvider:()->String,
    private val onConfigChanged:(AdasFeatureConfig)->Unit,
    private val onLicenseActivated:()->Unit,
    private val onBlackScreenCooling:()->Unit,
):Dialog(context){
    private var config=initialConfig
    private lateinit var status:TextView
    private lateinit var keyInput:EditText
    init{requestWindowFeature(Window.FEATURE_NO_TITLE);setContentView(build());window?.setBackgroundDrawableResource(android.R.color.transparent)}
    override fun onStart(){super.onStart();val dm=context.resources.displayMetrics;window?.setLayout((dm.widthPixels*.90f).toInt(),(dm.heightPixels*.95f).toInt());refresh()}
    private fun build():View{
        val s=ScrollView(context).apply{isFillViewport=true;setBackgroundColor(Color.rgb(5,12,20))};val r=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(18),dp(24),dp(30))};s.addView(r,ViewGroup.LayoutParams(-1,-2))
        r.addView(title("TrungKien ADAS",28f,Color.WHITE));r.addView(title("V4.2 • SPC CORE • VIDEO MODE",14f,Color.rgb(56,232,194)))
        r.addView(card("SUPERCOMBO LÀ NỀN CHÍNH","YOLO đã loại khỏi runtime/model. SPC trực tiếp path, lane, road edge, lead distance, TTC/HMW/FCW. UFLD chỉ calibration/fallback lane."))
        r.addView(section("SPC CORE"))
        toggle(r,"SPC Path / Lane / Road edge","Hành lang xanh và 33 điểm model.",config.supercomboLanePath){save(config.copy(supercomboLanePath=it))}
        toggle(r,"SPC Lead","Khoảng cách + marker cam từ Supercombo.",config.supercomboLead){save(config.copy(supercomboLead=it))}
        toggle(r,"UFLD calibration/fallback","Không dùng cho distance; chỉ hỗ trợ camera/lane.",config.ufld){save(config.copy(ufld=it))}
        toggle(r,"FCW + HMW","Cảnh báo từ chuỗi SPC distance.",config.fcwHmw){save(config.copy(fcwHmw=it))}
        toggle(r,"LDW + TLC","Cảnh báo lệch làn.",config.ldwTlc){save(config.copy(ldwTlc=it))}
        toggle(r,"Bảo vệ nhiệt","Tự giảm tần suất SPC khi nóng.",config.thermalProtection){save(config.copy(thermalProtection=it))}
        toggle(r,"Thông tin kỹ thuật kiểu video","FPS/XNNPACK/pair/feat/pitch/yaw/fPx/horizon/lane probs.",config.technicalInfo){save(config.copy(technicalInfo=it))}
        r.addView(section("TRẠNG THÁI SPC"));val m=body(metricsProvider()).apply{typeface=Typeface.MONOSPACE;background=round(Color.rgb(12,27,36));setPadding(dp(14),dp(12),dp(14),dp(12))};r.addView(m,lp())
        r.addView(button("LÀM MỚI THÔNG SỐ").apply{setOnClickListener{m.text=metricsProvider()}},bp())
        r.addView(button("KIỂM TRA CẬP NHẬT").apply{setOnClickListener{(context as? Activity)?.let{AdasAutoUpdater.checkNow(it)}}},bp())
        r.addView(button("TẮT HIỂN THỊ GIẢM NÓNG").apply{setOnClickListener{dismiss();onBlackScreenCooling()}},bp())
        r.addView(section("THIẾT BỊ & BẢN QUYỀN"));status=body("");r.addView(status)
        r.addView(title(licenseManager.deviceCode,23f,Color.WHITE).apply{typeface=Typeface.MONOSPACE;gravity=Gravity.CENTER;background=round(Color.rgb(13,28,38));setTextIsSelectable(true);setPadding(dp(10),dp(12),dp(10),dp(12))},lp())
        r.addView(button("SAO CHÉP MÃ THIẾT BỊ").apply{setOnClickListener{val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("Device",licenseManager.deviceCode))}},bp())
        keyInput=EditText(context).apply{hint="DÁN KEY TỪ ADMIN";setHintTextColor(Color.GRAY);setTextColor(Color.WHITE);minLines=2;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;background=round(Color.rgb(13,28,38));setPadding(dp(10),dp(10),dp(10),dp(10))};r.addView(keyInput,LinearLayout.LayoutParams(-1,dp(82)).apply{topMargin=dp(8)})
        val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL};row.addView(button("DÁN KEY").apply{setOnClickListener{val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.primaryClip?.takeIf{it.itemCount>0}?.let{keyInput.setText(it.getItemAt(0).coerceToText(context).toString().trim())}}},LinearLayout.LayoutParams(0,-1,1f));row.addView(button("KÍCH HOẠT").apply{setOnClickListener{val x=licenseManager.activate(keyInput.text.toString());status.text=x.message;if(x.valid){onLicenseActivated();dismiss()}}},LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(8)});r.addView(row,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(8)})
        r.addView(section("GOOGLE TTS"));val vr=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL};vr.addView(button("VA CHẠM").apply{setOnClickListener{voice.collisionRisk()}},LinearLayout.LayoutParams(0,-1,1f));vr.addView(button("QUÁ GẦN").apply{setOnClickListener{voice.headwayTooClose()}},LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(6)});vr.addView(button("LỆCH LÀN").apply{setOnClickListener{voice.laneDeparture()}},LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(6)});r.addView(vr,LinearLayout.LayoutParams(-1,dp(50)))
        r.addView(body("SPC camera đơn là hệ thống thử nghiệm, không thay thế ADAS được chứng nhận.").apply{setTextColor(Color.rgb(255,190,72));setPadding(0,dp(14),0,0)})
        r.addView(button("ĐÓNG CÀI ĐẶT").apply{setOnClickListener{dismiss()}},bp(16));return s
    }
    private fun save(v:AdasFeatureConfig){config=v.copy(yolox=false,supercombo=true,fusionSmartLead=false,distanceMode=LeadDistanceMode.SUPERCOMBO);onConfigChanged(config)}
    private fun refresh(){status.text=if(licenseManager.isLicensed())"TRẠNG THÁI: ${licenseManager.licenseSummary()}" else {val s=(licenseManager.remainingTrialMs()+999)/1000;"TRẠNG THÁI: DÙNG THỬ %02d:%02d".format(Locale.US,s/60,s%60)}}
    private fun toggle(root:LinearLayout,t:String,d:String,c:Boolean,cb:(Boolean)->Unit){val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=round(Color.rgb(12,25,35));setPadding(dp(12),dp(8),dp(10),dp(8))};val tx=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL};tx.addView(title(t,14f,Color.WHITE));tx.addView(body(d).apply{textSize=12f});row.addView(tx,LinearLayout.LayoutParams(0,-2,1f));val sw=Switch(context).apply{isChecked=c};sw.setOnCheckedChangeListener{_,v->cb(v)};row.addView(sw);root.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})}
    private fun title(t:String,z:Float,c:Int)=TextView(context).apply{text=t;textSize=z;setTextColor(c);typeface=Typeface.DEFAULT_BOLD}
    private fun body(t:String)=TextView(context).apply{text=t;textSize=13f;setTextColor(Color.rgb(201,211,218))}
    private fun section(t:String)=title(t,15f,Color.rgb(84,235,204)).apply{setPadding(0,dp(18),0,dp(7))}
    private fun card(a:String,b:String)=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;background=round(Color.rgb(8,38,42));setPadding(dp(13),dp(10),dp(13),dp(10));addView(title(a,15f,Color.rgb(80,240,205)));addView(body(b).apply{setPadding(0,dp(4),0,0)})}
    private fun button(t:String)=Button(context).apply{text=t;textSize=12f;setTextColor(Color.WHITE);background=round(Color.rgb(0,118,100))}
    private fun round(c:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(18).toFloat()}
    private fun lp()=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7)}
    private fun bp(top:Int=7)=LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(top)}
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
}
