package dev.mcandle.uwbpos.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 세 파일 저장·공유 — `files/logs/{events,pos,cycles}_yyyyMMdd_HHmmss.*`. 손님 앱 `MainActivity.exportCsv` 와 같은 FileProvider 공유.
 * 파일명 규칙은 시뮬레이터와 같다 — 손님 앱 `docs/logs/` 에 접미만 붙여 복사한다.
 */
object LogExport {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun dir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    fun saveEvents(context: Context, csv: String, at: Date = Date()): File = write(context, "events_${stamp.format(at)}.csv", csv)
    fun saveActivity(context: Context, text: String, at: Date = Date()): File = write(context, "pos_${stamp.format(at)}.txt", text)
    fun saveCycles(context: Context, csv: String, at: Date = Date()): File = write(context, "cycles_${stamp.format(at)}.csv", csv)

    private fun write(context: Context, name: String, body: String): File =
        File(dir(context), name).apply { writeText(body, Charsets.UTF_8) }.also { prune(context) }

    /** 종류별 최근 10개만 */
    private fun prune(context: Context) {
        for (prefix in listOf("events_", "pos_", "cycles_")) {
            dir(context).listFiles { f -> f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        }
    }

    /** Share sheet — 여러 파일 */
    fun share(context: Context, files: List<File>): Result<Unit> = runCatching {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, files.joinToString(", ") { it.name })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "POS 로그 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
