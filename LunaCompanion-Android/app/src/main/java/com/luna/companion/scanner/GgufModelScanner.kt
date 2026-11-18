package com.luna.companion.scanner

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class GgufModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val sizeLabel: String,
    val quantLabel: String,
    val mmprojPath: String?
)

/**
 * Scans shared storage (/sdcard/Luna/, Download/Luna/, Documents/Luna/)
 * and app-private model storage for local .gguf files. Automatically pairs
 * mmproj*.gguf vision projectors when present in the same directory.
 */
object GgufModelScanner {

    fun scanModels(context: Context): List<GgufModelInfo> {
        val candidateDirs = buildCandidateDirectories(context)
        val discoveredFiles = linkedMapOf<String, File>()

        for (dir in candidateDirs) {
            if (!dir.exists()) {
                runCatching { dir.mkdirs() }
            }
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                collectGgufRecursively(dir, depth = 0, maxDepth = 2, out = discoveredFiles)
            }
        }

        val allFiles = discoveredFiles.values.toList()
        val projectorFiles = allFiles.filter {
            it.name.lowercase(Locale.US).contains("mmproj")
        }

        return allFiles
            .filterNot { it.name.lowercase(Locale.US).contains("mmproj") }
            .sortedBy { it.name.lowercase(Locale.US) }
            .map { file ->
                val siblingProjector = projectorFiles.firstOrNull { proj ->
                    proj.parentFile?.absolutePath == file.parentFile?.absolutePath
                }
                GgufModelInfo(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    sizeLabel = formatSize(file.length()),
                    quantLabel = extractQuantTag(file.name),
                    mmprojPath = siblingProjector?.absolutePath
                )
            }
    }

    fun toJsonArray(models: List<GgufModelInfo>): String {
        val array = JSONArray()
        for (model in models) {
            array.put(JSONObject().apply {
                put("name", model.name)
                put("path", model.path)
                put("sizeBytes", model.sizeBytes)
                put("sizeLabel", model.sizeLabel)
                put("quantLabel", model.quantLabel)
                put("hasVision", model.mmprojPath != null)
                put("mmprojPath", model.mmprojPath ?: "")
            })
        }
        return array.toString()
    }

    private fun buildCandidateDirectories(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        val extRoot = Environment.getExternalStorageDirectory()
        if (extRoot != null) {
            dirs.add(File(extRoot, "Luna"))
            dirs.add(File(extRoot, "Download/Luna"))
            dirs.add(File(extRoot, "Documents/Luna"))
        }
        dirs.add(File("/sdcard/Luna"))
        context.getExternalFilesDir("models")?.let { dirs.add(it) }
        dirs.add(File(context.filesDir, "models"))
        return dirs.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun collectGgufRecursively(
        dir: File,
        depth: Int,
        maxDepth: Int,
        out: MutableMap<String, File>
    ) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.isFile && entry.name.endsWith(".gguf", ignoreCase = true) && entry.length() > 0L) {
                val key = runCatching { entry.canonicalPath }.getOrDefault(entry.absolutePath)
                out[key] = entry
            } else if (entry.isDirectory && depth < maxDepth) {
                collectGgufRecursively(entry, depth + 1, maxDepth, out)
            }
        }
    }

    private fun extractQuantTag(fileName: String): String {
        val upper = fileName.uppercase(Locale.US)
        val regex = Regex("(Q[2-8]_[0-9A-Z_]+|IQ[1-4]_[A-Z]+|F16|BF16)")
        return regex.find(upper)?.value ?: "GGUF"
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
