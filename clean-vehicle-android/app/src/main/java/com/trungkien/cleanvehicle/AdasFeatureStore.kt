package com.trungkien.cleanvehicle

import android.content.Context

data class AdasFeatureConfig(
    val yolox: Boolean = true,
    val ufld: Boolean = true,
    val supercombo: Boolean = false,
    val supercomboLanePath: Boolean = false,
    val supercomboLead: Boolean = false,
    val fusionSmartLead: Boolean = false,
    val fcwHmw: Boolean = true,
    val ldwTlc: Boolean = true,
    val technicalInfo: Boolean = false,
) {
    fun presetName(): String = when {
        yolox && ufld && !supercombo -> "BASELINE"
        yolox && !ufld && supercombo && supercomboLanePath -> "SUPERCOMBO"
        yolox && ufld && supercombo && supercomboLanePath -> "HYBRID"
        else -> "TÙY CHỈNH"
    }

    companion object {
        fun baseline() = AdasFeatureConfig()
        fun supercombo() = AdasFeatureConfig(
            yolox = true,
            ufld = false,
            supercombo = true,
            supercomboLanePath = true,
            supercomboLead = true,
            fusionSmartLead = true,
            fcwHmw = true,
            ldwTlc = true,
        )
        fun hybrid() = AdasFeatureConfig(
            yolox = true,
            ufld = true,
            supercombo = true,
            supercomboLanePath = true,
            supercomboLead = true,
            fusionSmartLead = true,
            fcwHmw = true,
            ldwTlc = true,
        )
    }
}

class AdasFeatureStore(context: Context) {
    private val prefs = context.getSharedPreferences("trungkien_adas_v3_features", Context.MODE_PRIVATE)

    fun load(): AdasFeatureConfig = AdasFeatureConfig(
        yolox = prefs.getBoolean("yolox", true),
        ufld = prefs.getBoolean("ufld", true),
        supercombo = prefs.getBoolean("supercombo", false),
        supercomboLanePath = prefs.getBoolean("sc_lane", false),
        supercomboLead = prefs.getBoolean("sc_lead", false),
        fusionSmartLead = prefs.getBoolean("fusion_lead", false),
        fcwHmw = prefs.getBoolean("fcw_hmw", true),
        ldwTlc = prefs.getBoolean("ldw_tlc", true),
        technicalInfo = prefs.getBoolean("technical", false),
    )

    fun save(v: AdasFeatureConfig) {
        prefs.edit()
            .putBoolean("yolox", v.yolox)
            .putBoolean("ufld", v.ufld)
            .putBoolean("supercombo", v.supercombo)
            .putBoolean("sc_lane", v.supercomboLanePath)
            .putBoolean("sc_lead", v.supercomboLead)
            .putBoolean("fusion_lead", v.fusionSmartLead)
            .putBoolean("fcw_hmw", v.fcwHmw)
            .putBoolean("ldw_tlc", v.ldwTlc)
            .putBoolean("technical", v.technicalInfo)
            .apply()
    }
}
