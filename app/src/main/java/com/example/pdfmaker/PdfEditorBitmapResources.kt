package com.example.pdfmaker

import android.graphics.Bitmap
import java.util.Collections
import java.util.IdentityHashMap

internal fun annotationBitmaps(
    annotations: List<PageAnnotations>,
    liveSignatures: List<LiveSignature>,
): List<Bitmap> =
    annotations.flatMap { page -> page.signatures.map(SignatureOverlay::bitmap) } +
        liveSignatures.map(LiveSignature::bitmap)

internal fun recycleDistinctBitmaps(bitmaps: Iterable<Bitmap>) {
    val distinct = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
    bitmaps.forEach { bitmap ->
        if (distinct.add(bitmap) && !bitmap.isRecycled) bitmap.recycle()
    }
}

internal fun snapshotAnnotations(source: List<PageAnnotations>): List<PageAnnotations> =
    source.map { page ->
        PageAnnotations().also { snapshot ->
            snapshot.strokes = page.strokes.toList()
            snapshot.texts = page.texts.toList()
            snapshot.signatures = page.signatures.toList()
        }
    }
