package com.javierrb35.bibliotecaia.updates

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Installs only the official, checksum-verified and same-certificate signed APK.
 * The app downloads privately; Android's installer ALWAYS handles final consent.
 * No browser, OAuth, Drive access or silent package installation.
 */
internal object UpdateInstaller {
    private const val MAX_APK_BYTES = 256L * 1024 * 1024
    private const val MAX_SHA_BYTES = 256
    private const val TIMEOUT_MS = 20_000
    private val running = AtomicBoolean(false)
    private data class PendingPermission(val apk: File, val expectedSha: String)
    private var pendingPermission: PendingPermission? = null

    fun start(activity: Activity, release: UpdateChecker.ReleaseInfo) {
        val owner = activity as? LifecycleOwner ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(activity, "Ya se está preparando una actualización.",
                Toast.LENGTH_SHORT).show()
            return
        }

        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val status = TextView(activity).apply {
            text = "Descargando la actualización oficial…"
            textSize = 15f
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(status)
            addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Instalar Biblioteca IA " + release.versionName)
            .setView(content)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        var job: Job? = null
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                job?.cancel()
                dialog.dismiss()
            }
        }
        dialog.show()
        job = owner.lifecycleScope.launch {
            try {
                val verified = prepare(activity.applicationContext, release) { copied, total ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        bar.isIndeterminate = total <= 0
                        if (total > 0) bar.progress =
                            (copied * 100L / total).toInt().coerceIn(0, 100)
                        status.text = if (total > 0)
                            "Descargando… " + (copied * 100L / total).coerceIn(0, 100) + "%"
                        else "Descargando y verificando la actualización…"
                    }
                }
                if (!activity.isFinishing && !activity.isDestroyed) {
                    status.text = "Firma y APK verificadas."
                    dialog.dismiss()
                    requestSystemInstall(activity, verified.first, verified.second)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                dialog.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    AlertDialog.Builder(activity)
                        .setTitle("No se puede instalar la actualización")
                        .setMessage(e.message ?: "No se ha podido verificar la APK oficial.")
                        .setPositiveButton(R.string.dialog_ok, null).show()
                }
            } finally {
                running.set(false)
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    /** Called by UpdateRuntime when returning from system's "install unknown apps" page. */
    fun resumeAfterPermission(activity: Activity) {
        val current = pendingPermission ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()) return
        val owner = activity as? LifecycleOwner ?: return
        pendingPermission = null
        owner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    verifyCachedHash(current.apk, current.expectedSha)
                    verifySignedArchive(activity.applicationContext, current.apk)
                }
                if (!activity.isFinishing && !activity.isDestroyed) {
                    launchSystemInstaller(activity, current.apk)
                }
            } catch (e: Exception) {
                Toast.makeText(activity,
                    "No se pudo verificar la APK. Vuelve a pulsar «Instalar».",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun prepare(
        context: android.content.Context,
        release: UpdateChecker.ReleaseInfo,
        progress: suspend (Long, Long) -> Unit
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        val apkName = UpdatePayloadPolicy.assetName(release)
        val shaText = downloadText(release.sha256Url)
        val expected = UpdatePayloadPolicy.parseChecksum(shaText, apkName)
        val dir = File(context.cacheDir, "updates").canonicalFile
        check(dir.isDirectory || dir.mkdirs()) { "No hay espacio privado para la descarga." }
        val destination = File(dir, apkName).canonicalFile
        require(destination.parentFile == dir) { "Ruta no permitida para la actualización." }

        if (destination.isFile && runCatching {
            verifyCachedHash(destination, expected)
            verifySignedArchive(context, destination)
        }.isSuccess) {
            withContext(Dispatchers.Main) { progress(destination.length(), destination.length()) }
            return@withContext destination to expected
        }

        // PackageManager parses the staged APK before it is promoted to cache.
        val temp = File.createTempFile(".downloading-", ".apk", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = openOfficialConnection(release.apkUrl)
            try {
                val declared = connection.contentLengthLong
                require(declared <= MAX_APK_BYTES) {
                    "La APK supera el límite de descarga segura."
                }
                var count = 0L
                var lastUpdate = 0L
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.use { source ->
                    temp.outputStream().use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            count += n
                            require(count <= MAX_APK_BYTES) {
                                "La APK supera el límite de descarga segura."
                            }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            if (count - lastUpdate >= 1024 * 1024) {
                                lastUpdate = count
                                withContext(Dispatchers.Main) { progress(count, declared) }
                            }
                        }
                    }
                }
                require(count > 0 && (declared < 0 || declared == count) &&
                    UpdatePayloadPolicy.isExpectedHash(digest.digest(), expected)) {
                    "La descarga está incompleta o no coincide con el SHA-256 oficial."
                }
            } finally {
                connection.disconnect()
            }
            verifySignedArchive(context, temp)
            if (destination.exists() && !destination.delete()) {
                throw IOException("No se puede reemplazar la descarga anterior.")
            }
            if (!temp.renameTo(destination)) throw IOException(
                "No se pudo guardar la APK verificada.")
            withContext(Dispatchers.Main) { progress(destination.length(), destination.length()) }
            destination to expected
        } finally {
            temp.delete()
        }
    }

    private fun openOfficialConnection(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https" &&
            UpdatePayloadPolicy.allowedDownloadHost(parsed.host)) {
            "La descarga no utiliza una conexión oficial segura."
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "BibliotecaIA-Android-Installer")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.url.protocol != "https" ||
                !UpdatePayloadPolicy.allowedDownloadHost(connection.url.host)) {
                throw IOException("GitHub no ha devuelto la APK oficial.")
            }
            return connection
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    private fun downloadText(url: String): String {
        val connection = openOfficialConnection(url)
        try {
            val input = connection.inputStream
            val out = ByteArrayOutputStream()
            input.use {
                val buffer = ByteArray(128)
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= MAX_SHA_BYTES) {
                        "El manifiesto de integridad es demasiado grande."
                    }
                    out.write(buffer, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyCachedHash(file: File, expected: String) {
        require(file.isFile && file.length() in 1..MAX_APK_BYTES) {
            "No existe una APK completa."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        require(UpdatePayloadPolicy.isExpectedHash(digest.digest(), expected)) {
            "La APK descargada no coincide con el SHA-256 publicado."
        }
    }

    @Suppress("DEPRECATION")
    private fun verifySignedArchive(context: android.content.Context, apk: File) {
        val pm = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, flag)
            ?: throw IllegalArgumentException("El archivo descargado no es una APK válida.")
        val installed = pm.getPackageInfo(context.packageName, flag)
        require(downloaded.packageName == context.packageName) {
            "La APK no pertenece a Biblioteca IA."
        }
        val candidateVersion = if (Build.VERSION.SDK_INT >= 28)
            downloaded.longVersionCode else downloaded.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= 28)
            installed.longVersionCode else installed.versionCode.toLong()
        require(candidateVersion > installedVersion) {
            "La APK no es posterior a la instalada."
        }
        val currentSigners = signerDigests(installed)
        val newSigners = signerDigests(downloaded)
        require(currentSigners.isNotEmpty() && newSigners == currentSigners) {
            "La firma de esta APK no coincide con la aplicación instalada. No la desinstales."
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()
    }

    private fun requestSystemInstall(activity: Activity, apk: File, expectedSha: String) {
        if (Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()) {
            pendingPermission = PendingPermission(apk, expectedSha)
            AlertDialog.Builder(activity)
                .setTitle("Permitir instalación desde Biblioteca IA")
                .setMessage("Android necesita tu autorización para instalar esta actualización. " +
                    "Activa «Permitir desde esta fuente»; después volverás a la instalación.")
                .setPositiveButton("Autorizar e instalar") { _, _ ->
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.packageName)))
                    } catch (_: Exception) {
                        pendingPermission = null
                        Toast.makeText(activity, "No se pudieron abrir los permisos del sistema.",
                            Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                    pendingPermission = null
                }.show()
        } else {
            launchSystemInstaller(activity, apk)
        }
    }

    private fun launchSystemInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity,
            activity.packageName + ".updates.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(activity.contentResolver,
            "Actualización de Biblioteca IA", uri)
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("Instalador no disponible")
                .setMessage("Android no ha podido abrir su instalador. Comprueba que " +
                    "están permitidas las instalaciones desde Biblioteca IA.")
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
        }
    }
}
