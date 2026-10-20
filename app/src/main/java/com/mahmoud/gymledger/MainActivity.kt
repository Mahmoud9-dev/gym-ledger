package com.mahmoud.gymledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private val model: GymViewModel by viewModels()
    private var authorizing = false
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        authorizing = false
        try {
            val auth = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
            val token = auth.accessToken ?: throw AuthRequired()
            model.authorized(token)
        } catch (_: Exception) { model.authFailed("Google connection wasn't completed. Your workouts remain saved locally. Tap Connect Google to retry.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { GymLedger(model) }
        lifecycleScope.launch { model.authRequests.collect { authorize(it) } }
    }

    private suspend fun authorize(interactive: Boolean) {
        if (authorizing) return
        if (!(application as GymApp).wifi.allowed()) {
            if (interactive) model.authFailed("Connect to Wi-Fi first. Your workouts are saved locally.")
            return
        }
        authorizing = true
        try {
            val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SHEETS_SCOPE))).build()
            val result = Identity.getAuthorizationClient(this).authorize(request).await()
            if (result.hasResolution()) {
                if (interactive) authorization.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build())
                else {
                    authorizing = false
                    model.authFailed("Tap Connect Google to renew sheet access. Your workouts are saved locally.")
                }
            } else {
                authorizing = false
                model.authorized(result.accessToken ?: throw AuthRequired())
            }
        } catch (e: Exception) {
            authorizing = false
            model.authFailed("Google connection failed. Check the OAuth setup and signing SHA-1 in the setup guide, then retry. (${e.javaClass.simpleName})")
        }
    }

    override fun onStart() {
        super.onStart()
        (application as GymApp).wifi.foreground = true
    }
    override fun onStop() {
        (application as GymApp).wifi.foreground = false
        super.onStop()
    }
}
