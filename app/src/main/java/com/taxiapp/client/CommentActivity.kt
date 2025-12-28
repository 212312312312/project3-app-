package com.taxiapp.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.taxiapp.client.utils.ViewUtils

class CommentActivity : AppCompatActivity() {

    private lateinit var etComment: EditText
    private lateinit var tvCounter: TextView
    private lateinit var suggestionsContainer: LinearLayout
    private lateinit var btnDone: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comment)

        try { ViewUtils.makeImmersive(this) } catch (e: Exception) { e.printStackTrace() }

        etComment = findViewById(R.id.et_comment)
        tvCounter = findViewById(R.id.tv_counter)
        suggestionsContainer = findViewById<LinearLayout>(R.id.suggestions_container)
        btnDone = findViewById(R.id.btn_done)

        val currentText = intent.getStringExtra("EXTRA_COMMENT") ?: ""
        etComment.setText(currentText)
        etComment.setSelection(etComment.text.length)
        updateCounter(currentText.length)

        // Оновлюємо стан кнопки відразу при запуску
        updateButtonState(currentText.isNotEmpty())

        // Авто-клавіатура
        etComment.requestFocus()
        etComment.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etComment, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        btnDone.setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putExtra("EXTRA_COMMENT", etComment.text.toString().trim())
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        etComment.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                updateCounter(length)
                // Оновлюємо кнопку при кожному введенні символу
                updateButtonState(length > 0)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setupSuggestions()
    }

    private fun updateCounter(len: Int) {
        tvCounter.text = "$len/400"
    }

    // === НОВА ФУНКЦІЯ: Зміна стилю кнопки ===
    private fun updateButtonState(isEnabled: Boolean) {
        btnDone.isEnabled = isEnabled

        if (isEnabled) {
            // АКТИВНА: Заповнена (Колір тексту теми), Текст (Колір фону теми)
            // У світлій темі: Фон Чорний, Текст Білий
            // У темній темі: Фон Білий, Текст Чорний
            val activeBgColor = getThemeColor(android.R.attr.colorForeground) // Зазвичай це колір тексту (чорний/білий)
            // Або краще використовувати colorOnBackground
            val colorOnBg = getThemeColor(com.google.android.material.R.attr.colorOnBackground)
            val colorBg = getThemeColor(android.R.attr.colorBackground)

            btnDone.backgroundTintList = ColorStateList.valueOf(colorOnBg)
            btnDone.setTextColor(colorBg)
            btnDone.strokeWidth = 0
        } else {
            // НЕАКТИВНА: Прозора, сірий текст, сіра обводка
            val grayColor = Color.parseColor("#9E9E9E")
            btnDone.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btnDone.setTextColor(grayColor)
            btnDone.strokeColor = ColorStateList.valueOf(grayColor)
            btnDone.strokeWidth = 2 // 2px або перевести в dp, тут простіше 2 пікселі для тонкої лінії
        }
    }

    // Допоміжна функція для отримання кольору з атрибутів теми
    private fun getThemeColor(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    private fun setupSuggestions() {
        val clickListener = View.OnClickListener { view ->
            val textToAdd = when(view.id) {
                R.id.suggestion_wait -> "Чекайте біля "
                R.id.suggestion_entry -> "Заїзд зі сторони "
                R.id.suggestion_change -> "Потрібна решта з "
                R.id.suggestion_nocall -> "Не телефонуйте мені, будь ласка"
                else -> ""
            }

            etComment.setText(textToAdd)
            etComment.setSelection(etComment.text.length)

            suggestionsContainer.visibility = View.GONE

            // Оновлюємо кнопку (бо текст змінився програмно)
            updateButtonState(true)

            etComment.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etComment, InputMethodManager.SHOW_IMPLICIT)
        }

        findViewById<TextView>(R.id.suggestion_wait).setOnClickListener(clickListener)
        findViewById<TextView>(R.id.suggestion_entry).setOnClickListener(clickListener)
        findViewById<TextView>(R.id.suggestion_change).setOnClickListener(clickListener)
        findViewById<TextView>(R.id.suggestion_nocall).setOnClickListener(clickListener)
    }
}