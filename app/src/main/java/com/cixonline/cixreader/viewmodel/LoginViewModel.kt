package com.cixonline.cixreader.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cixonline.cixreader.api.Account
import com.cixonline.cixreader.api.NetworkClient
import com.cixonline.cixreader.utils.SettingsManager
import kotlinx.coroutines.launch
import org.simpleframework.xml.core.Persister
import org.simpleframework.xml.convert.AnnotationStrategy

class LoginViewModel(private val settingsManager: SettingsManager) : ViewModel() {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    private val serializer = Persister(AnnotationStrategy())

    init {
        val (savedUser, savedPass) = settingsManager.getCredentials()
        if (savedUser != null && savedPass != null) {
            username = savedUser
            password = savedPass
        }
    }

    fun login(onSuccess: () -> Unit) {
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "Username and password are required"
            return
        }

        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                NetworkClient.setCredentials(username, password)
                val response = NetworkClient.api.getAccount()
                val responseString = response.string()
                
                // Manually parse the XML response to avoid Retrofit converter confusion
                val account = try {
                    serializer.read(Account::class.java, responseString)
                } catch (e: Exception) {
                    // If parsing fails, the response might be a plain error string
                    throw Exception(responseString.take(100))
                }

                if (account.type == "activate") {
                    errorMessage = "Account needs activation"
                } else {
                    settingsManager.saveCredentials(username, password)
                    onSuccess()
                }
            } catch (e: Exception) {
                errorMessage = "Authentication failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }
}

class LoginViewModelFactory(private val settingsManager: SettingsManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(settingsManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
