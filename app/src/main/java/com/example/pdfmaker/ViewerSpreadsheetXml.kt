package com.example.pdfmaker

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal fun parseViewerSharedStrings(xml: String): List<String> {
    val strings = mutableListOf<String>()
    return try {
        val parser = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        val text = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG ->
                    if (parser.name == "t") {
                        inText = true
                        text.clear()
                    }
                XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                XmlPullParser.END_TAG ->
                    if (parser.name == "t") {
                        strings += text.toString()
                        inText = false
                    }
            }
            event = parser.next()
        }
        strings
    } catch (ignoredError: Exception) {
        Log.w("PdfViewer", "Unable to parse spreadsheet strings", ignoredError)
        emptyList()
    }
}

internal fun parseViewerSheet(
    xml: String,
    sharedStrings: List<String>,
): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    return try {
        val parser = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        var row = mutableListOf<String>()
        val value = StringBuilder()
        var cellType = ""
        var inValue = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG ->
                    when (parser.name) {
                        "row" -> row = mutableListOf()
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t").orEmpty()
                            inValue = false
                        }
                        "v", "t" -> {
                            inValue = true
                            value.clear()
                        }
                    }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG ->
                    when (parser.name) {
                        "v", "t" -> {
                            val raw = value.toString()
                            row +=
                                if (cellType == "s") {
                                    sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw }
                                } else {
                                    raw
                                }
                            inValue = false
                        }
                        "row" -> if (row.isNotEmpty()) rows += row.toList()
                    }
            }
            event = parser.next()
        }
        rows
    } catch (ignoredError: Exception) {
        Log.w("PdfViewer", "Unable to parse spreadsheet sheet", ignoredError)
        emptyList()
    }
}
