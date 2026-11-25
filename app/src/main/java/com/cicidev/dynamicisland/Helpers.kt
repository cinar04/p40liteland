package com.cicidev.dynamicisland

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Fener aç/kapat (izin gerektirmez). */
object Torch {
    private fun id(c: Context): String? {
        return try {
            val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) { null }
    }

    fun set(c: Context, on: Boolean): Boolean {
        val id = id(c) ?: return false
        return try {
            (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
            true
        } catch (_: Exception) { false }
    }
}

/** Uygulama bazlı ada renkleri. */
object AppColors {
    val names = listOf("Varsayılan", "Kırmızı", "Turuncu", "Sarı", "Yeşil", "Turkuaz", "Mavi", "Mor", "Pembe", "Beyaz")
    val values = intArrayOf(
        0, 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(),
        0xFF5AC8FA.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFFFFFFFF.toInt()
    )

    fun default(pkg: String): Int = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> 0xFF25D366.toInt()
        "com.instagram.android" -> 0xFFE1306C.toInt()
        "org.telegram.messenger" -> 0xFF2AABEE.toInt()
        "com.spotify.music" -> 0xFF1DB954.toInt()
        "com.facebook.katana", "com.facebook.orca" -> 0xFF1877F2.toInt()
        "com.google.android.youtube" -> 0xFFFF0000.toInt()
        "com.discord" -> 0xFF5865F2.toInt()
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> 0xFF25F4EE.toInt()
        "com.snapchat.android" -> 0xFFFFFC00.toInt()
        "com.twitter.android" -> 0xFF1D9BF0.toInt()
        else -> 0
    }

    fun forPkg(c: Context, pkg: String): Int {
        val v = Cfg.p(c).getInt("ac_$pkg", 0)
        return if (v != 0) v else default(pkg)
    }
}
