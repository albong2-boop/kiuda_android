package com.kiuda.app.data.api

import com.kiuda.app.data.model.*
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ===== Auth =====
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): Response<AuthResponse>

    // Google 로그인 (백엔드에 엔드포인트가 있을 경우)
    @POST("auth/google")
    suspend fun googleLogin(@Body body: Map<String, String>): Response<AuthResponse>

    // ===== 내 정보 =====
    @GET("user/me")
    suspend fun getMe(): Response<AuthResponse>

    // ===== 보다 (Dashboard) =====
    @GET("plants")
    suspend fun getMyPlants(): Response<List<UserPlant>>

    @GET("plants/{id}")
    suspend fun getPlant(@Path("id") id: Long): Response<UserPlant>

    @GET("checklist")
    suspend fun getChecklist(): Response<List<CareChecklistItem>>

    @PUT("checklist/{id}/complete")
    suspend fun completeChecklistItem(@Path("id") id: Long): Response<CareChecklistItem>

    @GET("weather")
    suspend fun getWeather(): Response<WeatherSnapshot>

    @GET("pest-alerts")
    suspend fun getPestAlerts(): Response<List<PestRiskAlert>>

    // ===== 묻다 (AI) =====
    @GET("symptoms")
    suspend fun getSymptomTags(): Response<List<SymptomTag>>

    @POST("upload/base64")
    suspend fun uploadBase64(@Body request: Base64UploadRequest): Response<Base64UploadResponse>

    @POST("upload/presigned")
    suspend fun getPresignedUrl(@Body request: PresignedUrlRequest): Response<PresignedUrlResponse>

    @POST("ai/predict")
    suspend fun predictQuestions(@Body request: PredictRequest): Response<PredictResponse>

    @POST("ai/diagnose")
    suspend fun requestDiagnosis(@Body request: DiagnosisRequest): Response<DiagnosisResult>

    @GET("ai/diagnose/{id}")
    suspend fun getDiagnosisResult(@Path("id") id: Long): Response<DiagnosisResult>

    // NCPMS
    @GET("ncpms/alerts")
    suspend fun ncpmsAlerts(@Query("crop") crop: String? = null): Response<NcpmsAlertListResponse>

    @GET("ncpms/encyclopedia")
    suspend fun ncpmsEncyclopedia(
        @Query("q") q: String? = null,
        @Query("crop") crop: String? = null
    ): Response<NcpmsEncyclopediaListResponse>

    @GET("ncpms/encyclopedia/{id}")
    suspend fun ncpmsEncyclopediaDetail(@Path("id") id: String): Response<NcpmsEncyclopediaItem>

    @GET("ncpms/match")
    suspend fun ncpmsMatch(@Query("name") name: String): Response<NcpmsMatchResponse>
}
