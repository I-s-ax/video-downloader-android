package com.example.videodownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppScreen() } }
    }
}

@Composable
private fun AppScreen() {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Regístrate o inicia sesión. Las cuentas requieren aprobación manual.") }
    var busy by remember { mutableStateOf(false) }

    suspend fun request(path: String, payload: JSONObject, auth: String = ""): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (auth.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $auth")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val text = stream.bufferedReader().use { it.readText() }
                JSONObject(text)
            } finally { connection.disconnect() }
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Video Downloader", style = MaterialTheme.typography.headlineMedium)
        Text("API privada · Acceso mediante aprobación")
        OutlinedTextField(email, { email = it }, label = { Text("Correo") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Contraseña (mínimo 12 caracteres)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                status = "Creando cuenta..."
                kotlinx.coroutines.MainScope().launch {
                    try {
                        val result = request("/auth/register", JSONObject().put("email", email).put("password", password).put("device_label", android.os.Build.MODEL))
                        status = result.optString("message", result.optString("error", "Solicitud completada"))
                    } catch (e: Exception) { status = "Error: ${e.message}" }
                    busy = false
                }
            }) { Text("Registrarme") }
            Button(enabled = !busy, onClick = {
                busy = true
                kotlinx.coroutines.MainScope().launch {
                    try {
                        val result = request("/auth/login", JSONObject().put("email", email).put("password", password).put("device_label", android.os.Build.MODEL))
                        token = result.optString("token")
                        status = if (token.isNotBlank()) "Sesión iniciada. Estado: ${result.getJSONObject("user").optString("status")}" else result.optString("error", "No se pudo iniciar sesión")
                    } catch (e: Exception) { status = "Error: ${e.message}" }
                    busy = false
                }
            }) { Text("Entrar") }
        }
        HorizontalDivider()
        Text("Descargar video", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(link, { link = it }, label = { Text("Pega el enlace público") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = token.isNotBlank() && !busy, onClick = {
            busy = true
            kotlinx.coroutines.MainScope().launch {
                try {
                    val result = request("/api/v1/extract", JSONObject().put("url", link), token)
                    status = result.optString("message", result.optString("error", "Respuesta recibida"))
                    // Cuando el extractor esté conectado, procesar aquí formats[0].download_url.
                    val downloadUrl = result.optJSONArray("formats")
                        ?.optJSONObject(0)?.optString("download_url").orEmpty()
                    if (downloadUrl.isNotBlank()) enqueueDownload(context, downloadUrl)
                } catch (e: Exception) { status = "Error: ${e.message}" }
                busy = false
            }
        }) { Text(if (busy) "Procesando..." else "Obtener enlace") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (token.isNotBlank()) {
            OutlinedButton(onClick = {
                token = ""
                status = "Sesión cerrada localmente. Implementa /auth/logout para revocación remota."
            }) { Text("Cerrar sesión") }
        }
    }
}

private fun enqueueDownload(context: Context, downloadUrl: String) {
    val uri = Uri.parse(downloadUrl)
    require(uri.scheme == "https") { "La descarga debe usar HTTPS" }
    val request = DownloadManager.Request(uri)
        .setTitle("Video descargado")
        .setDescription("Descargando desde la aplicación")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "video_${System.currentTimeMillis()}.mp4")
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        // La CDN de TikTok rechaza (403) las peticiones sin encabezados de navegador.
        .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
        .addRequestHeader("Referer", "https://www.tiktok.com/")
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.enqueue(request)
}
