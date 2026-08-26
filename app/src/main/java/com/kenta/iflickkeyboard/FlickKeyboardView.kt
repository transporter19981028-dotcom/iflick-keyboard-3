package com.kenta.iflickkeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * iPhone風フリック入力キーボードView。
 * Galaxy Z Fold8の折りたたみ(カバー画面/小さい)〜展開(メイン画面/大きい)双方に
 * 画面幅ベースで自動対応しつつ、最大幅を制限してiPhoneに近いキーサイズ感を保つ。
 */
class FlickKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Action { TEXT, DELETE, SPACE, ENTER, SWITCH_SYMBOL, SWITCH_KANA, SWITCH_IME, DAKUTEN }

    data class Cell(
        val flickKey: FlickKey? = null,
        val action: Action = Action.TEXT,
        val weight: Float = 1f,
        val label: String = ""
    )

    data class RowSpec(val cells: List<Cell>)

    interface OnKeyboardActionListener {
        fun onText(text: String)
        fun onDelete()
        fun onDeleteLongPressStart()
        fun onSpace()
        fun onEnter()
        fun onSwitchIme()
        fun onDakutenToggle()
    }

    var listener: OnKeyboardActionListener? = null

    private var mode = Mode.KANA
    enum class Mode { KANA, SYMBOL }

    // ---- 見た目の寸法(iPhoneのキーサイズ感・質感に寄せる) ----
    private val density = resources.displayMetrics.density
    private val maxKeyboardWidthPx = (500 * density) // 展開時でも横に間延びしすぎないよう上限
    private val rowHeightPx = (48 * density)
    private val bottomRowHeightPx = (48 * density)
    private val keyGapPx = (7 * density)
    private val cornerRadius = 9 * density

    private val flickThresholdPx = 22 * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(1.2f * density, 0f, 1f * density, 0x33000000)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 23 * density
        color = 0xFF000000.toInt()
    }
    private val funcTextPaint = Paint(textPaint).apply {
        textSize = 15 * density
    }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(3f * density, 0f, 1.5f * density, 0x44000000)
    }
    private val popupTextPaint = Paint(textPaint).apply { textSize = 32 * density }

    private var bgColor = 0xFFD1D3D9.toInt()
    private var keyBg = 0xFFFFFFFF.toInt()
    private var keyBgPressed = 0xFFB9BDC6.toInt()
    private var funcBg = 0xFFA9AEB8.toInt()
    private var funcBgPressed = 0xFFC7CAD1.toInt()
    private var popupBg = 0xFFF5F5F5.toInt()

    private var rows: List<RowSpec> = buildKanaRows()

    // タッチ状態
    private var activeRow = -1
    private var activeCol = -1
    private var startX = 0f
    private var startY = 0f
    private var currentDir: Dir = Dir.CENTER
    private var isTracking = false

    private enum class Dir { CENTER, UP, DOWN, LEFT, RIGHT }

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var deleteRepeatRunnable: Runnable? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun buildKanaRows(): List<RowSpec> {
        val grid = KanaLayout.mainGrid
        val result = mutableListOf<RowSpec>()
        // 1行目: かな3つ + 削除キー
        result.add(
            RowSpec(
                grid[0].map { Cell(flickKey = it, action = Action.TEXT) } +
                    listOf(Cell(action = Action.DELETE, label = "⌫", weight = 1f))
            )
        )
        // 2〜4行目: かな3つ(横幅いっぱい)
        for (r in 1..3) {
            result.add(RowSpec(grid[r].map { Cell(flickKey = it, action = Action.TEXT) }))
        }
        // 5行目: 機能キー
        result.add(
            RowSpec(
                listOf(
                    Cell(action = Action.SWITCH_IME, label = "🌐", weight = 1f),
                    Cell(action = Action.SWITCH_SYMBOL, label = "123", weight = 1f),
                    Cell(action = Action.SPACE, label = "空白", weight = 3f),
                    Cell(action = Action.ENTER, label = "改行", weight = 1.4f)
                )
            )
        )
        return result
    }

    private fun buildSymbolRows(): List<RowSpec> {
        val symbols = listOf(
            listOf("1", "2", "3", "4", "5"),
            listOf("6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "("),
            listOf(")", "¥", "&", "@", "\"")
        )
        val result = mutableListOf<RowSpec>()
        symbols.forEachIndexed { idx, rowChars ->
            val cells = rowChars.map { Cell(action = Action.TEXT, label = it) }
            if (idx == 0) {
                result.add(RowSpec(cells + listOf(Cell(action = Action.DELETE, label = "⌫"))))
            } else {
                result.add(RowSpec(cells))
            }
        }
        result.add(
            RowSpec(
                listOf(
                    Cell(action = Action.SWITCH_KANA, label = "あ1", weight = 1f),
                    Cell(action = Action.TEXT, label = ".", weight = 1f),
                    Cell(action = Action.SPACE, label = "空白", weight = 3f),
                    Cell(action = Action.ENTER, label = "改行", weight = 1.4f)
                )
            )
        )
        return result
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        rows = if (mode == Mode.KANA) buildKanaRows() else buildSymbolRows()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenWidth = MeasureSpec.getSize(widthMeasureSpec)
        val kbWidth = minOf(screenWidth.toFloat(), maxKeyboardWidthPx).toInt()
        val mainRows = rows.size - 1
        val height = (mainRows * rowHeightPx + bottomRowHeightPx).toInt()
        setMeasuredDimension(screenWidth, height)
        keyboardWidthPx = kbWidth
    }

    private var keyboardWidthPx: Int = 0

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val offsetX = (width - keyboardWidthPx) / 2f
        var y = 0f
        rows.forEachIndexed { rIdx, row ->
            val rh = if (rIdx == rows.size - 1) bottomRowHeightPx else rowHeightPx
            val totalWeight = row.cells.sumOf { it.weight.toDouble() }.toFloat()
            var x = offsetX
            row.cells.forEachIndexed { cIdx, cell ->
                val cw = (keyboardWidthPx * (cell.weight / totalWeight))
                val pressed = (rIdx == activeRow && cIdx == activeCol)
                drawKey(canvas, x, y, cw, rh, cell, pressed)
                x += cw
            }
            y += rh
        }

        // フリック中のポップアップ(機能キーは対象外)
        val activeCell = rows.getOrNull(activeRow)?.cells?.getOrNull(activeCol)
        if (isTracking && activeCell?.action == Action.TEXT && activeCell.flickKey?.isFunction != true) {
            drawFlickPopup(canvas, offsetX)
        }
    }

    private fun drawKey(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, cell: Cell, pressed: Boolean) {
        val pad = keyGapPx / 2
        val rect = RectF(x + pad, y + pad, x + w - pad, y + h - pad)
        val isFunc = cell.action != Action.TEXT || cell.flickKey?.isFunction == true
        keyPaint.color = when {
            isFunc && pressed -> funcBgPressed
            isFunc -> funcBg
            pressed -> keyBgPressed
            else -> keyBg
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

        val cx = rect.centerX()
        val cy = rect.centerY()

        // 通常表示は中央の文字だけ(iPhone同様、フリック候補は押した時のポップアップでのみ見せる)
        when (cell.action) {
            Action.TEXT -> {
                val fk = cell.flickKey
                val label = fk?.center ?: cell.label
                canvas.drawText(label, cx, cy + textPaint.textSize / 3, textPaint)
            }
            else -> canvas.drawText(cell.label, cx, cy + funcTextPaint.textSize / 3, funcTextPaint)
        }
    }

    private fun drawFlickPopup(canvas: Canvas, offsetX: Float) {
        val (rect) = cellRect(activeRow, activeCol, offsetX) ?: return
        val bubbleW = rect.width() * 1.6f
        val bubbleH = rect.height() * 1.6f
        val bubble = RectF(
            rect.centerX() - bubbleW / 2,
            rect.top - bubbleH - 8 * density,
            rect.centerX() + bubbleW / 2,
            rect.top - 8 * density
        )
        popupPaint.color = popupBg
        canvas.drawRoundRect(bubble, cornerRadius, cornerRadius, popupPaint)
        val char = currentChar() ?: return
        canvas.drawText(char, bubble.centerX(), bubble.centerY() + popupTextPaint.textSize / 3, popupTextPaint)
    }

    private fun cellRect(rIdx: Int, cIdx: Int, offsetX: Float): Pair<RectF, Cell>? {
        if (rIdx !in rows.indices) return null
        val row = rows[rIdx]
        if (cIdx !in row.cells.indices) return null
        var y = 0f
        for (i in 0 until rIdx) y += if (i == rows.size - 1) bottomRowHeightPx else rowHeightPx
        val rh = if (rIdx == rows.size - 1) bottomRowHeightPx else rowHeightPx
        val totalWeight = row.cells.sumOf { it.weight.toDouble() }.toFloat()
        var x = offsetX
        for (i in 0 until cIdx) x += keyboardWidthPx * (row.cells[i].weight / totalWeight)
        val cw = keyboardWidthPx * (row.cells[cIdx].weight / totalWeight)
        return RectF(x, y, x + cw, y + rh) to row.cells[cIdx]
    }

    private fun currentChar(): String? {
        val fk = rows.getOrNull(activeRow)?.cells?.getOrNull(activeCol)?.flickKey ?: return null
        return when (currentDir) {
            Dir.CENTER -> fk.center
            Dir.UP -> fk.up ?: fk.center
            Dir.DOWN -> fk.down ?: fk.center
            Dir.LEFT -> fk.left ?: fk.center
            Dir.RIGHT -> fk.right ?: fk.center
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val offsetX = (width - keyboardWidthPx) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findCellAt(event.x, event.y, offsetX) ?: return true
                activeRow = hit.first
                activeCol = hit.second
                startX = event.x
                startY = event.y
                currentDir = Dir.CENTER
                isTracking = true

                val cell = rows[activeRow].cells[activeCol]
                if (cell.action == Action.DELETE) {
                    listener?.onDelete()
                    scheduleDeleteRepeat()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return true
                val cell = rows.getOrNull(activeRow)?.cells?.getOrNull(activeCol) ?: return true
                if (cell.action == Action.TEXT && cell.flickKey != null) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val dist = hypot(dx, dy)
                    currentDir = if (dist < flickThresholdPx) {
                        Dir.CENTER
                    } else if (abs(dx) > abs(dy)) {
                        if (dx > 0) Dir.RIGHT else Dir.LEFT
                    } else {
                        if (dy > 0) Dir.DOWN else Dir.UP
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelDeleteRepeat()
                if (isTracking && activeRow >= 0 && activeCol >= 0) {
                    val cell = rows[activeRow].cells[activeCol]
                    handleRelease(cell)
                }
                isTracking = false
                activeRow = -1
                activeCol = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDeleteRepeat()
                isTracking = false
                activeRow = -1
                activeCol = -1
                invalidate()
            }
        }
        return true
    }

    private fun handleRelease(cell: Cell) {
        if (cell.flickKey?.isFunction == true) {
            // 「小゛゜」キー: 直前の文字を濁点/半濁点/小文字にトグル
            listener?.onDakutenToggle()
            return
        }
        when (cell.action) {
            Action.TEXT -> {
                val text = if (cell.flickKey != null) currentChar() else cell.label
                if (!text.isNullOrEmpty()) listener?.onText(text)
            }
            Action.DELETE -> { /* ACTION_DOWN側で単発削除済み。長押しはRunnableで処理 */ }
            Action.SPACE -> listener?.onSpace()
            Action.ENTER -> listener?.onEnter()
            Action.SWITCH_SYMBOL -> setMode(Mode.SYMBOL)
            Action.SWITCH_KANA -> setMode(Mode.KANA)
            Action.SWITCH_IME -> listener?.onSwitchIme()
            Action.DAKUTEN -> listener?.onDakutenToggle()
        }
    }

    private fun findCellAt(x: Float, y: Float, offsetX: Float): Pair<Int, Int>? {
        var yy = 0f
        rows.forEachIndexed { rIdx, row ->
            val rh = if (rIdx == rows.size - 1) bottomRowHeightPx else rowHeightPx
            if (y in yy..(yy + rh)) {
                val totalWeight = row.cells.sumOf { it.weight.toDouble() }.toFloat()
                var xx = offsetX
                row.cells.forEachIndexed { cIdx, cell ->
                    val cw = keyboardWidthPx * (cell.weight / totalWeight)
                    if (x in xx..(xx + cw)) return rIdx to cIdx
                    xx += cw
                }
            }
            yy += rh
        }
        return null
    }

    private fun scheduleDeleteRepeat() {
        cancelDeleteRepeat()
        val r = object : Runnable {
            override fun run() {
                listener?.onDelete()
                longPressHandler.postDelayed(this, 60)
            }
        }
        deleteRepeatRunnable = r
        longPressHandler.postDelayed(r, 400)
    }

    private fun cancelDeleteRepeat() {
        deleteRepeatRunnable?.let { longPressHandler.removeCallbacks(it) }
        deleteRepeatRunnable = null
    }
}
