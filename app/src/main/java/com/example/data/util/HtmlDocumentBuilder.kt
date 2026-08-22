package com.example.data.util

import android.graphics.Color as AndroidColor
import com.example.data.model.DocParagraph
import com.example.data.model.DocParagraphType
import com.example.data.model.DocTextRun

/**
 * Builds semantic HTML/CSS from the already-parsed DOCX/PPTX structure (DocParagraph, DocTextRun,
 * PptxSlide). This HTML is then fed to Android's real print engine (WebView print adapter) which
 * takes care of layout, pagination, table rendering, text wrapping and RTL/BiDi shaping — instead
 * of a hand-rolled Canvas drawing routine. Everything here runs fully offline using classes already
 * built into the Android SDK.
 */
object HtmlDocumentBuilder {

    fun buildDocxHtml(paragraphs: List<DocParagraph>, documentTitle: String): String {
        val body = StringBuilder()
        var inBulletList = false
        var i = 0
        while (i < paragraphs.size) {
            val p = paragraphs[i]
            if (p.isBlank) {
                i++
                continue
            }

            // Consecutive TABLE_ROW paragraphs are grouped into a single <table>.
            if (p.type == DocParagraphType.TABLE_ROW && p.tableCells != null) {
                if (inBulletList) {
                    body.append("</ul>")
                    inBulletList = false
                }
                body.append("<table class=\"doc-table\">")
                while (i < paragraphs.size &&
                    paragraphs[i].type == DocParagraphType.TABLE_ROW &&
                    paragraphs[i].tableCells != null
                ) {
                    val row = paragraphs[i].tableCells!!
                    body.append("<tr>")
                    row.forEach { cellRuns ->
                        body.append("<td dir=\"").append(detectDirection(cellRuns.joinToString("") { it.text }))
                            .append("\">").append(buildRunsHtml(cellRuns)).append("</td>")
                    }
                    body.append("</tr>")
                    i++
                }
                body.append("</table>")
                continue
            }

            if (p.type == DocParagraphType.BULLET_ITEM) {
                if (!inBulletList) {
                    body.append("<ul class=\"doc-list\">")
                    inBulletList = true
                }
                body.append("<li dir=\"").append(detectDirection(p.plainText)).append("\">")
                    .append(buildRunsHtml(p.runs)).append("</li>")
                i++
                continue
            } else if (inBulletList) {
                body.append("</ul>")
                inBulletList = false
            }

            val tag = when (p.type) {
                DocParagraphType.TITLE -> "h1"
                DocParagraphType.HEADING_1 -> "h2"
                DocParagraphType.HEADING_2 -> "h3"
                DocParagraphType.HEADING_3 -> "h4"
                DocParagraphType.SLIDE_HEADER -> "div"
                else -> "p"
            }
            val cssClass = if (p.type == DocParagraphType.SLIDE_HEADER) " class=\"slide-header\"" else ""
            val alignStyle = alignmentStyle(p.alignment)
            body.append('<').append(tag).append(" dir=\"").append(detectDirection(p.plainText)).append('"')
                .append(cssClass).append(alignStyle).append('>')
                .append(buildRunsHtml(p.runs))
                .append("</").append(tag).append('>')
            i++
        }
        if (inBulletList) body.append("</ul>")

        return wrapHtmlDocument(body.toString())
    }

    fun buildPptxHtml(slides: List<OfficeToPdfConverter.PptxSlide>, documentTitle: String): String {
        val body = StringBuilder()
        slides.forEach { slide ->
            body.append("<div class=\"slide-page\">")
            body.append("<div class=\"slide-header\">").append("شريحة ${slide.slideNumber}").append("</div>")

            slide.title?.let { titleRuns ->
                if (titleRuns.isNotEmpty()) {
                    val plain = titleRuns.joinToString("") { it.text }
                    body.append("<div class=\"slide-title\" dir=\"").append(detectDirection(plain)).append("\">")
                        .append(buildRunsHtml(titleRuns))
                        .append("</div>")
                }
            }

            var inList = false
            slide.paragraphs.forEach { p ->
                if (p.isBlank) return@forEach
                if (p.type == DocParagraphType.BULLET_ITEM) {
                    if (!inList) {
                        body.append("<ul class=\"doc-list\">")
                        inList = true
                    }
                    body.append("<li dir=\"").append(detectDirection(p.plainText)).append("\">")
                        .append(buildRunsHtml(p.runs)).append("</li>")
                } else {
                    if (inList) {
                        body.append("</ul>")
                        inList = false
                    }
                    body.append("<p dir=\"").append(detectDirection(p.plainText)).append("\">")
                        .append(buildRunsHtml(p.runs)).append("</p>")
                }
            }
            if (inList) body.append("</ul>")
            body.append("</div>")
        }
        return wrapHtmlDocument(body.toString())
    }

    private fun alignmentStyle(alignment: String?): String = when (alignment) {
        "center" -> " style=\"text-align:center;\""
        "right" -> " style=\"text-align:right;\""
        "left" -> " style=\"text-align:left;\""
        else -> ""
    }

    private fun buildRunsHtml(runs: List<DocTextRun>): String {
        val sb = StringBuilder()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val styles = mutableListOf<String>()
            if (run.isBold) styles.add("font-weight:bold")
            if (run.isItalic) styles.add("font-style:italic")
            if (run.isUnderline) styles.add("text-decoration:underline")
            run.fontSizePt?.let { styles.add("font-size:${it}pt") }
            run.colorHex?.let { hex ->
                parseColorSafe(hex)?.let { color ->
                    val rgb = String.format("%06X", color and 0xFFFFFF)
                    styles.add("color:#$rgb")
                }
            }
            val styleAttr = if (styles.isNotEmpty()) " style=\"${styles.joinToString(";")}\"" else ""
            sb.append("<span").append(styleAttr).append('>')
                .append(escapeHtml(run.text))
                .append("</span>")
        }
        return sb.toString()
    }

    /** Simple first-strong-character heuristic to pick paragraph direction for correct RTL/LTR shaping. */
    private fun detectDirection(text: String): String {
        for (ch in text) {
            if (ch.code in 0x0600..0x06FF || ch.code in 0x0750..0x077F ||
                ch.code in 0x08A0..0x08FF || ch.code in 0xFB50..0xFDFF || ch.code in 0xFE70..0xFEFF
            ) {
                return "rtl"
            }
            if (ch.isLetter()) return "ltr"
        }
        return "rtl"
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("\n", "<br/>")
    }

    private fun parseColorSafe(hex: String): Int? {
        return try {
            val clean = hex.trim().removePrefix("#")
            when (clean.length) {
                6, 8 -> AndroidColor.parseColor("#$clean")
                3 -> {
                    val r = clean[0]; val g = clean[1]; val b = clean[2]
                    AndroidColor.parseColor("#$r$r$g$g$b$b")
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun wrapHtmlDocument(bodyContent: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <style>
                @page { margin: 16mm 14mm; }
                body {
                    font-family: 'Noto Naskh Arabic', 'Droid Arabic Naskh', Tahoma, sans-serif;
                    font-size: 12pt;
                    line-height: 1.65;
                    color: #1a1a1a;
                    direction: rtl;
                    margin: 0;
                    padding: 6mm 4mm;
                }
                h1 { font-size: 22pt; font-weight: bold; margin: 0 0 14pt 0; }
                h2 { font-size: 17pt; font-weight: bold; margin: 16pt 0 8pt 0; }
                h3 { font-size: 14.5pt; font-weight: bold; margin: 14pt 0 6pt 0; }
                h4 { font-size: 13pt; font-weight: bold; margin: 12pt 0 6pt 0; }
                p { margin: 6pt 0; }
                ul.doc-list { margin: 6pt 0; padding-inline-start: 22pt; }
                li { margin: 3pt 0; }
                table.doc-table {
                    border-collapse: collapse;
                    width: 100%;
                    margin: 10pt 0;
                }
                table.doc-table td {
                    border: 0.75pt solid #999999;
                    padding: 5pt 7pt;
                    vertical-align: top;
                    font-size: 11pt;
                }
                div.slide-header {
                    font-size: 10pt;
                    color: #00838F;
                    font-weight: bold;
                    margin-top: 6pt;
                    padding-bottom: 4pt;
                    border-bottom: 1pt solid #cccccc;
                }
                div.slide-page { page-break-after: always; padding-top: 4pt; }
                div.slide-page:last-child { page-break-after: auto; }
                div.slide-title { font-size: 20pt; font-weight: bold; margin: 12pt 0 10pt 0; }
            </style>
            </head>
            <body>
            $bodyContent
            </body>
            </html>
        """.trimIndent()
    }
}
