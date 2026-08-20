package com.kiuda.app.data.repository

import com.kiuda.app.data.api.RetrofitClient
import com.kiuda.app.data.model.*
import com.kiuda.app.util.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class KiudaRepository {

    private val api = RetrofitClient.api

    // ===== Auth =====
    suspend fun login(username: String, password: String): Result<AuthResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.login(LoginRequest(username, password))
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    body.accessToken?.let { TokenManager.saveTokens(it, null) }
                    TokenManager.saveUserInfo(body.username, body.name, body.nickname)
                    Result.success(body)
                } else {
                    val errMsg = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Result.failure(
                        Exception(
                            errMsg?.takeIf { it.isNotBlank() }
                                ?: response.body()?.message
                                ?: "로그인 실패 (${response.code()})"
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun signup(username: String, password: String, name: String): Result<AuthResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.signup(
                    SignupRequest(
                        username = username,
                        password = password,
                        name = name,
                        nickname = name,
                        email = username
                    )
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    body.accessToken?.let { TokenManager.saveTokens(it, null) }
                    TokenManager.saveUserInfo(body.username ?: username, body.name ?: name, body.nickname ?: name)
                    Result.success(body)
                } else if (response.isSuccessful) {
                    Result.success(AuthResponse(message = "회원가입 성공"))
                } else {
                    val err = try { response.errorBody()?.string() } catch (_: Exception) { null }
                    Result.failure(Exception(err?.takeIf { it.isNotBlank() } ?: "회원가입 실패 (${response.code()})"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun logout() {
        TokenManager.clear()
    }

    fun isLoggedIn(): Boolean = !TokenManager.getAccessToken().isNullOrBlank()

    // ===== Dashboard =====
    suspend fun getDashboard(): Result<DashboardData> = withContext(Dispatchers.IO) {
        try {
            val plants = runCatching { api.getMyPlants().body() }.getOrNull() ?: emptyList()
            val checklist = runCatching { api.getChecklist().body() }.getOrNull() ?: emptyList()
            val weather = runCatching { api.getWeather().body() }.getOrNull()
            val pests = runCatching { api.getPestAlerts().body() }.getOrNull() ?: emptyList()

            // 전부 비어 있고 서버 연결 자체가 안 된 경우를 구분하기 위해 plants 호출 재시도 느낌으로
            // 하나라도 데이터가 있거나 성공했으면 Success
            Result.success(DashboardData(plants, checklist, weather, pests))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeChecklist(id: Long): Result<CareChecklistItem> =
        withContext(Dispatchers.IO) {
            try {
                val res = api.completeChecklistItem(id)
                if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
                else Result.failure(Exception("체크리스트 업데이트 실패"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ===== AI Diagnosis =====
    suspend fun getSymptoms(): Result<List<SymptomTag>> = withContext(Dispatchers.IO) {
        try {
            val res = api.getSymptomTags()
            if (res.isSuccessful) Result.success(res.body() ?: emptyList())
            else Result.failure(Exception("증상 목록 조회 실패"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Presigned URL 방식 업로드
     * 반드시 IO 스레드에서 실행 (blocking OkHttp)
     */
    suspend fun uploadImage(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(Exception("빈 이미지 파일"))
            }
            val presigned = api.getPresignedUrl(
                PresignedUrlRequest(file.name, "image/jpeg")
            )
            if (!presigned.isSuccessful || presigned.body()?.uploadUrl == null) {
                return@withContext Result.failure(Exception("Presigned URL 발급 실패"))
            }
            val uploadUrl = presigned.body()!!.uploadUrl!!
            val fileUrl = presigned.body()!!.fileUrl
                ?: return@withContext Result.failure(Exception("fileUrl 없음"))

            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(uploadUrl)
                .put(body)
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    return@withContext Result.failure(Exception("이미지 업로드 실패: ${it.code}"))
                }
            }
            Result.success(fileUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestDiagnosis(
        imageUrl: String,
        symptomIds: List<Long>,
        question: String? = null
    ): Result<DiagnosisResult> = withContext(Dispatchers.IO) {
        try {
            val res = api.requestDiagnosis(
                DiagnosisRequest(imageUrl, symptomIds, question = question)
            )
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("AI 진단 요청 실패 (${res.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
