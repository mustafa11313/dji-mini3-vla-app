package dji.sampleV5.aircraft.recorder

import dji.v5.utils.common.ContextUtil
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Sends a finished episode folder (telemetry, frames, setup.json, result.txt, ...) to the PC
 * as ONE zip file. Only call it after landing. The folder on the phone is never deleted.
 */
object EpisodeUploader {

    fun upload(dir: File, onDone: (String) -> Unit) {
        thread {
            val zip = File(ContextUtil.getContext().cacheDir, dir.name + ".zip")
            try {
                ZipOutputStream(zip.outputStream().buffered()).use { z ->
                    z.setLevel(0)                       // jpg is already compressed
                    dir.walkTopDown().filter { it.isFile }.forEach { f ->
                        z.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).path.replace('\\', '/')))
                        FileInputStream(f).use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
                val c = URL("${EpisodePlan.SERVER_BASE_URL}/upload?name=${dir.name}").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 120000
                c.setFixedLengthStreamingMode(zip.length())
                FileInputStream(zip).use { i -> c.outputStream.use { o -> i.copyTo(o) } }
                val code = c.responseCode
                val text = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                c.disconnect()
                if (code == 200) {
                    File(dir, "uploaded.txt").writeText("ok\n")
                    onDone("Uploaded ${dir.name}: $text")
                } else {
                    onDone("Upload FAILED ($code) ${dir.name}: $text")
                }
            } catch (e: Exception) {
                onDone("Upload FAILED ${dir.name}: ${e.message} (folder is still on the phone)")
            } finally {
                zip.delete()
            }
        }
    }
}
