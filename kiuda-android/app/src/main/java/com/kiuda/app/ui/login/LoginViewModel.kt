package com.kiuda.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiuda.app.data.repository.KiudaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

class LoginViewModel : ViewModel() {

    private val repository = KiudaRepository()

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("아이디와 비밀번호를 입력해주세요.")
            return
        }
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            val result = repository.login(username.trim(), password)
            _uiState.value = result.fold(
                onSuccess = { LoginUiState.Success },
                onFailure = { e ->
                    // 백엔드 미연결 시 데모 로그인 허용 (개발/테스트용)
                    val msg = e.message ?: ""
                    if (msg.contains("Failed to connect", ignoreCase = true)
                        || msg.contains("Unable to resolve", ignoreCase = true)
                        || msg.contains("timeout", ignoreCase = true)
                        || msg.contains("Connection refused", ignoreCase = true)
                        || msg.contains("Network", ignoreCase = true)
                        || msg.contains("failed to connect", ignoreCase = true)
                    ) {
                        // 오프라인 데모: 토큰 저장 후 성공 처리
                        com.kiuda.app.util.TokenManager.saveTokens("demo-access-token", null)
                        com.kiuda.app.util.TokenManager.saveUserInfo(
                            username.trim(),
                            username.trim(),
                            username.trim()
                        )
                        LoginUiState.Success
                    } else {
                        LoginUiState.Error(msg.ifBlank { "로그인 실패" })
                    }
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}
