package com.trungkien.cleanvehicle

import android.content.Context

data class AdasFeatureConfig(
    val yolox: Boolean = false,
    val ufld: Boolean = true,
    val supercombo: Boolean = true,
    val supercomboLanePath: Boolean = true,
    val supercomboLead: Boolean = true,
    val fusionSmartLead: Boolean = false,
    val fcwHmw: Boolean = true,
    val ldwTlc: Boolean = true,
    val technicalInfo: Boolean = false,
    val thermalProtection: Boolean = true,
    val distanceMode: LeadDistanceMode = LeadDistanceMode.SUPERCOMBO,
) {
    fun presetName(): String = "SPC CORE"
    companion object {
        fun spcCore() = AdasFeatureConfig()
        fun v4Auto() = spcCore()
        fun supercomboPrimary() = spcCore()
        fun yoloCheck() = spcCore()
        fun baseline() = spcCore()
        fun supercombo() = spcCore()
        fun hybrid() = spcCore()
    }
}

class AdasFeatureStore(context: Context) {
    private val prefs = context.getSharedPreferences("trungkien_adas_v42_spc_video", Context.MODE_PRIVATE)
    fun load(): AdasFeatureConfig {
        val d = AdasFeatureConfig.spcCore()
        return d.copy(
            ufld = prefs.getBoolean("ufld_helper", d.ufld),
            supercomboLanePath = prefs.getBoolean("sc_lane", d.supercomboLanePath),
            supercomboLead = prefs.getBoolean("sc_lead", d.supercomboLead),
            fcwHmw = prefs.getBoolean("fcw_hmw", d.fcwHmw),
            ldwTlc = prefs.getBoolean("ldw_tlc", d.ldwTlc),
            technicalInfo = prefs.getBoolean("technical", d.technicalInfo),
            thermalProtection = prefs.getBoolean("thermal", d.thermalProtection),
        )
    }
    fun save(v: AdasFeatureConfig) {
        prefs.edit()
            .putBoolean("ufld_helper", v.ufld)
            .putBoolean("sc_lane", v.supercomboLanePath)
            .putBoolean("sc_lead", v.supercomboLead)
            .putBoolean("fcw_hmw", v.fcwHmw)
            .putBoolean("ldw_tlc", v.ldwTlc)
            .putBoolean("technical", v.technicalInfo)
            .putBoolean("thermal", v.thermalProtection)
            .apply()
    }
}
