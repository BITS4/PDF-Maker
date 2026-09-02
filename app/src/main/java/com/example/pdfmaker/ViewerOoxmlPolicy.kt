package com.example.pdfmaker

private val slideEntryPattern = Regex("^ppt/slides/slide(\\d+)\\.xml$")
private val slideRelationshipPattern = Regex("^ppt/slides/_rels/slide(\\d+)\\.xml\\.rels$")

internal fun viewerSlideNumber(entryName: String): Int? =
    slideEntryPattern
        .matchEntire(entryName)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

internal fun viewerSlideRelationshipNumber(entryName: String): Int? =
    slideRelationshipPattern
        .matchEntire(entryName)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

internal fun viewerMediaName(target: String): String? =
    target
        .replace('\\', '/')
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() && it != "." && it != ".." }
