package com.example.mainactivity.network

import com.google.gson.annotations.SerializedName

data class Probabilities(
    val real: Float? = null,
    @SerializedName("fake_2") val fake_2: Float? = null,
    val tts: Float? = null
)

data class PredictionResponse(
    val id: Long? = null,
    val prediction: String? = null,
    val confidence: Float? = null,
    val probabilities: Probabilities? = null,
    // <- 서버가 내려주는 phone_number를 camelCase로 매핑
    @SerializedName("phone_number") val phoneNumber: String? = null
)
