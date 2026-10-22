package com.taxiapp.client.ui.dialogs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.TimePicker
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.taxiapp.client.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleRideBottomSheet(
    private val isDarkMode: Boolean,
    private val onDateScheduled: (Calendar) -> Unit,
    private val onValidationError: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_schedule_ride, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            val controller = WindowInsetsControllerCompat(window, view)
            if (isDarkMode) {
                window.navigationBarColor = Color.BLACK
                controller.isAppearanceLightNavigationBars = false
            } else {
                window.navigationBarColor = Color.WHITE
                controller.isAppearanceLightNavigationBars = true
            }
        }

        val tabDate = view.findViewById<TextView>(R.id.tab_date)
        val tabTime = view.findViewById<TextView>(R.id.tab_time)
        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm_schedule)

        val datePicker = view.findViewById<NumberPicker>(R.id.date_picker_widget)
        val timePicker = view.findViewById<TimePicker>(R.id.time_picker_widget)

        val dateContainer = view.findViewById<FrameLayout>(R.id.date_picker_container)
        val timeContainer = view.findViewById<FrameLayout>(R.id.time_picker_container)

        val availableDates = mutableListOf<Calendar>()
        val displayStrings = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("dd MMM", Locale("uk", "UA"))

        for (i in 0..6) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, i)
            availableDates.add(cal)

            when (i) {
                0 -> displayStrings.add("Сьогодні, ${dateFormat.format(cal.time)}")
                1 -> displayStrings.add("Завтра, ${dateFormat.format(cal.time)}")
                else -> displayStrings.add(dateFormat.format(cal.time))
            }
        }

        datePicker.minValue = 0
        datePicker.maxValue = displayStrings.size - 1
        datePicker.displayedValues = displayStrings.toTypedArray()
        datePicker.wrapSelectorWheel = false
        datePicker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS

        timePicker.setIs24HourView(true)

        val defaultScheduleTime = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 40)
        }
        val defaultHour = defaultScheduleTime.get(Calendar.HOUR_OF_DAY)
        val defaultMinute = defaultScheduleTime.get(Calendar.MINUTE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            timePicker.hour = defaultHour
            timePicker.minute = defaultMinute
        } else {
            @Suppress("DEPRECATION")
            timePicker.currentHour = defaultHour
            @Suppress("DEPRECATION")
            timePicker.currentMinute = defaultMinute
        }

        fun updateTabs(isDateSelected: Boolean) {
            val ctx = context ?: return
            val colorPrimary = ContextCompat.getColor(ctx, R.color.text_primary)
            val colorBg = ContextCompat.getColor(ctx, R.color.card_background)
            val colorSecondary = ContextCompat.getColor(ctx, R.color.text_secondary)

            if (isDateSelected) {
                tabDate.setBackgroundResource(R.drawable.bg_button_primary)
                tabDate.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                tabDate.setTextColor(colorBg)

                tabTime.background = null
                tabTime.backgroundTintList = null
                tabTime.setTextColor(colorSecondary)

                dateContainer.visibility = View.VISIBLE
                timeContainer.visibility = View.GONE
            } else {
                tabTime.setBackgroundResource(R.drawable.bg_button_primary)
                tabTime.backgroundTintList = ColorStateList.valueOf(colorPrimary)
                tabTime.setTextColor(colorBg)

                tabDate.background = null
                tabDate.backgroundTintList = null
                tabDate.setTextColor(colorSecondary)

                dateContainer.visibility = View.GONE
                timeContainer.visibility = View.VISIBLE
            }
        }

        tabDate.setOnClickListener { updateTabs(true) }
        tabTime.setOnClickListener { updateTabs(false) }

        btnConfirm.setOnClickListener {
            val selectedIndex = datePicker.value
            val selectedCalendar = availableDates[selectedIndex].clone() as Calendar

            val hour = if (Build.VERSION.SDK_INT >= 23) timePicker.hour else @Suppress("DEPRECATION") timePicker.currentHour
            val minute = if (Build.VERSION.SDK_INT >= 23) timePicker.minute else @Suppress("DEPRECATION") timePicker.currentMinute

            selectedCalendar.set(Calendar.HOUR_OF_DAY, hour)
            selectedCalendar.set(Calendar.MINUTE, minute)
            selectedCalendar.set(Calendar.SECOND, 0)
            selectedCalendar.set(Calendar.MILLISECOND, 0)

            val minAllowedTime = Calendar.getInstance().apply {
                add(Calendar.MINUTE, 40)
            }

            if (selectedCalendar.before(minAllowedTime)) {
                onValidationError("Попереднє замовлення можливе мінімум за 40 хвилин")
                updateTabs(false)
            } else {
                onDateScheduled(selectedCalendar)
                dismissAllowingStateLoss()
            }
        }
    }
}