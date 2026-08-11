package com.amr3d.preview.pro

/**
 * Lightweight in-memory handoff between the existing 2D viewer/Slicer and Nesting.
 * No DXF is duplicated on disk.
 */
object NestingSession {
    var model: DxfModel? = null
    var sourceName: String = ""
    var sourceUri: android.net.Uri? = null

    fun clear() {
        model = null
        sourceName = ""
        sourceUri = null
    }
}
