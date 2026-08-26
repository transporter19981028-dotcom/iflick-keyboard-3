package com.kenta.iflickkeyboard

/**
 * iPhone日本語フリック入力の1キー分のデータ。
 * center = タップ時の文字, up/down/left/right = 各方向へフリックした時の文字。
 * null の場合はその方向にフリック候補が存在しない。
 */
data class FlickKey(
    val center: String,
    val up: String? = null,
    val down: String? = null,
    val left: String? = null,
    val right: String? = null,
    val isFunction: Boolean = false // 記号/削除/改行/切替などの機能キーかどうか
)

object KanaLayout {

    // 4行 x 3列の五十音メインキー(iPhone標準配列に準拠)
    val mainGrid: List<List<FlickKey>> = listOf(
        listOf(
            FlickKey("あ", up = "う", down = "お", left = "い", right = "え"),
            FlickKey("か", up = "く", down = "こ", left = "き", right = "け"),
            FlickKey("さ", up = "す", down = "そ", left = "し", right = "せ")
        ),
        listOf(
            FlickKey("た", up = "つ", down = "と", left = "ち", right = "て"),
            FlickKey("な", up = "ぬ", down = "の", left = "に", right = "ね"),
            FlickKey("は", up = "ふ", down = "ほ", left = "ひ", right = "へ")
        ),
        listOf(
            FlickKey("ま", up = "む", down = "も", left = "み", right = "め"),
            FlickKey("や", up = "ゆ", down = "よ", left = "ゃ", right = "ょ"),
            FlickKey("ら", up = "る", down = "ろ", left = "り", right = "れ")
        ),
        listOf(
            FlickKey("小゛゜", isFunction = true), // 直前の文字の濁点/半濁点/小文字化トグル
            FlickKey("わ", up = "ー", down = "ん", left = "を"),
            FlickKey("、", up = "?", down = "…", left = "。", right = "!")
        )
    )

    // 濁点/半濁点/小文字化の変換テーブル(トグルキー用)。cycle順で次の文字を返す。
    private val dakutenCycle: Map<Char, List<Char>> = mapOf(
        'か' to listOf('が'), 'き' to listOf('ぎ'), 'く' to listOf('ぐ'), 'け' to listOf('げ'), 'こ' to listOf('ご'),
        'さ' to listOf('ざ'), 'し' to listOf('じ'), 'す' to listOf('ず'), 'せ' to listOf('ぜ'), 'そ' to listOf('ぞ'),
        'た' to listOf('だ'), 'ち' to listOf('ぢ'), 'つ' to listOf('っ', 'づ'), 'て' to listOf('で'), 'と' to listOf('ど'),
        'は' to listOf('ば', 'ぱ'), 'ひ' to listOf('び', 'ぴ'), 'ふ' to listOf('ぶ', 'ぷ'),
        'へ' to listOf('べ', 'ぺ'), 'ほ' to listOf('ぼ', 'ぽ'),
        'う' to listOf('ゔ'),
        'や' to listOf('ゃ'), 'ゆ' to listOf('ゅ'), 'よ' to listOf('ょ'),
        'あ' to listOf('ぁ'), 'い' to listOf('ぃ'), 'え' to listOf('ぇ'), 'お' to listOf('ぉ')
    )

    /** 直前の1文字を受け取り、濁点/半濁点/小文字サイクルの次の文字を返す。該当なしはそのまま返す。 */
    fun cycleDakuten(prev: Char): Char {
        val options = dakutenCycle[prev] ?: run {
            // 濁点/半濁点が既についている場合は元の清音に戻す、または次の候補へ
            val reverse = dakutenCycle.entries.firstOrNull { prev in it.value }
            if (reverse != null) {
                val list = reverse.value
                val idx = list.indexOf(prev)
                return if (idx + 1 < list.size) list[idx + 1] else reverse.key
            }
            return prev
        }
        return options.first()
    }
}
