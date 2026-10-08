package com.bitchat.android.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bitchat.android.util.AppIntegrity
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bitchat.android.hotspot.HotspotActivity
import com.bitchat.android.util.UniversalApkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ShareMethod { NEARBY, HOTSPOT }

object ShareApp {
    private const val TAG = "ShareApp"
    private const val APK_MIME = "application/vnd.android.package-archive"

    suspend fun prepareApk(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            UniversalApkManager(context.applicationContext).prepareLocalApkInfo()?.file
                ?.takeIf { it.isFile }
        } catch (e: Exception) {
            Log.w(TAG, "Could not prepare APK: ${e.message}")
            null
        }
    }

    fun share(context: Context, apk: File, method: ShareMethod) {
        when (method) {
            ShareMethod.NEARBY -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apk
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = APK_MIME
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(send, "Send SafeMesh").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            ShareMethod.HOTSPOT -> context.startActivity(
                Intent(context, HotspotActivity::class.java)
                    .putExtra(HotspotActivity.EXTRA_APK_PATH, apk.absolutePath)
            )
        }
    }
}

@Composable
fun ShareAppDialog(
    onShare: (ShareMethod) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isOfficial by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<AppIntegrity.ApkCheck?>(null) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            checking = true
            scope.launch {
                check = AppIntegrity.checkApk(context, uri)
                checking = false
            }
        }
    }
    LaunchedEffect(Unit) {
        isOfficial = withContext(Dispatchers.IO) { AppIntegrity.isOfficialInstall(context) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share SafeMesh") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("Give the app to someone nearby. No internet needed.")
                Button(
                    onClick = { onShare(ShareMethod.NEARBY) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Bluetooth / Quick Share") }
                OutlinedButton(
                    onClick = { onShare(ShareMethod.HOTSPOT) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Wi-Fi hotspot + QR code") }
                Text("Hotspot: they join your Wi-Fi, scan the QR code and download the app in their browser.")

                HorizontalDivider()
                Text("Verify", fontWeight = FontWeight.Bold)
                when (isOfficial) {
                    true -> Text(
                        "\u2713 This app reports it is official SafeMesh. A fake app could show this too, " +
                            "so check new APK files from a copy you already trust.",
                        color = VerifiedGreen
                    )
                    false -> Text("\u26A0 This app is NOT an official SafeMesh build", color = SosRed)
                    null -> Text("Checking this app\u2026")
                }
                AppIntegrity.officialCertSha256?.let {
                    Text(
                        "Official code: ${AppIntegrity.shortCode(it)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(
                    onClick = {
                        check = null
                        pickApk.launch(arrayOf(APK_MIME_TYPE, "application/octet-stream"))
                    },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (checking) "Checking\u2026" else "Check an APK file") }
                check?.let { ApkCheckResult(it) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ApkCheckResult(check: AppIntegrity.ApkCheck) {
    val (text, color) = when (check.verdict) {
        AppIntegrity.Verdict.OFFICIAL ->
            "\u2713 Real SafeMesh ${check.versionName.orEmpty()}. Safe to install." to VerifiedGreen
        AppIntegrity.Verdict.NOT_OFFICIAL ->
            "\u2717 FAKE: not signed by SafeMesh. Do not install." to SosRed
        AppIntegrity.Verdict.NOT_SAFEMESH ->
            "\u2717 This file is not SafeMesh." to SosRed
        AppIntegrity.Verdict.UNREADABLE ->
            "Could not read this file as an app." to SosRed
    }
    Text(text, color = color, fontWeight = FontWeight.Bold)
    check.fileSha256?.let {
        Text("File SHA-256: ${AppIntegrity.shortCode(it)}\u2026", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private val VerifiedGreen = Color(0xFF1B8A3A)
