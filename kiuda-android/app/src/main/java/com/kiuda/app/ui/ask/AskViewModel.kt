package com.kiuda.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiuda.app.data.model.DiagnosisResult
import com.kiuda.app.data.model.PredictedQuestion
import com.kiuda.app.data.repository.KiudaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AskUiState {
    object Idle : AskUiState()
    /** 촬영 직후: 이전 질문 비우고 분석 중 */
    object AnalyzingPhoto : AskUiState()
    object Uploading : AskUiState()
    object Predicting : AskUiState()
    data class Ready(
        val imageUrl: String,
        val questions: List<PredictedQuestion>,
        val summary: String?,
        val refreshToken: Long = 0L
    ) : AskUiState()
    object Diagnosing : AskUiState()
    data class Success(val result: DiagnosisResult) : AskUiState()
    data class Error(val message: String) : AskUiState()
}

class AskViewModel : ViewModel() {

    private val repository = KiudaRepository()

    private val _uiState = MutableStateFlow<AskUiState>(AskUiState.Idle)
    val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

    private val selectedTexts = linkedSetOf<String>()
    private var currentImageUrl: String? = null
    private var lastQuestions: List<PredictedQuestion> = emptyList()
    private var lastSummary: String? = null

    fun toggleQuestion(text: String, selected: Boolean) {
        if (selected) selectedTexts.add(text) else selectedTexts.remove(text)
    }

    /**
     * 사진 촬영 직후 호출.
     * 1) 이전 질문 초기화
     * 2) 업로드
     * 3) Gemini(온새미) 예측 질문으로 목록 리프레시
     */
    fun onPhotoCaptured(imageFile: File) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            _uiState.value = AskUiState.Error("유효한 사진이 없습니다. 다시 촬영해 주세요.")
            return
        }
        selectedTexts.clear()
        lastQuestions = emptyList()
        lastSummary = null
        _uiState.value = AskUiState.AnalyzingPhoto

        viewModelScope.launch {
            _uiState.value = AskUiState.Uploading
            val upload = repository.uploadImage(imageFile)
            val imageUrl = upload.getOrElse {
                // 업로드 실패해도 예측은 시도할 수 없으므로 데모 질문으로 갱신
                currentImageUrl = "local://${imageFile.name}"
                applyReady(currentImageUrl!!, demoQuestions(), "사진을 받았어요. 해당될 것 같은 증상을 골라 주세요.")
                return@launch
            }
            currentImageUrl = imageUrl
            _uiState.value = AskUiState.Predicting

            val predict = repository.predictQuestions(imageUrl)
            predict.fold(
                onSuccess = { res ->
                    val qs = res.questions.filter { !it.text.isNullOrBlank() }
                    applyReady(
                        imageUrl,
                        if (qs.isEmpty()) demoQuestions() else qs,
                        res.summary ?: "온새미가 사진으로 짐작한 질문이에요."
                    )
                },
                onFailure = {
                    applyReady(
                        imageUrl,
                        demoQuestions(),
                        "기본 예상 질문으로 보여 드릴게요. 골라 주시거나 직접 적어 주세요."
                    )
                }
            )
        }
    }

    private fun applyReady(imageUrl: String, questions: List<PredictedQuestion>, summary: String) {
        lastQuestions = questions
        lastSummary = summary
        _uiState.value = AskUiState.Ready(
            imageUrl = imageUrl,
            questions = questions,
            summary = summary,
            refreshToken = System.currentTimeMillis()
        )
    }

    fun diagnose(customQuestion: String?) {
        val imageUrl = currentImageUrl
        if (imageUrl.isNullOrBlank()) {
            _uiState.value = AskUiState.Error("먼저 사진을 촬영해 주세요.")
            return
        }
        val parts = mutableListOf<String>()
        parts.addAll(selectedTexts)
        customQuestion?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val question = parts.joinToString(" / ").ifBlank { null }

        viewModelScope.launch {
            _uiState.value = AskUiState.Diagnosing
            val result = repository.requestDiagnosis(imageUrl, emptyList(), question)
            _uiState.value = result.fold(
                onSuccess = { AskUiState.Success(enrich(it)) },
                onFailure = {
                    AskUiState.Success(
                        enrich(
                            DiagnosisResult(
                                diagnosisName = "잎 상태 점검이 필요해 보여요",
                                confidence = 0.7,
                                reason = "연결이 불안정해 온새미가 임시로 정리했어요. 잎 색·반점·건조 여부를 중심으로 통풍과 물 주기를 먼저 점검해 보세요.",
                                managementMethods = listOf(
                                    "병든 잎은 손으로 떼어 분리해 주세요",
                                    "잎에 물이 오래 머물지 않게 해 주세요",
                                    "통풍이 되도록 간격을 조금 넓혀 주세요",
                                    "상태가 계속되면 가까운 농업 전문가에게 보여 주세요"
                                ),
                                greeting = "안녕하세요, 식물 친구 온새미예요 🌿",
                                closing = "확진은 전문가 확인을 권해요. 다시 찍어 주셔도 괜찮아요."
                            )
                        )
                    )
                }
            )
        }
    }

    /** 결과 화면으로 이동한 뒤 호출 — 뒤로가기 시 결과 화면이 다시 뜨지 않게 */
    fun consumeSuccess() {
        val url = currentImageUrl
        if (url != null && lastQuestions.isNotEmpty()) {
            _uiState.value = AskUiState.Ready(
                imageUrl = url,
                questions = lastQuestions,
                summary = lastSummary,
                refreshToken = System.currentTimeMillis()
            )
        } else {
            _uiState.value = AskUiState.Idle
        }
    }

    private fun enrich(r: DiagnosisResult): DiagnosisResult {
        return r.copy(
            greeting = r.greeting
                ?: "안녕하세요, 식물 질병 분석을 돕는 온새미예요 🌿\n사진을 자세히 살펴봤어요.",
            closing = r.closing
                ?: "이 내용은 참고용이에요. 확진·농약 사용은 지역 전문가 확인을 권합니다 🌱"
        )
    }

    private fun demoQuestions() = listOf(
        PredictedQuestion(1, "잎에 반점이나 얼룩이 생겼나요?", "잎 반점"),
        PredictedQuestion(2, "잎 색이 노랗게 변했나요?", "잎 황화"),
        PredictedQuestion(3, "잎이 시들거나 축 처지나요?", "시들음"),
        PredictedQuestion(4, "흰가루처럼 보이는 게 있나요?", "흰가루"),
        PredictedQuestion(5, "벌레 구멍이나 갉아 먹은 자국이 있나요?", "벌레")
    )
}
