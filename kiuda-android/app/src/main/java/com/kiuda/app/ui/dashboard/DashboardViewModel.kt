package com.kiuda.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiuda.app.data.model.DashboardData
import com.kiuda.app.data.repository.KiudaRepository
import com.kiuda.app.util.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(val data: DashboardData) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

class DashboardViewModel : ViewModel() {

    private val repository = KiudaRepository()

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val greetingName: String
        get() = TokenManager.getNickname() ?: TokenManager.getName() ?: "키움이"

    fun load() {
        viewModelScope.launch {
            _uiState.value = DashboardUiState.Loading
            val result = repository.getDashboard()
            _uiState.value = result.fold(
                onSuccess = { DashboardUiState.Success(it) },
                onFailure = { DashboardUiState.Error(it.message ?: "데이터를 불러오지 못했습니다.") }
            )
        }
    }

    fun completeChecklist(id: Long) {
        viewModelScope.launch {
            repository.completeChecklist(id)
            load()
        }
    }

    fun logout() {
        repository.logout()
    }
}
