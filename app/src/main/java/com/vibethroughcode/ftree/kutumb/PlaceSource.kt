package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.geo.PlaceIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bundled list of towns and cities, read and parsed on first use and kept. Parsing is a few
 * hundred milliseconds of work, so it never happens on the main thread and never before somebody
 * actually opens a place search.
 */
class PlaceSource(private val read: () -> String) {
    private val lock = Mutex()
    @Volatile private var cached: PlaceIndex? = null

    suspend fun index(): PlaceIndex = cached ?: lock.withLock {
        cached ?: withContext(Dispatchers.IO) { PlaceIndex.parse(read()) }.also { cached = it }
    }
}
