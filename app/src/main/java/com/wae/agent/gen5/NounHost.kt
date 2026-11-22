package com.wae.agent.gen5

import android.content.Context
import android.util.Log
import com.wae.core.config.BundledData
import com.wae.core.rule.NounResolver

/**
 * 名詞解析器的載入層（三張表：名詞→型別、型別→App 類別、動詞→型別）。
 *
 * 只接在「句型與路由都認不得」之後，所以不會影響既有成功路徑。
 */
object NounHost {

    private const val TAG = "WAE_NOUN"

    @Volatile
    private var resolver: NounResolver? = null

    fun resolver(context: Context): NounResolver {
        resolver?.let { return it }
        synchronized(this) {
            resolver?.let { return it }
            val loaded = runCatching {
                NounResolver.parse(
                    BundledData.objectTypeLines(),
                    BundledData.typeAppLines(),
                    BundledData.verbTypeLines()
                )
            }.getOrDefault(NounResolver.parse(emptySequence(), emptySequence(), emptySequence()))
            Log.i(TAG, "loaded types=" + loaded.types().size)
            resolver = loaded
            return loaded
        }
    }
}
