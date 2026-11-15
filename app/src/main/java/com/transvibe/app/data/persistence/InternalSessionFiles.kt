package com.transvibe.app.data.persistence

import java.io.File

/** Removes only app-owned, per-job subtitle outputs; never exported copies or legacy JSON. */
internal class InternalSessionFiles(private val root: File) {
    fun deleteJobDirectory(jobId: String): Boolean {
        // Job IDs produced by this app are UUIDs. Older records may have opaque IDs:
        // do not use them to construct arbitrary filesystem paths.
        if (!jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return false
        val base = root.canonicalFile
        val folder = File(root, jobId)
        // Reject links and traversal to avoid recursive deletion outside sessions/.
        if (folder.canonicalFile != File(base, jobId)) return false
        return !folder.exists() || folder.deleteRecursively()
    }
}
