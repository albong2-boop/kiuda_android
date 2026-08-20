package com.kiuda.app.data.model

import com.google.gson.annotations.SerializedName

// ===== Auth =====
data class LoginRequest(
    val username: String,
    val password: String
)

data class SignupRequest(
    val username: String,
    val password: String,
    val name: String,
    val nickname: String? = null,
    val email: String? = null
)

data class AuthResponse(
    val jwt: String? = null,
    val token: String? = null,
    val username: String? = null,
    val name: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val message: String? = null
) {
    val accessToken: String?
        get() = jwt ?: token
}

// ===== User / Plant (보다) =====
data class UserPlant(
    val id: Long? = null,
    val name: String? = null,
    val plantType: String? = null,
    val nickname: String? = null,
    val imageUrl: String? = null,
    val location: String? = null,
    val createdAt: String? = null
)

data class CareChecklistItem(
    val id: Long? = null,
    val title: String? = null,
    val completed: Boolean = false,
    val plantId: Long? = null,
    val dueDate: String? = null
)

data class WeatherSnapshot(
    val temperature: Double? = null,
    val condition: String? = null,
    val humidity: Int? = null,
    val location: String? = null
)

data class PestRiskAlert(
    val id: Long? = null,
    val title: String? = null,
    val level: String? = null, // LOW, MEDIUM, HIGH
    val description: String? = null
)

data class DashboardData(
    val plants: List<UserPlant> = emptyList(),
    val checklist: List<CareChecklistItem> = emptyList(),
    val weather: WeatherSnapshot? = null,
    val pestAlerts: List<PestRiskAlert> = emptyList()
)

// ===== 묻다 (AI Diagnosis) =====
data class SymptomTag(
    val id: Long? = null,
    val name: String? = null,
    val category: String? = null
)

data class PresignedUrlRequest(
    val fileName: String,
    val contentType: String = "image/jpeg"
)

data class PresignedUrlResponse(
    val uploadUrl: String? = null,
    val fileUrl: String? = null,
    val key: String? = null
)

data class DiagnosisRequest(
    val imageUrl: String,
    val symptomTagIds: List<Long> = emptyList(),
    val plantId: Long? = null,
    val question: String? = null
)

data class DiagnosisStep(
    val step: Int? = null,
    val title: String? = null,
    val description: String? = null
)

data class DiagnosisResult(
    val id: Long? = null,
    val diagnosisName: String? = null,
    val confidence: Double? = null, // 0.0 ~ 1.0 or 0~100
    val reason: String? = null,
    val managementMethods: List<String>? = null,
    val steps: List<DiagnosisStep>? = null,
    val imageUrl: String? = null
)

// ===== Generic =====
data class ApiMessage(
    val message: String? = null,
    val success: Boolean? = null
)
