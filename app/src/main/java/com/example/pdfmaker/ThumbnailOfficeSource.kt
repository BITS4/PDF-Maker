package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads only the bounded OOXML parts needed for a first-page thumbnail. */
internal object ThumbnailOfficeSource {
    fun renderWord(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap {
        var texts = emptyList<String>()
        var image: Bitmap? = null
        try {
            scanArchive(file, checkCancellation) { name, budget, input ->
                when {
                    name == WORD_DOCUMENT && texts.isEmpty() -> {
                        texts = ThumbnailXmlParser.paragraphs(budget.readXml(input), MAX_WORD_PARAGRAPHS, checkCancellation)
                        true
                    }

                    name.startsWith(WORD_MEDIA) && image == null -> {
                        image = decodeMedia(budget, input, sizePx)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
            checkCancellation()
            return ThumbnailCanvasRenderer.document(
                sizePx = sizePx,
                badgeLabel = "DOCX",
                badgeColor = Color.parseColor("#1565C0"),
                texts = texts,
                image = image,
            )
        } finally {
            recycleIfNeeded(image)
        }
    }

    fun renderPresentation(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap {
        var texts = emptyList<String>()
        var image: Bitmap? = null
        try {
            scanArchive(file, checkCancellation) { name, budget, input ->
                when {
                    name == FIRST_SLIDE && texts.isEmpty() -> {
                        texts = ThumbnailXmlParser.paragraphs(budget.readXml(input), MAX_SLIDE_PARAGRAPHS, checkCancellation)
                        true
                    }

                    name.startsWith(PRESENTATION_MEDIA) && image == null -> {
                        image = decodeMedia(budget, input, sizePx)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
            checkCancellation()
            return ThumbnailCanvasRenderer.presentation(sizePx, texts, image)
        } finally {
            recycleIfNeeded(image)
        }
    }

    fun renderSpreadsheet(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap {
        var sharedStringsXml: String? = null
        var firstSheetXml: String? = null
        scanArchive(file, checkCancellation) { name, budget, input ->
            when {
                name == SHARED_STRINGS && sharedStringsXml == null -> {
                    sharedStringsXml = budget.readXml(input)
                    true
                }

                name == FIRST_SHEET && firstSheetXml == null -> {
                    firstSheetXml = budget.readXml(input)
                    true
                }

                else -> {
                    false
                }
            }
        }
        val strings = sharedStringsXml?.let { xml -> ThumbnailXmlParser.sharedStrings(xml, checkCancellation = checkCancellation) }.orEmpty()
        val rows =
            firstSheetXml
                ?.let { xml -> ThumbnailXmlParser.sheetRows(xml, strings, checkCancellation = checkCancellation) }
                .orEmpty()
        checkCancellation()
        return ThumbnailCanvasRenderer.table(
            sizePx = sizePx,
            rows = rows,
            badgeLabel = "XLSX",
            headerColor = Color.parseColor("#2E7D32"),
            badgeColor = Color.parseColor("#1B5E20"),
        )
    }

    private fun scanArchive(
        file: File,
        checkCancellation: () -> Unit,
        consumeEntry: (String, ViewerArchiveBudget, InputStream) -> Boolean,
    ) {
        val budget = ViewerArchiveBudget()
        ZipInputStream(file.inputStream().buffered()).use { archive ->
            val checkedInput = CancellationCheckingInputStream(archive, checkCancellation)
            var entry = archive.nextEntry
            while (entry != null) {
                checkCancellation()
                budget.beginEntry(entry.name)
                if (!consumeEntry(entry.name, budget, checkedInput)) budget.skipEntry(checkedInput)
                archive.closeEntry()
                entry = archive.nextEntry
            }
        }
    }

    private fun decodeMedia(
        budget: ViewerArchiveBudget,
        input: InputStream,
        sizePx: Int,
    ): Bitmap? =
        ThumbnailInput.decodeImage(
            budget.readEntry(input, ViewerResourceLimits.MAX_MEDIA_BYTES),
            sizePx,
        )

    private fun recycleIfNeeded(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private class CancellationCheckingInputStream(
        input: InputStream,
        private val checkCancellation: () -> Unit,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            checkCancellation()
            return super.read()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            checkCancellation()
            return super.read(buffer, offset, length)
        }

        override fun close() = Unit
    }

    private const val WORD_DOCUMENT = "word/document.xml"
    private const val WORD_MEDIA = "word/media/"
    private const val FIRST_SLIDE = "ppt/slides/slide1.xml"
    private const val PRESENTATION_MEDIA = "ppt/media/"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val FIRST_SHEET = "xl/worksheets/sheet1.xml"
    private const val MAX_WORD_PARAGRAPHS = 10
    private const val MAX_SLIDE_PARAGRAPHS = 6
}
