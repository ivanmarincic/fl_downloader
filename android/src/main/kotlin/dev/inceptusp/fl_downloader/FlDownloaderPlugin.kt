package dev.inceptusp.fl_downloader

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.DownloadManager.Query
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class FlDownloaderPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "dev.inceptusp.fl_downloader")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    fun requestStoragePermission(): Boolean? {
        return if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                activity?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                }
                null
            }
        } else {
            true
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method == "download") {
            val downloadId = download(call.argument("url"), call.argument("headers"), call.argument("fileName"))
            CoroutineScope(Dispatchers.Default).launch {
                trackProgress(downloadId)
            }
            result.success(downloadId)
        } else if (call.method == "openFile") {
            val downloadId: Int? = call.argument("downloadId")
            val filePath: String? = call.argument("filePath")
            openFile(downloadId?.toLong(), filePath)
            result.success(null)
        } else if (call.method == "cancel") {
            val downloadIds: LongArray = call.argument("downloadIds")!!
            val canceledDownloads = cancelDownload(*downloadIds)
            result.success(canceledDownloads)
        } else if (call.method == "requestPermission") {
            val permission = requestStoragePermission()
            result.success(permission)
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == 1) {
            permissions.indexOfFirst {
                it == Manifest.permission.WRITE_EXTERNAL_STORAGE
            }.let {
                if (it != -1) {
                    if (grantResults[it] == PackageManager.PERMISSION_GRANTED) {
                        channel.invokeMethod("permissionResult", true)
                        return true
                    } else {
                        channel.invokeMethod("permissionResult", false)
                        return true
                    }
                }
            }
        }
        return false
    }

    fun download(url: String?, headers: Map<String, String>?, fileName: String?): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName ?: "/${uri.lastPathSegment}"
        )
        for (header in headers?.keys ?: emptyList()) {
            request.addRequestHeader(header, headers!![header])
        }
        return manager.enqueue(request)
    }

    fun openFile(downloadId: Long?, filePath: String?) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var downloadedTo: String? = filePath

        if (filePath == null) {
            val cursor = manager.query(Query().setFilterById(downloadId!!))
            if (cursor.moveToFirst()) {
                downloadedTo = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
            }
            cursor.close()
        }

        val authority = context.applicationContext.packageName + ".flDownloader.provider"
        val fileUri = Uri.parse(downloadedTo)
        val mimeMap = MimeTypeMap.getSingleton()
        val ext = MimeTypeMap.getFileExtensionFromUrl(fileUri.path)
        var type = mimeMap.getMimeTypeFromExtension(ext)
        if (type == null) type = "*/*"
        val uri = FileProvider.getUriForFile(context, authority, File(fileUri.path!!))

        context.startActivity(
                Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, type)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    fun cancelDownload(vararg downloadIds: Long): Int {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.remove(*downloadIds)
    }

    suspend fun trackProgress(downloadId: Long?) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var finishDownload = false
        var lastProgress = -1
        var progress = 0
        withContext(Dispatchers.Main) {
            channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to progress, "status" to 2))
        }
        val timerCoroutine = CoroutineScope(Dispatchers.Default).launch {
            SystemClock.sleep(15000)
            finishDownload = true;
            withContext(Dispatchers.Main) {
                channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to 0, "status" to 4))
            }
            manager.remove(downloadId!!)
        }
        while (!finishDownload) {
            val cursor: Cursor = manager.query(Query().setFilterById(downloadId!!))
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_FAILED -> {
                        finishDownload = true
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to 0, "status" to 4))
                        }
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        val total =
                                cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total >= 0) {
                            val downloaded =
                                    cursor.getLong(
                                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                    )
                            progress = (downloaded * 100L / total).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to progress, "status" to 3))
                                }
                            }
                        } else {
                            if (progress != lastProgress) {
                                lastProgress = 0
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to progress, "status" to 3))
                                }
                            }
                        }
                    }
                    DownloadManager.STATUS_PENDING -> {
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to 0, "status" to 2))
                        }
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        val total =
                                cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total >= 0) {
                            if (timerCoroutine.isActive) timerCoroutine.cancel()
                            val downloaded =
                                    cursor.getLong(
                                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                    )
                            if (total != 0L) {
                                progress = (downloaded * 100L / total).toInt()
                            }
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to progress, "status" to 1))
                                }
                            }
                        }
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val filePath = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                        progress = 100
                        finishDownload = true
                        if (timerCoroutine.isActive) timerCoroutine.cancel()
                        withContext(Dispatchers.Main) {
                            channel.invokeMethod("notifyProgress", mapOf("downloadId" to downloadId, "progress" to progress, "status" to 0, "filePath" to filePath))
                        }
                    }
                }
            }
            cursor.close()
        }
    }
}
