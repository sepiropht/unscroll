package app.unscroll.data.db

import android.content.Context
import android.content.pm.ApplicationInfo
import app.unscroll.R

object DefaultCategories {
    data class Seed(
        val key: String,
        val label: String,
        val color: Long,
        val position: Int,
    )

    val seeds: List<Seed> = listOf(
        Seed("jeux", "Games", 0xFF0E6E6A, 0),
        Seed("social", "Social", 0xFFC9841A, 1),
        Seed("video", "Video", 0xFF8C4A3A, 2),
        Seed("audio", "Audio", 0xFF3D6B8C, 3),
        Seed("productivite", "Productivity", 0xFF2F6F4E, 4),
        Seed("actualites", "News", 0xFF6A5A3A, 5),
        Seed("cartes", "Maps", 0xFF3E7C74, 6),
        Seed("images", "Images", 0xFFA15C38, 7),
        Seed("autre", "Other", 0xFF5C6B68, 8),
    )

    fun entities(): List<Category> = seeds.map { seed ->
        Category(
            key = seed.key,
            label = seed.label,
            colorArgb = seed.color.toInt(),
            custom = false,
            position = seed.position,
        )
    }

    fun labelRes(key: String): Int? = when (key) {
        "jeux" -> R.string.cat_jeux
        "social" -> R.string.cat_social
        "video" -> R.string.cat_video
        "audio" -> R.string.cat_audio
        "productivite" -> R.string.cat_productivite
        "actualites" -> R.string.cat_actualites
        "cartes" -> R.string.cat_cartes
        "images" -> R.string.cat_images
        "autre" -> R.string.cat_autre
        else -> null
    }

    /** Les familles par défaut suivent la langue. Une catégorie personnalisée garde le nom choisi. */
    fun displayLabel(context: Context, key: String, stored: String, custom: Boolean): String {
        if (custom) return stored
        val res = labelRes(key) ?: return stored
        return context.getString(res)
    }

    fun keyFor(applicationCategory: Int): String = when (applicationCategory) {
        ApplicationInfo.CATEGORY_GAME -> "jeux"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivite"
        ApplicationInfo.CATEGORY_NEWS -> "actualites"
        ApplicationInfo.CATEGORY_MAPS -> "cartes"
        ApplicationInfo.CATEGORY_IMAGE -> "images"
        else -> "autre"
    }
}
