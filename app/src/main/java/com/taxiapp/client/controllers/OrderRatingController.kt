package com.taxiapp.client.controllers

import com.taxiapp.client.HomeViewModel
import com.taxiapp.client.network.ApiClient
import com.taxiapp.client.network.MessageResponse
import com.taxiapp.client.network.RateDriverRequest
import com.taxiapp.client.utils.SessionManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Контроллер завершения и оценки заказа.
 * Разделяет явную отправку оценки пользователем и закрытие карточки завершенного заказа
 * без навязывания скрытой оценки 5 звезд при сворачивании приложения в onStop().
 */
object OrderRatingController {

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
            ApiClient.instance.rateDriver(request).enqueue(object : Callback<MessageResponse> {
                override fun onResponse(call: Call<MessageResponse>, response: Response<MessageResponse>) {}
                override fun onFailure(call: Call<MessageResponse>, t: Throwable) {}
            })
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