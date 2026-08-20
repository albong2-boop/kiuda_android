package com.kiuda.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiuda.app.data.model.DiagnosisResult
import com.kiuda.app.data.model.SymptomTag
import com.kiuda.app.data.repository.KiudaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AskUiState {
    object Idle : AskUiState()
    object LoadingSymptoms : AskUiState()
    data class SymptomsLoaded(val tags: List<SymptomTag>) : AskUiState()
    object Uploading : AskUiState()
    object Diagnosing : AskUiState()
    data class Success(val result: DiagnosisResult) : AskUiState()
    data class Error(val message: String) : AskUiState()
}

class AskViewModel : ViewModel() {

    private val repository = KiudaRepository()

    private val _uiState = MutableStateFlow<AskUiState>(AskUiState.Idle)
    val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

    private val selectedSymptomIds = mutableSetOf<Long>()

    fun loadSymptoms() {
        viewModelScope.launch {
            _uiState.value = AskUiState.LoadingSymptoms
            val result = repository.getSymptoms()
            _uiState.value = result.fold(
                onSuccess = { tags ->
                    if (tags.isEmpty()) demoSymptoms() else AskUiState.SymptomsLoaded(tags)
                },
                onFailure = { demoSymptoms() }
            )
        }
    }

    private fun demoSymptoms() = AskUiState.SymptomsLoaded(
        listOf(
            SymptomTag(1, "잎 반점", "잎"),
            SymptomTag(2, "잎 황화", "잎"),
            SymptomTag(3, "시들음", "전체"),
            SymptomTag(4, "흰가루", "잎"),
            SymptomTag(5, "벌레 구멍", "잎")
        )
    )

    fun toggleSymptom(id: Long, selected: Boolean) {
        if (selected) selectedSymptomIds.add(id) else selectedSymptomIds.remove(id)
    }

    fun diagnose(imageFile: File, question: String? = null) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            _uiState.value = AskUiState.Error("유효한 사진 파일이 없습니다. 다시 촬영해주세요.")
            return
        }
        viewModelScope.launch {
            _uiState.value = AskUiState.Uploading
            val uploadResult = repository.uploadImage(imageFile)
            if (uploadResult.isFailure) {
                // 백엔드 미연결 시 데모 진단 결과
                _uiState.value = AskUiState.Success(
                    DiagnosisResult(
                        diagnosisName = "토마토 잎곰팡이병 (데모)",
                        confidence = 0.87,
                        reason = "잎에 나타난 반점과 변색 패턴이 유사합니다. (서버 미연결 · 데모 결과)",
                        managementMethods = listOf(
                            "감염 잎 제거",
                            "통풍 개선",
                            "물 주기 조절",
                            "살균제 사용 검토"
                        )
                    )
                )
                return@launch
            }
            val imageUrl = uploadResult.getOrThrow()
            _uiState.value = AskUiState.Diagnosing
            val diagResult = repository.requestDiagnosis(
                imageUrl,
                selectedSymptomIds.toList(),
                question
            )
            _uiState.value = diagResult.fold(
                onSuccess = { AskUiState.Success(it) },
                onFailure = {
                    // 진단 API 실패 시에도 데모 결과
                    AskUiState.Success(
                        DiagnosisResult(
                            diagnosisName = "토마토 잎곰팡이병 (데모)",
                            confidence = 0.87,
                            reason = "잎에 나타난 반점과 변색 패턴이 유사합니다. (진단 API 오류 · 데모)",
                            managementMethods = listOf(
                                "감염 잎 제거",
                                "통풍 개선",
                                "물 주기 조절"
                            )
                        )
                    )
                }
            )
        }
    }

    fun reset() {
        selectedSymptomIds.clear()
        _uiState.value = AskUiState.Idle
    }
}
