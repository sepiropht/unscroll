package app.unscroll.data.guard

import android.content.Context
import app.unscroll.R

object Quotes {
    fun pick(context: Context, seed: String): String {
        val lines = context.resources.getStringArray(R.array.block_quotes)
        if (lines.isEmpty()) return ""
        val index = (seed.hashCode() and Int.MAX_VALUE) % lines.size
        return lines[index]
    }
}
