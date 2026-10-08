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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share SafeMesh") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
