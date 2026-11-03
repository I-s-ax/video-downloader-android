# Video Downloader Android — Kotlin

Aplicación inicial Android nativa con Jetpack Compose.

Incluye:
- Pantalla de registro e inicio de sesión.
- Estado de aprobación manual.
- Formulario para pegar un enlace.
- Cliente HTTP básico.
- Integración inicial con Android DownloadManager para guardar un enlace devuelto por la API.

La API starter devuelve `EXTRACTOR_NOT_CONFIGURED` hasta que se implementen y prueben los adaptadores de extracción. No se ha incluido una URL de descarga ficticia.

## Abrir

1. Instala Android Studio.
2. Abre esta carpeta como proyecto.
3. Espera la sincronización de Gradle.
4. Cambia `API_BASE_URL` en `app/build.gradle.kts`.
5. Ejecuta en un dispositivo Android o emulador.

## Seguridad

- No pongas claves administrativas ni secretos del Worker en el APK.
- La app utiliza HTTPS en producción.
- Un token de usuario puede extraerse de un dispositivo comprometido; la autorización real se valida en el servidor.
- Para producción añade almacenamiento seguro del token con Android Keystore, verificación de correo y recuperación de contraseña.
