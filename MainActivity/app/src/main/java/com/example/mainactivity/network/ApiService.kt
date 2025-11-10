package com.example.mainactivity.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("/predict")
    suspend fun uploadAudio(
        @Part audio: MultipartBody.Part,
        // ✅ 둘 다 옵션으로 둠: 기존 AnalyzeFragment 컴파일 에러 사라짐
        @Part("confirm") confirm: RequestBody? = null,
        @Part("phone_number") phoneNumber: RequestBody? = null
    ): Response<PredictionResponse>
}
