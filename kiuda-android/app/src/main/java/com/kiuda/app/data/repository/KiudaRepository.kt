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
import com.kiuda.app.util.ImageUtils
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
     * 이미지 업로드 (base64 우선 — 에뮬레이터 localhost 문제 회피)
     */
    suspend fun uploadImage(file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(Exception("빈 이미지 파일"))
            }
            val uploadFile = ImageUtils.compressForAi(file, file.parentFile ?: file)
            val bytes = uploadFile.readBytes()
            if (bytes.size < 100) {
                return@withContext Result.failure(Exception("압축 이미지 데이터가 너무 작습니다"))
            }
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

            // base64 업로드 (Retrofit BASE_URL = 10.0.2.2 사용)
            try {
                val res = api.uploadBase64(Base64UploadRequest(b64))
                val url = res.body()?.fileUrl
                if (res.isSuccessful && !url.isNullOrBlank()) {
                    lastImageBase64 = b64
                    return@withContext Result.success(rewriteLocalhost(url))
                }
            } catch (_: Exception) {
                // presigned 폴백
            }

            val presigned = api.getPresignedUrl(
                PresignedUrlRequest(uploadFile.name, "image/jpeg")
            )
            if (!presigned.isSuccessful || presigned.body()?.uploadUrl == null) {
                return@withContext Result.failure(Exception("이미지 업로드 실패"))
            }
            val uploadUrl = rewriteLocalhost(presigned.body()!!.uploadUrl!!)
            val fileUrl = rewriteLocalhost(
                presigned.body()!!.fileUrl
                    ?: return@withContext Result.failure(Exception("fileUrl 없음"))
            )
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val body = okhttp3.RequestBody.create(
                "image/jpeg".toMediaTypeOrNull(),
                bytes
            )
            val req = okhttp3.Request.Builder().url(uploadUrl).put(body).build()
            val putRes = client.newCall(req).execute()
            if (!putRes.isSuccessful) {
                return@withContext Result.failure(Exception("이미지 업로드 실패 (${putRes.code})"))
            }
            lastImageBase64 = b64
            Result.success(fileUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Volatile
    private var lastImageBase64: String? = null

    private fun rewriteLocalhost(url: String): String {
        return url
            .replace("http://localhost:", "http://10.0.2.2:")
            .replace("http://127.0.0.1:", "http://10.0.2.2:")
    }

    suspend fun predictQuestions(imageUrl: String): Result<PredictResponse> = withContext(Dispatchers.IO) {
        try {
            val res = api.predictQuestions(PredictRequest(imageUrl, lastImageBase64))
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("예측 질문 조회 실패"))
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
                DiagnosisRequest(
                    imageUrl = imageUrl,
                    symptomTagIds = symptomIds,
                    question = question,
                    imageBase64 = lastImageBase64
                )
            )
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("AI 진단 요청 실패 (${res.code()})"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun ncpmsAlerts(crop: String? = null) = withContext(Dispatchers.IO) {
        try {
            val res = api.ncpmsAlerts(crop)
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("주의보 조회 실패"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun ncpmsEncyclopedia(q: String? = null, crop: String? = null) = withContext(Dispatchers.IO) {
        try {
            val res = api.ncpmsEncyclopedia(q, crop)
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("도감 조회 실패"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun ncpmsEncyclopediaDetail(id: String) = withContext(Dispatchers.IO) {
        try {
            val res = api.ncpmsEncyclopediaDetail(id)
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("도감 상세 실패"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun ncpmsMatch(name: String) = withContext(Dispatchers.IO) {
        try {
            val res = api.ncpmsMatch(name)
            if (res.isSuccessful && res.body() != null) Result.success(res.body()!!)
            else Result.failure(Exception("도감 매칭 실패"))
        } catch (e: Exception) { Result.failure(e) }
    }

}
