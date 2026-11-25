package com.cicidev.dynamicisland

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper

/** Ayarlara tek noktadan erişim. */
object Cfg {
    fun p(c: Context): SharedPreferences = c.getSharedPreferences("ada", Context.MODE_PRIVATE)
}

/** Adada gösterilecek tek bir olay (bildirim, müzik, zamanlayıcı...). */
data class IslandItem(
    val key: String,
    val emoji: String,
    val title: String,
    val subtitle: String = "",
    val priority: Int = 10,        // yüksek olan öne çıkar
    val ttlMs: Long = 0L,          // 0 = kalıcı, >0 = süre sonra kaybolur
    val pop: Boolean = false,      // true = genişleyerek açılır
    val action: PendingIntent? = null,
    val media: MediaController? = null,
    val icon: Drawable? = null,    // albüm kapağı / uygulama ikonu
    val ring: Float = -1f,         // 0..1 halka ilerlemesi, -1 = halka yok
    val accent: Int = 0,           // 0 = otomatik renk
    val stamp: Long = 0L,
    val expireAt: Long = 0L
)

/** Tüm kaynakların (bildirim, müzik, şarj, arama, zamanlayıcı) ortak noktası. */
object IslandBus {
    private val main = Handler(Looper.getMainLooper())
    private val items = LinkedHashMap<String, IslandItem>()
    private var counter = 0L
    @Volatile var onChange: (() -> Unit)? = null

    fun put(item: IslandItem) {
        main.post {
            counter++
            val exp = if (item.ttlMs > 0) System.currentTimeMillis() + item.ttlMs else 0L
            items[item.key] = item.copy(stamp = counter, expireAt = exp)
            onChange?.invoke()
        }
    }

    fun remove(key: String) {
        main.post { if (items.remove(key) != null) onChange?.invoke() }
    }

    /** Öncelik sırasına göre aktif tüm olaylar. */
    fun all(): List<IslandItem> {
        val now = System.currentTimeMillis()
        items.values.removeAll { it.expireAt in 1..now }
        return items.values.sortedWith(
            compareByDescending<IslandItem> { it.priority }.thenByDescending { it.stamp }
        )
    }
}
