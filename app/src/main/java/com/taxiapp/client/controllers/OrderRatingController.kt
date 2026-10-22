package com.taxiapp.client.controllers

import com.taxiapp.client.HomeViewModel
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.RateDriverRequest
import com.taxiapp.client.utils.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Контроллер завершения и оценки заказа (на базе Kotlin Coroutines).
 */
object OrderRatingController {

    private val ratingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun submitRatingAndDismiss(
        orderId: String,
        score: Int,
        comment: String?,
        sessionManager: SessionManager,
        viewModel: HomeViewModel,
        onCompleteUiReset: () -> Unit
    ) {
        val token = sessionManager.fetchAuthToken()
        if (!token.isNullOrEmpty() && score in 1..5) {
            val request = RateDriverRequest(orderId, score, comment)
            ratingScope.launch {
                try {
                    ApiClient.instance.rateDriver(request)
                } catch (_: Exception) {}
            }
        }
        viewModel.dismissCompletedOrder(orderId)
        viewModel.clearOrderState()
        onCompleteUiReset()
    }

    fun dismissCompletedWithoutForcedRating(
        orderId: String,
        viewModel: HomeViewModel,
        onCompleteUiReset: () -> Unit
    ) {
        viewModel.dismissCompletedOrder(orderId)
        viewModel.clearOrderState()
        onCompleteUiReset()
    }
}