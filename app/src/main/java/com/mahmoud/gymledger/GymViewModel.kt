package com.mahmoud.gymledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.LocalDate

class GymViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GymApp
    private val repo = app.repository
    private val prefs = app.getSharedPreferences("connection", 0)
    val routines = repo.dao.observeRoutines().map { list -> list.map { Json.routine(it.json) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workouts = repo.dao.observeWorkouts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val entries = repo.dao.observeEntries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cardio = repo.dao.observeCardio().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val wifi = app.wifi.ready
    val busy = MutableStateFlow(false)
    val saving = MutableStateFlow(0)
    val localError = MutableStateFlow("")
    val message = MutableStateFlow("")
    val connected = MutableStateFlow(prefs.getBoolean("connected", false))
    val lastRefresh = MutableStateFlow(prefs.getString("lastRefresh", "Never") ?: "Never")
    val authRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    @Volatile private var accessToken: String? = null
    private val client = SheetsClient({ accessToken }, app.wifi)
    private val engine = SyncEngine(repo, client) { app.wifi.allowed() }
    private val cardioEngine = CardioSyncEngine(repo, client) { app.wifi.allowed() }
    private val syncLock = Mutex()
    private val edits = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val retryLocalSave = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            try {
                val seed = withContext(Dispatchers.IO) {
                    app.assets.open("workbook.json").bufferedReader().use { SheetCodec.parse(JSONObject(it.readText())) }
                }
                repo.seed(seed)
            } catch (e: Exception) { message.value = "Couldn't load workouts: ${e.message}" }
        }
        viewModelScope.launch {
            for (edit in edits) {
                while (true) {
                    try { edit(); localError.value = ""; break }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        localError.value = "Couldn't save locally: ${e.message}. Keep the app open and retry."
                        retryLocalSave.receive()
                    }
                }
                saving.value--
            }
        }
        viewModelScope.launch {
            wifi.collect { ready ->
                if (ready && connected.value) {
                    authRequests.emit(false)
                }
            }
        }
    }

    private fun edit(action: suspend () -> Unit) {
        saving.value++
        if (!edits.trySend(action).isSuccess) {
            saving.value--
            message.value = "Local save failed. Keep the app open and retry."
        }
    }
    fun start(routine: Routine, date: String, open: (String) -> Unit) {
        edit { open(repo.start(routine, date)) }
    }
    fun field(id: String, row: Int, field: String, value: String) {
        if (InputRules.accepts(field, value)) edit { repo.dao.edit(id, row, field, value) }
    }
    fun done(id: String, row: Int, done: Boolean) = edit { repo.dao.markDone(id, row, done) }
    fun finish(id: String) = edit {
        repo.finish(id)
        message.value = "Workout finished and saved on your phone."
        sync()
    }
    fun startCardio(date: String, workoutId: String = "", open: (String) -> Unit) = edit {
        open(repo.startCardio(date, workoutId))
    }
    fun cardioField(id: String, field: String, value: String) {
        if (CardioRules.accepts(field, value)) edit { repo.dao.editCardio(id, field, value) }
    }
    fun finishCardio(id: String) = edit {
        repo.finishCardio(id)
        message.value = "Cardio finished and saved on your phone."
        sync()
    }
    fun requestConnect() {
        message.value = ""
        authRequests.tryEmit(true)
    }
    fun authFailed(error: String) {
        accessToken = null
        message.value = error
    }
    fun authorized(token: String) {
        accessToken = token
        connected.value = true
        prefs.edit().putBoolean("connected", true).apply()
        sync()
    }
    fun retry() {
        message.value = ""
        if (localError.value.isNotBlank()) { retryLocalSave.trySend(Unit); return }
        if (!app.wifi.allowed()) { message.value = "Waiting for Wi-Fi. Open the app on Wi-Fi to sync."; return }
        if (accessToken == null) authRequests.tryEmit(true) else sync()
    }

    private fun sync() {
        viewModelScope.launch {
            if (!app.wifi.allowed() || accessToken == null) return@launch
            syncLock.withLock {
                if (!app.wifi.allowed() || accessToken == null) return@withLock
                busy.value = true
                try {
                    engine.upload()
                    if (!app.wifi.allowed()) return@withLock
                    repo.refresh(client.read())
                    if (!app.wifi.allowed()) return@withLock
                    cardioEngine.upload()
                    if (!app.wifi.allowed()) return@withLock
                    repo.refreshCardio(client.readCardio())
                    val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
                    lastRefresh.value = now
                    prefs.edit().putString("lastRefresh", now).apply()
                } catch (e: CancellationException) { throw e }
                catch (e: AuthRequired) {
                    val rejectedToken = accessToken
                    accessToken = null
                    if (rejectedToken != null) {
                        try {
                            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(app)
                                .clearToken(com.google.android.gms.auth.api.identity.ClearTokenRequest.builder().setToken(rejectedToken).build()).await()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* A new interactive connection can retry clearing a rejected token. */ }
                    }
                    message.value = e.message.orEmpty()
                } catch (e: Exception) {
                    message.value = e.message ?: "Sync failed. Your workouts are saved locally."
                } finally { busy.value = false }
            }
        }
    }

    fun previous(workout: Workout): Workout? = workouts.value
        .filter { it.id != workout.id && it.sheetId == workout.sheetId && it.status in listOf("PENDING", "SYNCED", "IMPORTED") && it.createdAt < workout.createdAt }
        .maxByOrNull { it.createdAt }

    fun today() = LocalDate.now().toString()
}
