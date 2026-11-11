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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// User-Agent único para todo TikTok: la app, la API Aweme y el Worker usan
// exactamente este. TikTok asocia la URL firmada a esta identidad y la CDN
// rechaza la descarga (403) si cambia entre la consulta y la descarga.
private const val TIKTOK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

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
                    // 1. Extracción nativa en el teléfono (sin backend). Si
                    // falla, respaldo: extracción por el Worker como antes.
                    status = "Extrayendo en el teléfono..."
                    val downloadUrl = try {
                        extractTikTokNativeUrl(link)
                    } catch (e: Exception) {
                        status = "La extracción en el teléfono falló (${e.message}). Probando con el servidor..."
                        val result = request("/api/v1/extract", JSONObject().put("url", link), token)
                        result.optJSONArray("formats")
                            ?.optJSONObject(0)?.optString("download_url").orEmpty()
                    }
                    if (downloadUrl.isNotBlank()) {
                        status = "Resolviendo enlace en el teléfono..."
                        val downloadId = resolveAndDownload(context, downloadUrl)
                        status = "Descarga en cola, verificando..."
                        kotlinx.coroutines.MainScope().launch {
                            watchDownload(context, downloadId) { status = it }
                        }
                    } else {
                        status = "No se pudo obtener el enlace del video."
                    }
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

// Extracción nativa de TikTok en el teléfono, sin backend (como hace
// MultiDownloads según el PCAP: vm.tiktok.com -> www.tiktok.com ->
// API Aweme -> CDN v19). Al extraer desde el mismo móvil que descarga:
//  * TikTok firma la URL para la IP del teléfono (no hay 403 por IP).
//  * Las cookies de la sesión se retienen de forma nativa.
//  * No hay discordancia de User-Agent: todo usa TIKTOK_USER_AGENT.
// El inicio de sesión sigue pasando por el Worker de Cloudflare; solo el
// motor de extracción corre en el teléfono. Devuelve la URL directa del
// video (play_addr, con https forzado). Lanza Exception si no la obtiene.
private suspend fun extractTikTokNativeUrl(link: String): String =
    withContext(Dispatchers.IO) {
        // 1. Resolver el enlace corto siguiendo las redirecciones a mano
        // (como resolveAndDownload) y capturando las cookies de sesión.
        var finalUrl = link
        val cookies = mutableMapOf<String, String>()
        fun cookieHeader(): String =
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        var current = link
        var hops = 0
        while (hops < 5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", TIKTOK_USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookieHeader())
            try {
                connection.connect()
                connection.headerFields["Set-Cookie"]?.forEach { raw ->
                    val pair = raw.substringBefore(";").trim()
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=", "")
                    if (name.isNotBlank()) cookies[name] = value
                }
                val redirect = connection.getHeaderField("Location")
                if (connection.responseCode in 300..399 && !redirect.isNullOrBlank()) {
                    current = URL(URL(current), redirect).toString()
                    finalUrl = current
                    hops++
                } else {
                    break
                }
            } finally {
                connection.disconnect()
            }
        }

        // 2. Extraer el ID del video de la URL final.
        val videoId = Regex("video/(\\d+)").find(finalUrl)?.groupValues?.get(1)
            ?: throw Exception("No se pudo obtener el ID del video")

        // 3. Consultar la API Aweme DIRECTAMENTE DESDE EL MÓVIL, con
        // reintento en 3 hosts (igual que el Worker). Así TikTok firma el
        // video para la IP actual del teléfono.
        val hosts = listOf(
            "api16-normal-c-useast1a.tiktokv.com",
            "api19-normal-c-useast1a.tiktokv.com",
            "api22-normal-c-useast1a.tiktokv.com",
        )
        var lastError = "Video no encontrado"
        for (host in hosts) {
            try {
                val apiConn = URL("https://$host/aweme/v1/feed/?aweme_id=$videoId").openConnection() as HttpURLConnection
                apiConn.connectTimeout = 10000
                apiConn.readTimeout = 10000
                apiConn.setRequestProperty("User-Agent", TIKTOK_USER_AGENT)
                if (cookies.isNotEmpty()) apiConn.setRequestProperty("Cookie", cookieHeader())
                try {
                    if (apiConn.responseCode !in 200..299) {
                        lastError = "TikTok respondió ${apiConn.responseCode}"
                        delay(1500)
                        continue
                    }
                    val json = JSONObject(apiConn.inputStream.bufferedReader().use { it.readText() })
                    val awemeList = json.optJSONArray("aweme_list")
                    if (awemeList == null || awemeList.length() == 0) {
                        lastError = "Video no encontrado"
                        continue
                    }
                    val video = awemeList.getJSONObject(0).optJSONObject("video")
                    if (video == null) {
                        lastError = "Video no encontrado"
                        continue
                    }
                    val urlList = video.optJSONObject("play_addr")?.optJSONArray("url_list")
                    if (urlList == null || urlList.length() == 0) {
                        lastError = "Video no encontrado"
                        continue
                    }
                    var playAddr = urlList.getString(0)
                    if (playAddr.startsWith("http://")) {
                        playAddr = "https://" + playAddr.removePrefix("http://")
                    }
                    return@withContext playAddr
                } finally {
                    apiConn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Error de red"
                delay(1500)
            }
        }
        throw Exception(lastError)
    }

// Resuelve la redirección (301/302/...) de la URL de TikTok usando la
// conexión del propio teléfono y luego encola la descarga con la URL final.
// Esto es necesario por dos motivos:
//  1. Las URLs firmadas de TikTok están atadas a la IP que las pidió: si la
//     redirección la resuelve el Worker, la URL final queda firmada con la
//     IP de Cloudflare y el teléfono recibe 403. Resuelta en el móvil, la
//     firma es con la IP del teléfono.
//  2. El DownloadManager es un proceso del sistema: al seguir él mismo la
//     redirección entre dominios elimina el User-Agent y la CDN responde 403.
// Solo se hace una petición ligera (sin descargar el video): se lee la
// cabecera Location y se cierra la conexión. Si algo falla, se usa la URL
// original como respaldo. Devuelve el id de la descarga en cola.
private suspend fun resolveAndDownload(context: Context, initialUrl: String): Long =
    withContext(Dispatchers.IO) {
        val userAgent = TIKTOK_USER_AGENT
        var finalUrl = initialUrl
        // Cookies de sesión que TikTok envía durante las redirecciones: se
        // capturan en cada salto y se reenvían en el siguiente y en la
        // descarga final. La identidad (User-Agent) debe ser la misma que
        // usa el Worker al consultar la API (ver tiktok.ts).
        val cookies = mutableMapOf<String, String>()
        fun cookieHeader(): String =
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        try {
            var current = initialUrl
            var hops = 0
            while (hops < 5) {
                val connection = URL(current).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", "https://www.tiktok.com/")
                if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookieHeader())
                try {
                    connection.connect()
                    // Capturar todas las cookies de esta respuesta.
                    connection.headerFields["Set-Cookie"]?.forEach { raw ->
                        val pair = raw.substringBefore(";").trim()
                        val name = pair.substringBefore("=").trim()
                        val value = pair.substringAfter("=", "")
                        if (name.isNotBlank()) cookies[name] = value
                    }
                    val redirect = connection.getHeaderField("Location")
                    if (connection.responseCode in 300..399 && !redirect.isNullOrBlank()) {
                        current = URL(URL(current), redirect).toString()
                        finalUrl = current
                        hops++
                    } else {
                        break
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            finalUrl = initialUrl
        }
        val uri = Uri.parse(finalUrl)
        require(uri.scheme == "https") { "La descarga debe usar HTTPS" }
        val request = DownloadManager.Request(uri)
            .setTitle("Video descargado")
            .setDescription("Descargando desde la aplicación")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "video_${System.currentTimeMillis()}.mp4")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            // La CDN de TikTok rechaza (403) las peticiones sin encabezados de navegador.
            .addRequestHeader("User-Agent", userAgent)
            .addRequestHeader("Referer", "https://www.tiktok.com/")
        if (cookies.isNotEmpty()) request.addRequestHeader("Cookie", cookieHeader())
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
    }

// Consulta el estado real de la descarga para saber el motivo exacto del
// fallo (por ejemplo, el código HTTP de la CDN) en lugar de adivinarlo.
private suspend fun watchDownload(context: Context, downloadId: Long, onUpdate: (String) -> Unit) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    repeat(30) {
        delay(2000)
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (it.moveToFirst()) {
                val downloadStatus = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                when (downloadStatus) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onUpdate("Descarga completada. Revisa la carpeta Descargas.")
                        return
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val http = if (reason >= 400) " (código HTTP de la CDN)" else ""
                        onUpdate("Descarga fallida. Estado: $downloadStatus, motivo: $reason$http.")
                        return
                    }
                    DownloadManager.STATUS_RUNNING -> onUpdate("Descargando video...")
                    DownloadManager.STATUS_PAUSED -> onUpdate("Descarga en pausa. Motivo: $reason.")
                }
            }
        }
    }
    onUpdate("La descarga sigue en curso después de 60 s. Revisa la notificación de Descargas.")
}
