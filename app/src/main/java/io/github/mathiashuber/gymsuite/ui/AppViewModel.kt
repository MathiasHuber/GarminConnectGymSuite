package io.github.mathiashuber.gymsuite.ui

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mathiashuber.gymsuite.data.Repository
import io.github.mathiashuber.gymsuite.data.SettingsStore
import io.github.mathiashuber.gymsuite.data.StoreFile
import io.github.mathiashuber.gymsuite.data.SyncProgress
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.UserSettings
import io.github.mathiashuber.gymsuite.garmin.GarminApiException
import io.github.mathiashuber.gymsuite.garmin.GarminAuthException
import io.github.mathiashuber.gymsuite.garmin.GarminParsing
import io.github.mathiashuber.gymsuite.garmin.GarminTokens
import io.github.mathiashuber.gymsuite.garmin.LoginFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import io.github.mathiashuber.gymsuite.analysis.EraResult
import io.github.mathiashuber.gymsuite.analysis.Eras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LoginState {
    data object Idle : LoginState
    data object Exchanging : LoginState
    data class Failed(val message: String) : LoginState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val settingsStore = SettingsStore(app)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _store = MutableStateFlow(StoreFile())
    val store: StateFlow<StoreFile> = _store.asStateFlow()

    private val _userData = MutableStateFlow(UserData())
    val userData: StateFlow<UserData> = _userData.asStateFlow()

    /** Detected training eras; null until the first computation finishes. */
    val eras: StateFlow<EraResult?> = combine(_store, _userData, _settings) { store, user, settings ->
        // Favorites don't affect eras; leave them out so toggling one doesn't recompute.
        Triple(store, user.copy(favorites = emptySet()), settings.analysis)
    }
        .distinctUntilChanged()
        .map { (store, user, analysis) -> Eras.detect(store.workouts, user, analysis) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _signedIn = MutableStateFlow(repo.client.isAuthenticated)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Null while no sync is running. */
    private val _sync = MutableStateFlow<SyncProgress?>(null)
    val sync: StateFlow<SyncProgress?> = _sync.asStateFlow()

    private val _login = MutableStateFlow<LoginState>(LoginState.Idle)
    val login: StateFlow<LoginState> = _login.asStateFlow()

    /** One-off messages for a snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _userData.value = repo.loadUserData()
            _store.value = repo.load()
        }
    }

    fun consumeMessage() { _message.value = null }

    private fun describe(e: Throwable): String = when (e) {
        is GarminApiException -> if (e.status == 401 || e.status == 403) "Garmin rejected the session (HTTP ${e.status}). Sign in again." else e.message ?: "Garmin API error"
        is GarminAuthException -> e.message ?: "Authentication failed"
        is java.net.UnknownHostException -> "No internet connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun resetLogin() { _login.value = LoginState.Idle }

    fun completeLogin(ticket: String, flow: LoginFlow) {
        if (_login.value == LoginState.Exchanging) return
        _login.value = LoginState.Exchanging
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.client.signIn(ticket, flow)
                _signedIn.value = true
                _login.value = LoginState.Idle
                _message.value = "Connected to Garmin Connect"
            } catch (e: Exception) {
                _login.value = LoginState.Failed(describe(e))
            }
        }
    }

    /** Accepts python-garminconnect's garmin_tokens.json content. */
    fun importTokens(text: String) {
        val tokens = runCatching { GarminParsing.json.decodeFromString<GarminTokens>(text.trim()) }.getOrNull()
        if (tokens == null || tokens.di_token.isBlank()) {
            _message.value = "That doesn't look like a garmin_tokens.json (needs di_token)"
            return
        }
        repo.tokenStore.save(tokens)
        _signedIn.value = true
        _message.value = "Tokens imported"
    }

    fun signOut() {
        repo.client.signOut()
        CookieManager.getInstance().removeAllCookies(null)
        _signedIn.value = false
        _message.value = "Signed out"
    }

    fun clearData() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearData()
            _store.value = StoreFile()
            _message.value = "Local data deleted"
        }
    }

    fun sync(full: Boolean) {
        if (_sync.value != null) return
        _sync.value = SyncProgress("Starting…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _store.value = repo.sync(full) { _sync.value = it }
                _message.value = _sync.value?.message
            } catch (e: Exception) {
                // Whatever was saved before the failure is still worth showing.
                _store.value = repo.load()
                _message.value = "Sync failed: ${describe(e)}"
                if (e is GarminAuthException) _signedIn.value = repo.client.isAuthenticated
            } finally {
                _sync.value = null
            }
        }
    }

    fun updateSettings(s: UserSettings) {
        _settings.value = s
        settingsStore.save(s)
    }

    /** Apply a change to the user's groups/favorites/exclusions and persist it. */
    fun updateUserData(change: (UserData) -> UserData) {
        synchronized(_userData) { _userData.value = change(_userData.value) }
        viewModelScope.launch(Dispatchers.IO) {
            // Read the latest value under the lock so a slower earlier save can't overwrite a newer one.
            synchronized(_userData) { repo.saveUserData(_userData.value) }
        }
    }

    fun exportCsv(uri: Uri) = export(uri) { repo.exportCsv() }

    fun exportJson(uri: Uri) = export(uri) { repo.exportJson() }

    private fun export(uri: Uri, content: () -> String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)!!.use {
                        it.write(content().toByteArray())
                    }
                }
            }
            _message.value = result.fold({ "Exported" }, { "Export failed: ${describe(it)}" })
        }
    }
}
