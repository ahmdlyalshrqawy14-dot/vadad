package com.example.data.model

enum class DocParagraphType {
    NORMAL,
    TITLE,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    TABLE_ROW,
    SLIDE_HEADER,
    BULLET_ITEM
}

data class DocTextRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val fontSizePt: Float? = null,
    val colorHex: String? = null
)

data class DocParagraph(
    val type: DocParagraphType = DocParagraphType.NORMAL,
    val runs: List<DocTextRun> = emptyList(),
    val alignment: String? = null, // "left", "center", "right"
    val tableCells: List<List<DocTextRun>>? = null // For structured table rows
) {
    val plainText: String
        get() = runs.joinToString("") { it.text }

    val isBlank: Boolean
        get() = plainText.isBlank() && tableCells.isNullOrEmpty()
}
