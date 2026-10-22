package com.taxiapp.client.ui.dialogs

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.taxiapp.client.R

class DriverHealthDialog(
    private val issues: List<String>
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val wrapper = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }
        inflater.inflate(R.layout.dialog_driver_health, wrapper, true)

        val marginPx = (16 * resources.displayMetrics.density).toInt()
        (wrapper.getChildAt(0)?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            leftMargin = marginPx
            rightMargin = marginPx
            marginStart = marginPx
            marginEnd = marginPx
        }

        return wrapper
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvIssues = view.findViewById<TextView>(R.id.tv_health_issues_list)
        val btnOk = view.findViewById<Button>(R.id.btn_understand_health)

        val builder = SpannableStringBuilder()
        val marginPx = (16 * resources.displayMetrics.density).toInt()

        issues.forEachIndexed { index, item ->
            if (item.isNotBlank()) {
                val start = builder.length
                builder.append("•  $item")
                val end = builder.length
                builder.setSpan(
                    LeadingMarginSpan.Standard(0, marginPx),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (index < issues.lastIndex) {
                    builder.append("\n")
                }
            }
        }
        tvIssues.text = builder

        btnOk.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }
}

class NotificationPermissionDialog(
    private val onAllow: () -> Unit,
    private val onDeny: () -> Unit
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.setCancelable(false)
        return inflater.inflate(R.layout.dialog_notification_permission, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val btnAllow = view.findViewById<Button>(R.id.btn_allow)
        val btnDeny = view.findViewById<Button>(R.id.btn_deny)

        btnAllow.setOnClickListener {
            dismissAllowingStateLoss()
            onAllow()
        }

        btnDeny.setOnClickListener {
            dismissAllowingStateLoss()
            onDeny()
        }
    }
}

class LiqPayWebViewDialog(
    private val paymentUrl: String,
    private val onDismissed: () -> Unit
) : DialogFragment() {

    override fun getTheme(): Int = android.R.style.Theme_Light_NoTitleBar_Fullscreen

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_liqpay_webview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val webView = view.findViewById<WebView>(R.id.liqpay_webview)
        val btnClose = view.findViewById<ImageView>(R.id.btn_close_webview)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl(paymentUrl)

        btnClose.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissed()
    }
}