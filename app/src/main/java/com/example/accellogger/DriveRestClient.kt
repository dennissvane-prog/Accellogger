package com.example.accellogger

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Google Drive v3 REST client using plain HttpURLConnection so no extra HTTP
 * dependency is needed beyond the org.json classes already bundled with Android.
 * Only creates/updates files inside a single app-owned folder (drive.file scope).
 */
class DriveRestClient(private val accessToken: String) {

    fun ensureFolder(cachedFolderId: String?): String? {
        if (cachedFolderId != null && folderExists(cachedFolderId)) {
            return cachedFolderId
        }

        findFolderId()?.let { return it }
        return createFolder()
    }

    fun findFile(fileName: String, parentId: String): String? {
        val query = "name='${escapeQueryValue(fileName)}' and '$parentId' in parents and trashed=false"
        val url = "$FILES_ENDPOINT?q=${encode(query)}&fields=files(id,name)&spaces=drive"

        return runCatching {
            val connection = openConnection(url, "GET")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val files = JSONObject(readBody(connection)).optJSONArray("files") ?: return@runCatching null
            if (files.length() == 0) null else files.getJSONObject(0).getString("id")
        }.getOrNull()
    }

    fun uploadNewFile(fileName: String, parentId: String, mimeType: String, content: ByteArray): String? {
        return runCatching {
            val boundary = "accellogger-${System.currentTimeMillis()}"
            val connection = openConnection("$UPLOAD_ENDPOINT?uploadType=multipart", "POST")
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            connection.doOutput = true

            val metadata = JSONObject().apply {
                put("name", fileName)
                put("parents", JSONArray().put(parentId))
            }

            connection.outputStream.use { it.write(buildMultipartBody(boundary, metadata, mimeType, content)) }
            if (connection.responseCode !in 200..299) return@runCatching null
            JSONObject(readBody(connection)).getString("id")
        }.getOrNull()
    }

    fun updateFileContent(fileId: String, mimeType: String, content: ByteArray): Boolean {
        return runCatching {
            // Android's HttpURLConnection rejects the literal PATCH verb, so use the
            // method-override header that the Google APIs explicitly support instead.
            val connection = openConnection("$UPLOAD_ENDPOINT/$fileId?uploadType=media", "POST")
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Content-Type", mimeType)
            connection.doOutput = true
            connection.outputStream.use { it.write(content) }
            connection.responseCode in 200..299
        }.getOrDefault(false)
    }

    private fun folderExists(folderId: String): Boolean {
        return runCatching {
            val connection = openConnection("$FILES_ENDPOINT/$folderId?fields=id,trashed", "GET")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false
            !JSONObject(readBody(connection)).optBoolean("trashed", false)
        }.getOrDefault(false)
    }

    private fun findFolderId(): String? {
        val query =
            "mimeType='application/vnd.google-apps.folder' and name='${escapeQueryValue(DriveSyncConstants.DRIVE_FOLDER_NAME)}'" +
                " and 'root' in parents and trashed=false"
        val url = "$FILES_ENDPOINT?q=${encode(query)}&fields=files(id,name)&spaces=drive"

        return runCatching {
            val connection = openConnection(url, "GET")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val files = JSONObject(readBody(connection)).optJSONArray("files") ?: return@runCatching null
            if (files.length() == 0) null else files.getJSONObject(0).getString("id")
        }.getOrNull()
    }

    private fun createFolder(): String? {
        return runCatching {
            val connection = openConnection(FILES_ENDPOINT, "POST")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.doOutput = true
            val body = JSONObject().apply {
                put("name", DriveSyncConstants.DRIVE_FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", JSONArray().put("root"))
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return@runCatching null
            JSONObject(readBody(connection)).getString("id")
        }.getOrNull()
    }

    private fun buildMultipartBody(
        boundary: String,
        metadata: JSONObject,
        mimeType: String,
        content: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        output.write("\r\n--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(content)
        output.write("\r\n--$boundary--".toByteArray(Charsets.UTF_8))
        return output.toByteArray()
    }

    private fun readBody(connection: HttpURLConnection): String {
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        return connection
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun escapeQueryValue(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
