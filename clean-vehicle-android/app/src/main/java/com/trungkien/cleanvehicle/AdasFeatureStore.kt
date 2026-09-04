package com.trungkien.cleanvehicle

import android.content.Context

data class AdasFeatureConfig(
    val yolox: Boolean = true,
    val ufld: Boolean = true,
    val supercombo: Boolean = true,
    val supercomboLanePath: Boolean = true,
    val supercomboLead: Boolean = true,
    val fusionSmartLead: Boolean = true,
    val fcwHmw: Boolean = true,
    val ldwTlc: Boolean = true,
    val technicalInfo: Boolean = false,
    val distanceMode: LeadDistanceMode = LeadDistanceMode.AUTO,
) {
    fun presetName(): String = when (distanceMode) {
        LeadDistanceMode.AUTO -> "V4 AUTO"
        LeadDistanceMode.SUPERCOMBO -> "SC PRIMARY"
        LeadDistanceMode.YOLO -> "YOLO CHECK"
    }

    companion object {
        fun v4Auto() = AdasFeatureConfig()
        fun supercomboPrimary() = AdasFeatureConfig(distanceMode = LeadDistanceMode.SUPERCOMBO)
        fun yoloCheck() = AdasFeatureConfig(distanceMode = LeadDistanceMode.YOLO)

        fun baseline() = yoloCheck().copy(
            supercombo = false,
            supercomboLanePath = false,
            supercomboLead = false,
            fusionSmartLead = false,
        )
        fun supercombo() = supercomboPrimary().copy(ufld = false)
        fun hybrid() = v4Auto()
    }
}

class AdasFeatureStore(context: Context) {
    private val prefs = context.getSharedPreferences(
        "trungkien_adas_v4_features",
        Context.MODE_PRIVATE,
    )

    fun load(): AdasFeatureConfig {
        val d = AdasFeatureConfig.v4Auto()
        val mode = runCatching {
            LeadDistanceMode.valueOf(
                prefs.getString("distance_mode", d.distanceMode.name)
                    ?: d.distanceMode.name
            )
        }.getOrDefault(d.distanceMode)

        return AdasFeatureConfig(
            yolox = prefs.getBoolean("yolox", d.yolox),
            ufld = prefs.getBoolean("ufld", d.ufld),
            supercombo = prefs.getBoolean("supercombo", d.supercombo),
            supercomboLanePath = prefs.getBoolean("sc_lane", d.supercomboLanePath),
            supercomboLead = prefs.getBoolean("sc_lead", d.supercomboLead),
            fusionSmartLead = prefs.getBoolean("fusion_lead", d.fusionSmartLead),
            fcwHmw = prefs.getBoolean("fcw_hmw", d.fcwHmw),
            ldwTlc = prefs.getBoolean("ldw_tlc", d.ldwTlc),
            technicalInfo = prefs.getBoolean("technical", d.technicalInfo),
            distanceMode = mode,
        )
    }

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
            .putString("distance_mode", v.distanceMode.name)
            .apply()
    }
}
