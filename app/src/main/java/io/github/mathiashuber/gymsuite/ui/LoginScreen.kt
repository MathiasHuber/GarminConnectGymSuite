package io.github.mathiashuber.gymsuite.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.mathiashuber.gymsuite.garmin.LoginFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps fetch/XHR on Garmin's SSO page and reports the serviceTicketId from the
 * login API's JSON answer. This is a second way to catch the ticket, next to
 * watching for the redirect to "<service>?ticket=ST-...".
 */
private const val TICKET_HOOK_JS = """
(function () {
  if (window.__gymSuiteHooked) return;
  window.__gymSuiteHooked = true;
  function check(text) {
    try {
      var m = /"serviceTicketId"\s*:\s*"(ST-[^"]+)"/.exec(text || "");
      if (m && window.GymSuiteBridge) window.GymSuiteBridge.onTicket(m[1]);
    } catch (e) {}
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function () {
      return origFetch.apply(this, arguments).then(function (resp) {
        try { resp.clone().text().then(check); } catch (e) {}
        return resp;
      });
    };
  }
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener("load", function () {
      try { if (!this.responseType || this.responseType === "text") check(this.responseText); } catch (e) {}
    });
    return origSend.apply(this, arguments);
  };
})();
"""

private fun ticketFrom(uri: Uri): String? = runCatching {
    val host = uri.host ?: return null
    if (!(host == "garmin.com" || host.endsWith(".garmin.com"))) return null
    uri.getQueryParameter("ticket")?.takeIf { it.startsWith("ST-") }
}.getOrNull()

private class TicketBridge(private val onTicket: (String) -> Unit) {
    @JavascriptInterface
    fun onTicket(ticket: String) {
        if (ticket.startsWith("ST-")) onTicket.invoke(ticket)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: AppViewModel, flow: LoginFlow, onDone: () -> Unit) {
    val login by vm.login.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    // The ticket can arrive from the redirect and the JS hook; use it once.
    val captured = remember { AtomicReference<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(Unit) { vm.resetLogin() }
    LaunchedEffect(signedIn, login) {
        if (signedIn && login == LoginState.Idle && captured.get() != null) onDone()
    }

    val deliver: (String) -> Unit = { ticket ->
        if (captured.compareAndSet(null, ticket)) main.post { vm.completeLogin(ticket, flow) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(flow.label) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        addJavascriptInterface(TicketBridge(deliver), "GymSuiteBridge")
                        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        if (docStart) {
                            WebViewCompat.addDocumentStartJavaScript(this, TICKET_HOOK_JS, setOf("https://sso.garmin.com"))
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val ticket = ticketFrom(request.url) ?: return false
                                deliver(ticket)
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                val ticket = url?.let { ticketFrom(Uri.parse(it)) }
                                if (ticket != null) {
                                    view.stopLoading()
                                    deliver(ticket)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!docStart && url?.startsWith("https://sso.garmin.com") == true) {
                                    view.evaluateJavascript(TICKET_HOOK_JS, null)
                                }
                            }
                        }
                        loadUrl(flow.signInUrl)
                    }
                },
                onRelease = { it.destroy() },
            )

            when (val s = login) {
                LoginState.Exchanging -> Overlay {
                    CircularProgressIndicator()
                    Text("Connecting to Garmin Connect…")
                }
                is LoginState.Failed -> Overlay {
                    Text("Sign-in failed", style = MaterialTheme.typography.titleMedium)
                    Text(s.message, textAlign = TextAlign.Center)
                    Text(
                        "If this keeps happening, try the other sign-in option or import tokens on the home screen.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onDone) { Text("Back") }
                }
                LoginState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
