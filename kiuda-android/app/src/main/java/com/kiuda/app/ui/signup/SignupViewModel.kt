package com.kiuda.app.ui.signup

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiuda.app.data.repository.KiudaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SignupUiState {
    object Idle : SignupUiState()
    object Loading : SignupUiState()
    object Success : SignupUiState()
    data class Error(val message: String) : SignupUiState()
}

class SignupViewModel : ViewModel() {

    private val repository = KiudaRepository()

    private val _uiState = MutableStateFlow<SignupUiState>(SignupUiState.Idle)
    val uiState: StateFlow<SignupUiState> = _uiState.asStateFlow()

    fun passwordStrength(pwd: String): Int {
        var s = 0
        if (pwd.length >= 8) s++
        if (pwd.any { it.isUpperCase() } && pwd.any { it.isDigit() }) s++
        if (pwd.any { !it.isLetterOrDigit() }) s++
        return s
    }

    fun signup(email: String, password: String, name: String) {
        val e = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            _uiState.value = SignupUiState.Error("올바른 이메일 형식이 아닙니다.")
            return
        }
        if (password.length < 8) {
            _uiState.value = SignupUiState.Error("비밀번호는 8자 이상이어야 합니다.")
            return
        }
        if (name.isBlank()) {
            _uiState.value = SignupUiState.Error("이름을 입력해주세요.")
            return
        }
        viewModelScope.launch {
            _uiState.value = SignupUiState.Loading
            val result = repository.signup(e, password, name.trim())
            _uiState.value = result.fold(
                onSuccess = { SignupUiState.Success },
                onFailure = { err ->
                    val msg = err.message ?: ""
                    if (msg.contains("Failed to connect", true)
                        || msg.contains("Unable to resolve", true)
                        || msg.contains("Connection refused", true)
                        || msg.contains("timeout", true)
                        || msg.contains("Network", true)
                    ) {
                        // 오프라인 데모 가입
                        com.kiuda.app.util.TokenManager.saveTokens("demo-access-token", null)
                        com.kiuda.app.util.TokenManager.saveUserInfo(e, name.trim(), name.trim())
                        SignupUiState.Success
                    } else {
                        SignupUiState.Error(msg.ifBlank { "회원가입 실패" })
                    }
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = SignupUiState.Idle
    }
}
