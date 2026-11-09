package bg.solar.nightcharge.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject = request("GET", url, null, headers)

    fun post(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        request("POST", url, body, headers)

    private fun request(method: String, url: String, body: JSONObject?, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 40000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return JSONObject(text)
            throw IOException("HTTP $code: ${text.take(300)}")
        } finally {
            c.disconnect()
        }
    }
}
