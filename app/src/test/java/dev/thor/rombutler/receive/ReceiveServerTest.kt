package dev.thor.rombutler.receive

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class ReceiveServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: ReceiveServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `session page accepts a multipart upload`() {
        val targetDir = tempFolder.newFolder("received")
        val receivedNames = mutableListOf<String>()
        val port = ServerSocket(0).use { it.localPort }
        val token = "THOR42"
        server = ReceiveServer(port, targetDir, token, receivedNames::add).also {
            it.start(5_000, false)
        }

        val page = request("http://127.0.0.1:$port/$token/")
        assertThat(page.status).isEqualTo(HttpURLConnection.HTTP_OK)
        assertThat(page.body).contains("Thor ROM Butler")

        val denied = request("http://127.0.0.1:$port/")
        assertThat(denied.status).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND)

        val romBytes = byteArrayOf(1, 2, 3, 4, 5)
        val upload = upload(
            url = "http://127.0.0.1:$port/$token/upload",
            fileName = "game.gba",
            content = romBytes,
        )

        assertThat(upload.status).isEqualTo(HttpURLConnection.HTTP_OK)
        assertThat(upload.body).isEqualTo("OK: 1")
        assertThat(targetDir.resolve("game.gba").readBytes()).isEqualTo(romBytes)
        assertThat(receivedNames).containsExactly("game.gba")
    }

    @Test
    fun `chunk upload resumes and becomes visible only when complete`() {
        val targetDir = tempFolder.newFolder("resumable")
        val receivedNames = mutableListOf<String>()
        val port = ServerSocket(0).use { it.localPort }
        val token = "THOR42"
        val uploadId = "0123456789abcdef0123456789abcdef"
        server = ReceiveServer(port, targetDir, token, receivedNames::add).also {
            it.start(5_000, false)
        }

        val first = upload(
            url = "http://127.0.0.1:$port/$token/chunk",
            fileName = "large.7z",
            content = byteArrayOf(1, 2, 3),
            headers = chunkHeaders(uploadId, offset = 0, total = 5),
        )
        assertThat(first.status).isEqualTo(HttpURLConnection.HTTP_OK)
        assertThat(JSONObject(first.body).getLong("offset")).isEqualTo(3)
        assertThat(targetDir.resolve("large.7z").exists()).isFalse()

        val status = request("http://127.0.0.1:$port/$token/status?id=$uploadId")
        assertThat(JSONObject(status.body).getLong("offset")).isEqualTo(3)

        val completed = upload(
            url = "http://127.0.0.1:$port/$token/chunk",
            fileName = "large.7z",
            content = byteArrayOf(4, 5),
            headers = chunkHeaders(uploadId, offset = 3, total = 5),
        )
        assertThat(JSONObject(completed.body).getBoolean("complete")).isTrue()
        assertThat(targetDir.resolve("large.7z").readBytes())
            .isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(receivedNames).containsExactly("large.7z")
    }

    @Test
    fun `chunk upload reports the server offset after a stale retry`() {
        val targetDir = tempFolder.newFolder("offset-conflict")
        val port = ServerSocket(0).use { it.localPort }
        val token = "THOR42"
        val uploadId = "abcdef0123456789abcdef0123456789"
        server = ReceiveServer(port, targetDir, token) {}.also { it.start(5_000, false) }

        upload(
            url = "http://127.0.0.1:$port/$token/chunk",
            fileName = "game.zip",
            content = byteArrayOf(1, 2),
            headers = chunkHeaders(uploadId, offset = 0, total = 4),
        )
        val stale = upload(
            url = "http://127.0.0.1:$port/$token/chunk",
            fileName = "game.zip",
            content = byteArrayOf(1, 2),
            headers = chunkHeaders(uploadId, offset = 0, total = 4),
        )

        assertThat(stale.status).isEqualTo(HttpURLConnection.HTTP_CONFLICT)
        assertThat(JSONObject(stale.body).getLong("offset")).isEqualTo(2)
    }

    private fun request(url: String): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return connection.readResult()
    }

    private fun upload(
        url: String,
        fileName: String,
        content: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult {
        val boundary = "ThorRomButlerBoundary"
        val prefix = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val payload = prefix + content + suffix

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers.forEach(::setRequestProperty)
            setFixedLengthStreamingMode(payload.size)
        }
        connection.outputStream.use { it.write(payload) }
        return connection.readResult()
    }

    private fun HttpURLConnection.readResult(): HttpResult = try {
        val status = responseCode
        val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) errorStream else inputStream
        HttpResult(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } finally {
        disconnect()
    }

    private data class HttpResult(val status: Int, val body: String)

    private fun chunkHeaders(uploadId: String, offset: Long, total: Long): Map<String, String> =
        mapOf(
            "X-Thor-Upload-Id" to uploadId,
            "X-Thor-Offset" to offset.toString(),
            "X-Thor-Total-Size" to total.toString(),
        )
}
