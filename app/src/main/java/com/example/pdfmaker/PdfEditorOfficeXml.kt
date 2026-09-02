package com.example.pdfmaker

internal data class DocxPackageXml(
    val document: String,
    val documentRelationships: String,
    val contentTypes: String,
    val rootRelationships: String,
    val styles: String,
)

internal data class PptxPackageXml(
    val presentation: String,
    val presentationRelationships: String,
    val contentTypes: String,
    val rootRelationships: String,
)

internal fun docxImageRelationshipXml(
    relationshipId: String,
    image: String,
): String =
    """
    <Relationship
        Id="$relationshipId"
        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
        Target="media/$image"
    />
    """.trimIndent()

internal fun docxPictureParagraphXml(
    index: Int,
    image: String,
    relationshipId: String,
    bitmapWidth: Int,
    bitmapHeight: Int,
): String {
    val imageWidth = 5_486_400L
    val imageHeight = (imageWidth * bitmapHeight / bitmapWidth.coerceAtLeast(1).toFloat()).toLong()
    return """
        <w:p><w:r><w:drawing><wp:inline>
          <wp:extent cx="$imageWidth" cy="$imageHeight"/>
          <wp:docPr id="${index + 1}" name="$image"/>
          <a:graphic><a:graphicData
              uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:pic>
              <pic:nvPicPr><pic:cNvPr id="${index + 1}" name="$image"/><pic:cNvPicPr/></pic:nvPicPr>
              <pic:blipFill><a:blip r:embed="$relationshipId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
              <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$imageWidth" cy="$imageHeight"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
              </pic:spPr>
            </pic:pic>
          </a:graphicData></a:graphic>
        </wp:inline></w:drawing></w:r></w:p>
        """.trimIndent()
}

internal fun buildDocxPackageXml(
    body: String,
    imageRelationships: List<String>,
): DocxPackageXml =
    DocxPackageXml(
        document =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document
                xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
              <w:body>$body</w:body>
            </w:document>
            """.trimIndent(),
        documentRelationships =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship
                  Id="rId1"
                  Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                  Target="styles.xml"
              />
              ${imageRelationships.joinToString("")}
            </Relationships>
            """.trimIndent(),
        contentTypes =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels"
                  ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="jpg" ContentType="image/jpeg"/>
              <Override PartName="/word/document.xml"
                  ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml"
                  ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            </Types>
            """.trimIndent(),
        rootRelationships = rootOfficeRelationshipXml("word/document.xml"),
        styles =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
            </w:styles>
            """.trimIndent(),
    )

internal fun pptxPictureSlideXml(
    index: Int,
    image: String,
    bitmapWidth: Int,
    bitmapHeight: Int,
): String {
    val slideWidth = 9_144_000L
    val slideHeight = 6_858_000L
    val imageHeight = (slideWidth * bitmapHeight / bitmapWidth.coerceAtLeast(1).toFloat()).toLong()
    val imageY = ((slideHeight - imageHeight) / 2).coerceAtLeast(0)
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
          <p:cSld><p:spTree><p:pic>
            <p:nvPicPr><p:cNvPr id="${index + 2}" name="$image"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
            <p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
            <p:spPr><a:xfrm><a:off x="0" y="$imageY"/><a:ext cx="$slideWidth" cy="$imageHeight"/></a:xfrm>
              <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
            </p:spPr>
          </p:pic></p:spTree></p:cSld>
        </p:sld>
        """.trimIndent()
}

internal fun pptxImageRelationshipXml(image: String): String =
    """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1"
          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
          Target="../media/$image"/>
    </Relationships>
    """.trimIndent()

internal fun buildPptxPackageXml(slideCount: Int): PptxPackageXml {
    val slideIds =
        (0 until slideCount).joinToString("") {
            "<p:sldId id=\"${256 + it}\" r:id=\"rId${10 + it}\"/>"
        }
    val slideRelationships =
        (0 until slideCount).joinToString("") {
            """
            <Relationship Id="rId${10 + it}"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
                Target="slides/slide${it + 1}.xml"/>
            """.trimIndent()
        }
    val slideContentTypes =
        (0 until slideCount).joinToString("") {
            """
            <Override PartName="/ppt/slides/slide${it + 1}.xml"
                ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
            """.trimIndent()
        }
    return PptxPackageXml(
        presentation =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldMasterIdLst/><p:sldSz cx="9144000" cy="6858000"/>
              <p:sldIdLst>$slideIds</p:sldIdLst>
            </p:presentation>
            """.trimIndent(),
        presentationRelationships =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              $slideRelationships
            </Relationships>
            """.trimIndent(),
        contentTypes =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels"
                  ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="jpg" ContentType="image/jpeg"/>
              <Override PartName="/ppt/presentation.xml"
                  ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
              $slideContentTypes
            </Types>
            """.trimIndent(),
        rootRelationships = rootOfficeRelationshipXml("ppt/presentation.xml"),
    )
}

private fun rootOfficeRelationshipXml(target: String): String =
    """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
      <Relationship Id="rId1"
          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
          Target="$target"/>
    </Relationships>
    """.trimIndent()
