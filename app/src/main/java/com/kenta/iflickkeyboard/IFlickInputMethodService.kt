package com.kenta.iflickkeyboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class IFlickInputMethodService : InputMethodService(), FlickKeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: FlickKeyboardView

    override fun onCreateInputView(): View {
        keyboardView = FlickKeyboardView(this)
        keyboardView.listener = this
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView.setMode(FlickKeyboardView.Mode.KANA)
    }

    // ---- FlickKeyboardView.OnKeyboardActionListener ----

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun onDeleteLongPressStart() {
        // FlickKeyboardView側でリピート処理済み(拡張用フック)
    }

    override fun onSpace() {
        currentInputConnection?.commitText("\u3000", 1) // 全角スペース(日本語入力の慣習に合わせる)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onSwitchIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
    }

    override fun onDakutenToggle() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return
        val prev = before[0]
        val next = KanaLayout.cycleDakuten(prev)
        if (next != prev) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(next.toString(), 1)
        }
    }
}
