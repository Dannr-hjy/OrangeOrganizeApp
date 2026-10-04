package com.dannr.chengzikb

import com.dannr.chengzikb.util.OemFamily
import com.dannr.chengzikb.util.OemGuide
import com.dannr.chengzikb.util.OemGuide.candidateSpecs
import com.dannr.chengzikb.util.OemGuide.familyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemGuideTest {

    @Test
    fun family_classification() {
        assertEquals(OemFamily.VIVO, familyOf("vivo", "vivo"))
        assertEquals(OemFamily.VIVO, familyOf("vivo", "iQOO")) // iQOO 归 vivo 链
        assertEquals(OemFamily.OPPO, familyOf("OPPO", "OPPO"))
        assertEquals(OemFamily.OPPO, familyOf("realme", "realme"))
        assertEquals(OemFamily.OPPO, familyOf("OnePlus", "OnePlus"))
        assertEquals(OemFamily.XIAOMI, familyOf("Xiaomi", "Redmi"))
        assertEquals(OemFamily.XIAOMI, familyOf("Xiaomi", "POCO"))
        // 荣耀常报 manufacturer=HUAWEI / brand=HONOR → 归 HONOR
        assertEquals(OemFamily.HONOR, familyOf("HUAWEI", "Honor"))
        assertEquals(OemFamily.HUAWEI, familyOf("HUAWEI", "HUAWEI"))
        assertEquals(OemFamily.SAMSUNG, familyOf("samsung", "samsung"))
        assertEquals(OemFamily.OTHER, familyOf("google", "pixel"))
        assertEquals(OemFamily.OTHER, familyOf("", ""))
    }

    @Test
    fun vivo_candidates() {
        val specs = candidateSpecs(OemFamily.VIVO)
        assertEquals(1, specs.size)
        assertEquals("com.vivo.permissionmanager", specs[0].packageName)
        assertEquals("com.vivo.permissionmanager.activity.BgStartUpManagerActivity", specs[0].className)
        assertTrue(specs[0].action == null)
    }

    @Test
    fun oppo_candidates_order_and_content() {
        val specs = candidateSpecs(OemFamily.OPPO)
        // 首选 oplus REQUEST_PERMISSION（带 bg_startup extra），备选 coloros safecenter
        assertEquals("com.oplus.security.action.REQUEST_PERMISSION", specs[0].action)
        assertEquals("com.oplus.security", specs[0].packageName)
        assertEquals("bg_startup", specs[0].extras["operation"])
        assertEquals("com.coloros.safecenter", specs[1].packageName)
    }

    @Test
    fun xiaomi_candidates() {
        val specs = candidateSpecs(OemFamily.XIAOMI)
        assertEquals(1, specs.size)
        assertEquals("com.miui.securitycenter", specs[0].packageName)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", specs[0].className)
    }

    @Test
    fun honor_candidates_include_hihonor_fallback() {
        val specs = candidateSpecs(OemFamily.HONOR)
        assertEquals("com.huawei.systemmanager", specs[0].packageName)
        assertTrue(specs.any { it.packageName == "com.hihonor.systemmanager" })
    }

    @Test
    fun huawei_candidates_have_no_hihonor() {
        val specs = candidateSpecs(OemFamily.HUAWEI)
        assertEquals(1, specs.size)
        assertEquals("com.huawei.systemmanager", specs[0].packageName)
        assertTrue(specs.none { it.packageName == "com.hihonor.systemmanager" })
    }

    @Test
    fun samsung_and_other_fall_to_generic_page() {
        assertTrue(candidateSpecs(OemFamily.SAMSUNG).isEmpty())
        assertTrue(candidateSpecs(OemFamily.OTHER).isEmpty())
    }
}
