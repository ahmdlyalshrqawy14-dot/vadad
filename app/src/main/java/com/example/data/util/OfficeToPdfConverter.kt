package com.example.data.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import com.example.data.i18n.AppStrings
import com.example.data.i18n.StringsArabic
import com.example.data.model.DocParagraph
import com.example.data.model.DocParagraphType
import com.example.data.model.DocTextRun
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object OfficeToPdfConverter {

    private const val TAG = "OfficeToPdfConverter"

    // Data structures for Excel worksheets
    data class ExcelCell(
        val colIndex: Int,
        val runs: List<DocTextRun>
    ) {
        val plainText: String get() = runs.joinToString("") { it.text }
    }

    data class ExcelRow(
        val rowIndex: Int,
        val cells: Map<Int, ExcelCell>
    )

    data class ExcelSheet(
        val sheetName: String,
        val rows: List<ExcelRow>,
        val maxColIndex: Int
    )

    // Data structure for PowerPoint slides
    data class PptxSlide(
        val slideNumber: Int,
        val title: List<DocTextRun>?,
        val paragraphs: List<DocParagraph>
    )

    suspend fun convertOfficeToPdf(
        context: Context,
        uri: Uri,
        fileName: String,
        onProgress: (Float) -> Unit,
        strings: AppStrings = StringsArabic
    ): File {
        val tempOutput = StorageManager.createTempFile(context, "vada_conv_", "pdf")
        val lowerName = fileName.lowercase()
        val ext = fileName.substringAfterLast(".", "").lowercase()

        // 1. Check binary signature (magic bytes) for OLE2 legacy compound files (D0 CF 11 E0)
        val isOle2Binary = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(8)
                val read = stream.read(header, 0, 8)
                read >= 4 &&
                        header[0] == 0xD0.toByte() &&
                        header[1] == 0xCF.toByte() &&
                        header[2] == 0x11.toByte() &&
                        header[3] == 0xE0.toByte()
            } ?: false
        } catch (e: Exception) {
            AppLogger.logSilentFailure(TAG, "فشل فحص ترويسة OLE2 الثنائية: $uri", e)
            false
        }

        if (isOle2Binary || ext == "doc" || ext == "xls" || ext == "ppt") {
            throw IllegalArgumentException(strings.errorLegacyOfficeFormat)
        }

        if (ext != "docx" && ext != "xlsx" && ext != "pptx") {
            throw IllegalArgumentException(strings.errorUnsupportedOfficeFormat)
        }

        try {
            when (ext) {
                "docx" -> {
                    val paragraphs = mutableListOf<DocParagraph>()
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        parseDocx(inputStream, paragraphs)
                    }
                    if (paragraphs.isEmpty()) {
                        throw IllegalStateException(strings.errorDocxEmptyContent(fileName))
                    }
                    onProgress(0.1f)
                    // Real print-engine based rendering: build semantic HTML from the parsed
                    // structure, then let Android's own print pipeline (WebView print adapter)
                    // handle layout, pagination, tables, wrapping and RTL/BiDi — the same engine
                    // used for "Print to PDF" system-wide. No third-party library, fully offline.
                    val html = HtmlDocumentBuilder.buildDocxHtml(paragraphs, fileName)
                    WebViewPdfPrinter.renderHtmlToPdf(context, html, tempOutput, onProgress)
                }
                "xlsx" -> {
                    val sheets = mutableListOf<ExcelSheet>()
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        parseXlsxSheets(inputStream, sheets)
                    }
                    if (sheets.isEmpty() || sheets.all { it.rows.isEmpty() }) {
                        throw IllegalStateException(strings.errorXlsxEmptyContent(fileName))
                    }
                    renderXlsxToPdf(tempOutput, fileName, sheets, onProgress)
                }
                "pptx" -> {
                    val slides = mutableListOf<PptxSlide>()
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        parsePptxSlides(inputStream, slides)
                    }
                    if (slides.isEmpty()) {
                        throw IllegalStateException(strings.errorPptxEmptyContent(fileName))
                    }
                    onProgress(0.1f)
                    // Same real-print-engine approach used for DOCX (see comment above).
                    val html = HtmlDocumentBuilder.buildPptxHtml(slides, fileName)
                    WebViewPdfPrinter.renderHtmlToPdf(context, html, tempOutput, onProgress)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting office document $fileName", e)
            if (e is IllegalArgumentException || e is IllegalStateException) {
                throw e
            }
            throw IllegalStateException(strings.errorOfficeConversionFailed(fileName, e.message ?: ""), e)
        }

        return tempOutput
    }

    // =========================================================================
    // DOCX PARSER & RENDERER
    // =========================================================================

    /**
     * Parses DOCX OpenXML with rich run and paragraph properties:
     * - <w:rPr>: <w:b>, <w:i>, <w:u>, <w:sz>, <w:color>
     * - <w:pStyle>: Title, Heading1, Heading2, Heading3, Subtitle, Bullet lists
     * - <w:tbl>, <w:tr>, <w:tc>: Table rows and cells
     */
    fun parseDocx(inputStream: InputStream, outputList: MutableList<DocParagraph>) {
        ZipInputStream(inputStream).use { zipStream ->
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                    val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
                    val parser = factory.newPullParser()
                    parser.setInput(zipStream.reader(Charsets.UTF_8))

                    var eventType = parser.eventType
                    var currentParaType = DocParagraphType.NORMAL
                    var currentParaAlignment: String? = null
                    var currentRuns = mutableListOf<DocTextRun>()

                    var inP = false
                    var inPPr = false
                    var inR = false
                    var inRPr = false
                    var inT = false
                    var inTbl = false
                    var inTr = false
                    var inTc = false

                    var runBold = false
                    var runItalic = false
                    var runUnderline = false
                    var runFontSize: Float? = null
                    var runColor: String? = null
                    var runText = StringBuilder()

                    var tableCells = mutableListOf<List<DocTextRun>>()
                    var currentCellRuns = mutableListOf<DocTextRun>()

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tagName = parser.name?.substringAfter(":")?.lowercase() ?: ""

                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                when (tagName) {
                                    "tbl" -> inTbl = true
                                    "tr" -> {
                                        inTr = true
                                        tableCells = mutableListOf()
                                    }
                                    "tc" -> {
                                        inTc = true
                                        currentCellRuns = mutableListOf()
                                    }
                                    "p" -> {
                                        inP = true
                                        currentParaType = if (inTc) DocParagraphType.TABLE_ROW else DocParagraphType.NORMAL
                                        currentParaAlignment = null
                                        currentRuns = mutableListOf()
                                    }
                                    "ppr" -> inPPr = true
                                    "pstyle" -> {
                                        if (inPPr) {
                                            val styleVal = getAttrValue(parser, "val")?.lowercase() ?: ""
                                            currentParaType = when {
                                                styleVal.contains("title") -> DocParagraphType.TITLE
                                                styleVal.contains("heading1") || styleVal.contains("heading 1") || styleVal.contains("1") -> DocParagraphType.HEADING_1
                                                styleVal.contains("heading2") || styleVal.contains("heading 2") || styleVal.contains("2") -> DocParagraphType.HEADING_2
                                                styleVal.contains("heading3") || styleVal.contains("heading 3") || styleVal.contains("3") -> DocParagraphType.HEADING_3
                                                styleVal.contains("subtitle") -> DocParagraphType.HEADING_2
                                                styleVal.contains("list") || styleVal.contains("bullet") -> DocParagraphType.BULLET_ITEM
                                                else -> DocParagraphType.NORMAL
                                            }
                                        }
                                    }
                                    "jc" -> {
                                        if (inPPr) {
                                            currentParaAlignment = getAttrValue(parser, "val")?.lowercase()
                                        }
                                    }
                                    "r" -> {
                                        inR = true
                                        runBold = false
                                        runItalic = false
                                        runUnderline = false
                                        runFontSize = null
                                        runColor = null
                                        runText = StringBuilder()
                                    }
                                    "rpr" -> inRPr = true
                                    "b" -> {
                                        if (inRPr) {
                                            val v = getAttrValue(parser, "val")?.lowercase()
                                            runBold = v == null || (v != "0" && v != "false" && v != "off")
                                        }
                                    }
                                    "i" -> {
                                        if (inRPr) {
                                            val v = getAttrValue(parser, "val")?.lowercase()
                                            runItalic = v == null || (v != "0" && v != "false" && v != "off")
                                        }
                                    }
                                    "u" -> {
                                        if (inRPr) {
                                            val v = getAttrValue(parser, "val")?.lowercase()
                                            runUnderline = v == null || (v != "none" && v != "0" && v != "false")
                                        }
                                    }
                                    "sz" -> {
                                        if (inRPr) {
                                            val v = getAttrValue(parser, "val")
                                            val halfPoints = v?.toFloatOrNull()
                                            if (halfPoints != null) {
                                                runFontSize = halfPoints / 2f
                                            }
                                        }
                                    }
                                    "color" -> {
                                        if (inRPr) {
                                            val v = getAttrValue(parser, "val")
                                            if (v != null && v.lowercase() != "auto") {
                                                runColor = v
                                            }
                                        }
                                    }
                                    "t" -> inT = true
                                    "tab" -> if (inR) runText.append("    ")
                                    "br" -> if (inR) runText.append("\n")
                                }
                            }

                            XmlPullParser.TEXT -> {
                                if (inT && inR) {
                                    runText.append(parser.text)
                                }
                            }

                            XmlPullParser.END_TAG -> {
                                when (tagName) {
                                    "t" -> inT = false
                                    "rpr" -> inRPr = false
                                    "r" -> {
                                        inR = false
                                        val text = runText.toString()
                                        if (text.isNotEmpty()) {
                                            val run = DocTextRun(
                                                text = text,
                                                isBold = runBold,
                                                isItalic = runItalic,
                                                isUnderline = runUnderline,
                                                fontSizePt = runFontSize,
                                                colorHex = runColor
                                            )
                                            currentRuns.add(run)
                                            if (inTc) {
                                                currentCellRuns.add(run)
                                            }
                                        }
                                    }
                                    "ppr" -> inPPr = false
                                    "p" -> {
                                        inP = false
                                        if (!inTc) {
                                            if (currentRuns.isNotEmpty() || currentParaType != DocParagraphType.NORMAL) {
                                                outputList.add(
                                                    DocParagraph(
                                                        type = currentParaType,
                                                        runs = currentRuns,
                                                        alignment = currentParaAlignment
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    "tc" -> {
                                        inTc = false
                                        if (currentCellRuns.isNotEmpty()) {
                                            tableCells.add(currentCellRuns)
                                        }
                                    }
                                    "tr" -> {
                                        inTr = false
                                        if (tableCells.isNotEmpty()) {
                                            val allRuns = tableCells.flatMapIndexed { idx, cell ->
                                                val prefix = if (idx > 0) listOf(DocTextRun("  |  ", isBold = true, colorHex = "718096")) else emptyList()
                                                prefix + cell
                                            }
                                            outputList.add(
                                                DocParagraph(
                                                    type = DocParagraphType.TABLE_ROW,
                                                    runs = allRuns,
                                                    tableCells = tableCells
                                                )
                                            )
                                        }
                                    }
                                    "tbl" -> inTbl = false
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                    break
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
    }

    // =========================================================================
    // EXCEL XLSX PARSER & REAL GRID TABLE RENDERER
    // =========================================================================

    /**
     * Parses XLSX workbook distinctly:
     * 1. Loads xl/sharedStrings.xml into indexed list.
     * 2. Parses each xl/worksheets/sheetN.xml independently.
     * 3. Within each sheet, maps each <row> and <c> with its column coordinate (r="A1", r="B1", etc.) and value.
     */
    fun parseXlsxSheets(inputStream: InputStream, outputSheets: MutableList<ExcelSheet>) {
        val sharedStrings = mutableListOf<List<DocTextRun>>()
        val sheetEntries = mutableListOf<Pair<String, ByteArray>>()

        ZipInputStream(inputStream).use { zipStream ->
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name == "xl/sharedstrings.xml") {
                    parseXlsxSharedStrings(zipStream.readBytes(), sharedStrings)
                } else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
                    sheetEntries.add(name to zipStream.readBytes())
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        // Sort sheets naturally by number
        sheetEntries.sortBy { (name, _) ->
            val num = name.substringAfterLast("sheet").substringBefore(".").toIntOrNull() ?: 0
            num
        }

        sheetEntries.forEach { (name, bytes) ->
            val sheetNumber = name.substringAfterLast("sheet").substringBefore(".")
            val sheetName = "Sheet $sheetNumber"
            val rows = mutableListOf<ExcelRow>()
            var maxCol = 0

            parseSingleXlsxWorksheet(bytes, sharedStrings) { row, sheetMaxCol ->
                rows.add(row)
                if (sheetMaxCol > maxCol) maxCol = sheetMaxCol
            }

            outputSheets.add(ExcelSheet(sheetName = sheetName, rows = rows, maxColIndex = maxCol))
        }
    }

    /**
     * Backward-compatible parseXlsx that also fills outputList for simple callers.
     */
    fun parseXlsx(inputStream: InputStream, outputList: MutableList<DocParagraph>) {
        val sheets = mutableListOf<ExcelSheet>()
        parseXlsxSheets(inputStream, sheets)
        sheets.forEach { sheet ->
            outputList.add(
                DocParagraph(
                    type = DocParagraphType.HEADING_2,
                    runs = listOf(DocTextRun(sheet.sheetName, isBold = true, colorHex = "107C41"))
                )
            )
            sheet.rows.forEach { row ->
                val maxCol = sheet.maxColIndex
                val cells = (0..maxCol).map { cIdx ->
                    row.cells[cIdx]?.runs ?: emptyList()
                }
                val allRuns = cells.flatMapIndexed { idx, cell ->
                    val prefix = if (idx > 0) listOf(DocTextRun("   |   ", isBold = true, colorHex = "94A3B8")) else emptyList()
                    val content = if (cell.isNotEmpty()) cell else listOf(DocTextRun("-"))
                    prefix + content
                }
                outputList.add(
                    DocParagraph(
                        type = DocParagraphType.TABLE_ROW,
                        runs = allRuns,
                        tableCells = cells
                    )
                )
            }
        }
    }

    private fun parseXlsxSharedStrings(bytes: ByteArray, outputList: MutableList<List<DocTextRun>>) {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(bytes.inputStream().reader(Charsets.UTF_8))

        var eventType = parser.eventType
        var inSi = false
        var inR = false
        var inRPr = false
        var inT = false
        var currentRuns = mutableListOf<DocTextRun>()
        var runBold = false
        var runItalic = false
        var runUnderline = false
        var runFontSize: Float? = null
        var runColor: String? = null
        var runText = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(":")?.lowercase() ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "si" -> {
                            inSi = true
                            currentRuns = mutableListOf()
                        }
                        "r" -> {
                            inR = true
                            runBold = false
                            runItalic = false
                            runUnderline = false
                            runFontSize = null
                            runColor = null
                            runText = StringBuilder()
                        }
                        "rpr" -> inRPr = true
                        "b" -> if (inRPr) runBold = true
                        "i" -> if (inRPr) runItalic = true
                        "u" -> if (inRPr) runUnderline = true
                        "sz" -> {
                            if (inRPr) {
                                runFontSize = getAttrValue(parser, "val")?.toFloatOrNull()
                            }
                        }
                        "color" -> {
                            if (inRPr) {
                                runColor = getAttrValue(parser, "rgb") ?: getAttrValue(parser, "theme")
                            }
                        }
                        "t" -> inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inT) {
                        if (inR) {
                            runText.append(parser.text)
                        } else if (inSi) {
                            currentRuns.add(DocTextRun(parser.text))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tag) {
                        "t" -> inT = false
                        "rpr" -> inRPr = false
                        "r" -> {
                            inR = false
                            if (runText.isNotEmpty()) {
                                currentRuns.add(
                                    DocTextRun(
                                        text = runText.toString(),
                                        isBold = runBold,
                                        isItalic = runItalic,
                                        isUnderline = runUnderline,
                                        fontSizePt = runFontSize,
                                        colorHex = runColor
                                    )
                                )
                            }
                        }
                        "si" -> {
                            inSi = false
                            outputList.add(currentRuns)
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    fun colLetterToIndex(letters: String): Int {
        var result = 0
        for (ch in letters.uppercase()) {
            if (ch in 'A'..'Z') {
                result = result * 26 + (ch - 'A' + 1)
            }
        }
        return (result - 1).coerceAtLeast(0)
    }

    private fun parseSingleXlsxWorksheet(
        bytes: ByteArray,
        sharedStrings: List<List<DocTextRun>>,
        onRowParsed: (ExcelRow, Int) -> Unit
    ) {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(bytes.inputStream().reader(Charsets.UTF_8))

        var eventType = parser.eventType
        var inRow = false
        var inC = false
        var inV = false
        var inIs = false
        var inT = false

        var currentRowIndex = 1
        var currentCellRef = ""
        var currentCellType: String? = null
        var currentCellValue = StringBuilder()
        var currentInlineRuns = mutableListOf<DocTextRun>()

        var rowCells = mutableMapOf<Int, ExcelCell>()
        var sheetMaxCol = 0

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(":")?.lowercase() ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "row" -> {
                            inRow = true
                            rowCells = mutableMapOf()
                            val rAttr = getAttrValue(parser, "r")?.toIntOrNull()
                            if (rAttr != null) currentRowIndex = rAttr
                        }
                        "c" -> {
                            inC = true
                            currentCellRef = getAttrValue(parser, "r") ?: ""
                            currentCellType = getAttrValue(parser, "t")
                            currentCellValue = StringBuilder()
                            currentInlineRuns = mutableListOf()
                        }
                        "v" -> inV = true
                        "is" -> inIs = true
                        "t" -> if (inIs) inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inV && inC) {
                        currentCellValue.append(parser.text)
                    } else if (inT && inIs && inC) {
                        currentInlineRuns.add(DocTextRun(parser.text))
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tag) {
                        "v" -> inV = false
                        "t" -> inT = false
                        "is" -> inIs = false
                        "c" -> {
                            inC = false
                            val colLetters = currentCellRef.takeWhile { it.isLetter() }
                            val colIndex = if (colLetters.isNotEmpty()) colLetterToIndex(colLetters) else rowCells.size
                            if (colIndex > sheetMaxCol) sheetMaxCol = colIndex

                            val runs: List<DocTextRun> = when {
                                currentCellType == "s" -> {
                                    val sIndex = currentCellValue.toString().trim().toIntOrNull()
                                    if (sIndex != null && sIndex in sharedStrings.indices) {
                                        sharedStrings[sIndex]
                                    } else {
                                        listOf(DocTextRun(currentCellValue.toString().trim()))
                                    }
                                }
                                currentCellType == "inlineStr" || currentInlineRuns.isNotEmpty() -> {
                                    currentInlineRuns
                                }
                                currentCellType == "b" -> {
                                    val isTrue = currentCellValue.toString().trim() == "1"
                                    listOf(DocTextRun(if (isTrue) "TRUE" else "FALSE"))
                                }
                                else -> {
                                    val raw = currentCellValue.toString().trim()
                                    if (raw.isNotEmpty()) listOf(DocTextRun(raw)) else emptyList()
                                }
                            }

                            if (runs.isNotEmpty()) {
                                rowCells[colIndex] = ExcelCell(colIndex = colIndex, runs = runs)
                            }
                        }
                        "row" -> {
                            inRow = false
                            if (rowCells.isNotEmpty()) {
                                onRowParsed(ExcelRow(rowIndex = currentRowIndex, cells = rowCells), sheetMaxCol)
                            }
                            currentRowIndex++
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * Renders Excel worksheets as actual structured tables in PDF with real grid lines,
     * header formatting, alternating row backgrounds, dynamic column distribution, and RTL cell support.
     */
    fun renderXlsxToPdf(
        outputFile: File,
        documentTitle: String,
        sheets: List<ExcelSheet>,
        onProgress: (Float) -> Unit
    ) {
        val doc = PdfDocument()
        val pageWidth = 842 // A4 Landscape for proper spreadsheet grid viewing
        val pageHeight = 595
        val margin = 36f
        val printableWidth = pageWidth - (margin * 2)

        var currentPageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas

        val titlePaint = TextPaint().apply {
            color = AndroidColor.rgb(16, 124, 65) // Excel Brand Green
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val sheetTabPaint = TextPaint().apply {
            color = AndroidColor.rgb(30, 41, 59)
            textSize = 13f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val headerCellPaint = TextPaint().apply {
            color = AndroidColor.rgb(15, 23, 42)
            textSize = 10.5f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val dataCellPaint = TextPaint().apply {
            color = AndroidColor.rgb(51, 65, 85)
            textSize = 10f
            isAntiAlias = true
        }

        val headerBgPaint = Paint().apply {
            color = AndroidColor.rgb(232, 245, 233) // Soft Excel light green
            style = Paint.Style.FILL
        }

        val evenRowBgPaint = Paint().apply {
            color = AndroidColor.rgb(248, 250, 252)
            style = Paint.Style.FILL
        }

        val oddRowBgPaint = Paint().apply {
            color = AndroidColor.WHITE
            style = Paint.Style.FILL
        }

        val gridLinePaint = Paint().apply {
            color = AndroidColor.rgb(203, 213, 225)
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val tableBorderPaint = Paint().apply {
            color = AndroidColor.rgb(148, 163, 184)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val footerPaint = TextPaint().apply {
            color = AndroidColor.rgb(148, 163, 184)
            textSize = 9f
            isAntiAlias = true
        }

        var yPos = 46f

        // Document Banner
        val isTitleRtl = isRtlText(documentTitle)
        val bannerText = if (isTitleRtl) "جدول بيانات: $documentTitle" else "Spreadsheet: $documentTitle"
        val bannerLayout = StaticLayout.Builder.obtain(
            bannerText,
            0,
            bannerText.length,
            titlePaint,
            printableWidth.toInt()
        )
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setAlignment(if (isTitleRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        canvas.save()
        canvas.translate(margin, yPos - 12f)
        bannerLayout.draw(canvas)
        canvas.restore()

        yPos += 14f
        canvas.drawLine(margin, yPos, margin + printableWidth, yPos, gridLinePaint)
        yPos += 20f

        fun performPageBreak() {
            canvas.drawText("Page $currentPageNum", margin + printableWidth - 50f, pageHeight - 18f, footerPaint)
            doc.finishPage(page)

            currentPageNum++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
            page = doc.startPage(pageInfo)
            canvas = page.canvas
            yPos = 46f
        }

        val totalSheets = sheets.size
        sheets.forEachIndexed { sheetIdx, sheet ->
            if (sheet.rows.isEmpty()) return@forEachIndexed

            if (yPos + 60f > pageHeight - 40f) performPageBreak()

            // Sheet Tab Badge
            val isSheetNameRtl = isRtlText(sheet.sheetName)
            val tabBg = Paint().apply {
                color = AndroidColor.rgb(241, 245, 249)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(margin, yPos - 14f, margin + 160f, yPos + 10f), 4f, 4f, tabBg)

            val tabText = "📑 ${sheet.sheetName}"
            val tabLayout = StaticLayout.Builder.obtain(
                tabText,
                0,
                tabText.length,
                sheetTabPaint,
                150
            )
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setAlignment(if (isSheetNameRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

            canvas.save()
            canvas.translate(margin + 6f, yPos - 10f)
            tabLayout.draw(canvas)
            canvas.restore()

            yPos += 20f

            val numCols = (sheet.maxColIndex + 1).coerceAtLeast(1)
            val colWidth = printableWidth / numCols.toFloat()

            sheet.rows.forEachIndexed { rIdx, row ->
                val isHeaderRow = (rIdx == 0)
                val rowHeight = 22f

                if (yPos + rowHeight > pageHeight - 40f) {
                    performPageBreak()
                }

                val rowTop = yPos - 14f
                val rowBottom = rowTop + rowHeight

                // Row background
                val bgPaint = when {
                    isHeaderRow -> headerBgPaint
                    rIdx % 2 == 0 -> evenRowBgPaint
                    else -> oddRowBgPaint
                }
                canvas.drawRect(margin, rowTop, margin + printableWidth, rowBottom, bgPaint)

                // Row horizontal borders
                canvas.drawLine(margin, rowBottom, margin + printableWidth, rowBottom, gridLinePaint)
                if (isHeaderRow) {
                    canvas.drawLine(margin, rowTop, margin + printableWidth, rowTop, tableBorderPaint)
                }

                // Render Cells with vertical grid dividers and StaticLayout RTL/BiDi engine
                for (colIdx in 0 until numCols) {
                    val cellX = margin + (colIdx * colWidth)
                    val cellRight = cellX + colWidth

                    // Vertical grid line on left
                    if (colIdx > 0) {
                        canvas.drawLine(cellX, rowTop, cellX, rowBottom, gridLinePaint)
                    }

                    val cell = row.cells[colIdx]
                    if (cell != null && cell.runs.isNotEmpty()) {
                        val cellPaint = if (isHeaderRow) headerCellPaint else dataCellPaint
                        val spannable = buildSpannedFromRuns(cell.runs, cellPaint.textSize, cellPaint.color, isHeaderRow)
                        if (spannable.isNotEmpty()) {
                            val cellW = (colWidth - 10f).toInt().coerceAtLeast(10)
                            val isRtlCell = isRtlText(spannable)
                            val cellAlign = if (isRtlCell) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL

                            val cellLayout = StaticLayout.Builder.obtain(
                                spannable,
                                0,
                                spannable.length,
                                cellPaint,
                                cellW
                            )
                                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                                .setAlignment(cellAlign)
                                .setIncludePad(false)
                                .setMaxLines(1)
                                .setEllipsize(TextUtils.TruncateAt.END)
                                .build()

                            canvas.save()
                            canvas.translate(cellX + 5f, yPos - 10f)
                            cellLayout.draw(canvas)
                            canvas.restore()
                        }
                    }
                }

                yPos += rowHeight
            }

            // Margin after sheet table
            yPos += 16f
            onProgress(((sheetIdx + 1).toFloat() / totalSheets.toFloat()).coerceIn(0f, 0.95f))
        }

        // Final footer
        canvas.drawText("Page $currentPageNum", margin + printableWidth - 50f, pageHeight - 18f, footerPaint)
        doc.finishPage(page)

        FileOutputStream(outputFile).use { out -> doc.writeTo(out) }
        doc.close()

        onProgress(1.0f)
    }

    // =========================================================================
    // POWERPOINT PPTX PARSER & 1-PAGE-PER-SLIDE RENDERER
    // =========================================================================

    /**
     * Parses PPTX presentations, separating each slide into a discrete PptxSlide data model.
     */
    fun parsePptxSlides(inputStream: InputStream, outputSlides: MutableList<PptxSlide>) {
        val slideEntries = mutableListOf<Pair<String, ByteArray>>()

        ZipInputStream(inputStream).use { zipStream ->
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    slideEntries.add(name to zipStream.readBytes())
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        slideEntries.sortBy { (name, _) ->
            val numStr = name.substringAfterLast("slide").substringBefore(".")
            numStr.toIntOrNull() ?: 0
        }

        slideEntries.forEach { (name, bytes) ->
            val slideNum = name.substringAfterLast("slide").substringBefore(".").toIntOrNull() ?: (outputSlides.size + 1)
            var slideTitle: List<DocTextRun>? = null
            val paragraphs = mutableListOf<DocParagraph>()

            parseSinglePptxSlide(bytes) { pType, runs ->
                if (pType == DocParagraphType.TITLE && slideTitle == null) {
                    slideTitle = runs
                } else {
                    paragraphs.add(DocParagraph(type = pType, runs = runs))
                }
            }

            outputSlides.add(
                PptxSlide(
                    slideNumber = slideNum,
                    title = slideTitle,
                    paragraphs = paragraphs
                )
            )
        }
    }

    /**
     * Backward-compatible parsePptx that fills outputList for simple callers.
     */
    fun parsePptx(inputStream: InputStream, outputList: MutableList<DocParagraph>) {
        val slides = mutableListOf<PptxSlide>()
        parsePptxSlides(inputStream, slides)
        slides.forEach { slide ->
            outputList.add(
                DocParagraph(
                    type = DocParagraphType.SLIDE_HEADER,
                    runs = listOf(DocTextRun("Slide ${slide.slideNumber}", isBold = true, colorHex = "00838F"))
                )
            )
            slide.title?.let { tRuns ->
                outputList.add(DocParagraph(type = DocParagraphType.TITLE, runs = tRuns))
            }
            outputList.addAll(slide.paragraphs)
        }
    }

    private fun parseSinglePptxSlide(
        bytes: ByteArray,
        onParagraphParsed: (DocParagraphType, List<DocTextRun>) -> Unit
    ) {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(bytes.inputStream().reader(Charsets.UTF_8))

        var eventType = parser.eventType
        var inP = false
        var inR = false
        var inRPr = false
        var inT = false
        var isTitleShape = false

        var runBold = false
        var runItalic = false
        var runFontSize: Float? = null
        var runColor: String? = null
        var runText = StringBuilder()
        var currentRuns = mutableListOf<DocTextRun>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(":")?.lowercase() ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "ph" -> {
                            val type = getAttrValue(parser, "type")?.lowercase()
                            if (type == "title" || type == "ctrtitle") {
                                isTitleShape = true
                            }
                        }
                        "p" -> {
                            inP = true
                            currentRuns = mutableListOf()
                        }
                        "r" -> {
                            inR = true
                            runBold = false
                            runItalic = false
                            runFontSize = null
                            runColor = null
                            runText = StringBuilder()
                        }
                        "rpr" -> {
                            inRPr = true
                            val b = getAttrValue(parser, "b")
                            runBold = b == "1" || b == "true"
                            val i = getAttrValue(parser, "i")
                            runItalic = i == "1" || i == "true"
                            val sz = getAttrValue(parser, "sz")?.toFloatOrNull()
                            if (sz != null) {
                                runFontSize = sz / 100f // Hundredths of a pt
                            }
                        }
                        "srgbclr" -> {
                            if (inRPr) {
                                runColor = getAttrValue(parser, "val")
                            }
                        }
                        "t" -> inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inT && inR) {
                        runText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tag) {
                        "t" -> inT = false
                        "rpr" -> inRPr = false
                        "r" -> {
                            inR = false
                            val text = runText.toString()
                            if (text.isNotEmpty()) {
                                currentRuns.add(
                                    DocTextRun(
                                        text = text,
                                        isBold = runBold,
                                        isItalic = runItalic,
                                        fontSizePt = runFontSize,
                                        colorHex = runColor
                                    )
                                )
                            }
                        }
                        "p" -> {
                            inP = false
                            if (currentRuns.isNotEmpty()) {
                                val pType = if (isTitleShape) DocParagraphType.TITLE else DocParagraphType.NORMAL
                                onParagraphParsed(pType, currentRuns)
                            }
                        }
                        "sp" -> {
                            isTitleShape = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * Renders each PowerPoint slide onto a distinct, dedicated PDF page (1 slide = 1 PDF page),
     * matching the layout behavior of professional PDF slide presentations with RTL and BiDi typography.
     */
    fun renderPptxToPdf(
        outputFile: File,
        documentTitle: String,
        slides: List<PptxSlide>,
        onProgress: (Float) -> Unit
    ) {
        val doc = PdfDocument()
        val pageWidth = 842 // 16:9 / Landscape presentation dimensions
        val pageHeight = 595
        val margin = 44f
        val printableWidth = pageWidth - (margin * 2)

        val totalSlides = slides.size

        slides.forEachIndexed { sIdx, slide ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, sIdx + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            // 1. Slide Canvas Frame & Accent Top Bar
            val framePaint = Paint().apply {
                color = AndroidColor.rgb(241, 245, 249)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(16f, 16f, pageWidth - 16f, pageHeight - 16f), 12f, 12f, framePaint)

            val topBarPaint = Paint().apply {
                color = AndroidColor.rgb(0, 131, 143) // Deep Teal
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(16f, 16f, pageWidth - 16f, 26f), 4f, 4f, topBarPaint)

            // 2. Slide Header Badge
            val badgeBg = Paint().apply {
                color = AndroidColor.rgb(224, 247, 250)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(margin, 38f, margin + 120f, 62f), 6f, 6f, badgeBg)

            val badgeTextPaint = TextPaint().apply {
                color = AndroidColor.rgb(0, 105, 120)
                textSize = 11f
                isFakeBoldText = true
                isAntiAlias = true
            }
            canvas.drawText("Slide ${slide.slideNumber} of $totalSlides", margin + 10f, 54f, badgeTextPaint)

            // Presentation Document Name
            val isDocNameRtl = isRtlText(documentTitle)
            val docNamePaint = TextPaint().apply {
                color = AndroidColor.rgb(100, 116, 139)
                textSize = 10f
                isAntiAlias = true
            }
            val docNameLayout = StaticLayout.Builder.obtain(
                documentTitle,
                0,
                documentTitle.length,
                docNamePaint,
                240
            )
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setAlignment(if (isDocNameRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

            canvas.save()
            canvas.translate(pageWidth - margin - 250f, 42f)
            docNameLayout.draw(canvas)
            canvas.restore()

            var yPos = 88f

            // 3. Slide Title
            val titleRuns = slide.title ?: listOf(DocTextRun("Slide ${slide.slideNumber}", isBold = true))
            val isSlideTitleRtl = isRtlText(slide.title?.joinToString("") { it.text } ?: "")

            val titlePaint = TextPaint().apply {
                color = AndroidColor.rgb(15, 23, 42)
                textSize = 22f
                isFakeBoldText = true
                isAntiAlias = true
            }

            yPos = drawParagraphWithStaticLayout(
                getCanvas = { canvas },
                runs = titleRuns,
                defaultPaint = titlePaint,
                defaultSize = 22f,
                defaultColor = AndroidColor.rgb(15, 23, 42),
                defaultBold = true,
                printableWidth = printableWidth,
                startX = margin,
                startY = yPos,
                maxPageY = pageHeight - 60f,
                onPageBreak = { /* Dedicated 1-page per slide */ }
            )

            // Underline Accent for Title
            yPos += 6f
            val accentLinePaint = Paint().apply {
                color = AndroidColor.rgb(0, 131, 143)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            if (isSlideTitleRtl) {
                canvas.drawLine(margin + printableWidth - 140f, yPos, margin + printableWidth, yPos, accentLinePaint)
            } else {
                canvas.drawLine(margin, yPos, margin + 140f, yPos, accentLinePaint)
            }
            yPos += 24f

            // 4. Slide Content Elements / Bullets / Body
            val bodyPaint = TextPaint().apply {
                color = AndroidColor.rgb(30, 41, 59)
                textSize = 13.5f
                isAntiAlias = true
            }

            val bulletDotPaint = Paint().apply {
                color = AndroidColor.rgb(0, 131, 143)
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            slide.paragraphs.forEach { para ->
                if (yPos > pageHeight - 50f) return@forEach
                val isParaRtl = isRtlText(para.plainText)

                if (isParaRtl) {
                    // Bullet dot on the right margin
                    canvas.drawCircle(margin + printableWidth - 6f, yPos + 8f, 3.5f, bulletDotPaint)
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = bodyPaint,
                        defaultSize = 13.5f,
                        defaultColor = AndroidColor.rgb(30, 41, 59),
                        defaultBold = false,
                        printableWidth = printableWidth - 24f,
                        startX = margin,
                        startY = yPos,
                        maxPageY = pageHeight - 40f,
                        onPageBreak = { /* 1 page per slide */ }
                    )
                } else {
                    // Bullet dot on the left margin
                    canvas.drawCircle(margin + 6f, yPos + 8f, 3.5f, bulletDotPaint)
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = bodyPaint,
                        defaultSize = 13.5f,
                        defaultColor = AndroidColor.rgb(30, 41, 59),
                        defaultBold = false,
                        printableWidth = printableWidth - 24f,
                        startX = margin + 18f,
                        startY = yPos,
                        maxPageY = pageHeight - 40f,
                        onPageBreak = { /* 1 page per slide */ }
                    )
                }
                yPos += 10f
            }

            // 5. Slide Footer
            val footerLinePaint = Paint().apply {
                color = AndroidColor.rgb(226, 232, 240)
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(margin, pageHeight - 34f, margin + printableWidth, pageHeight - 34f, footerLinePaint)

            val footerTextPaint = TextPaint().apply {
                color = AndroidColor.rgb(148, 163, 184)
                textSize = 9.5f
                isAntiAlias = true
            }
            canvas.drawText("Page ${sIdx + 1}", margin + printableWidth - 40f, pageHeight - 20f, footerTextPaint)

            doc.finishPage(page)
            onProgress(((sIdx + 1).toFloat() / totalSlides.toFloat()).coerceIn(0f, 0.95f))
        }

        FileOutputStream(outputFile).use { out -> doc.writeTo(out) }
        doc.close()

        onProgress(1.0f)
    }

    // =========================================================================
    // GENERAL DOCUMENT RENDERER (FOR DOCX & MIXED TEXT)
    // =========================================================================

    /**
     * Renders parsed document elements into a professional PDF with distinct visual typography,
     * powered by StaticLayout, FIRSTSTRONG_RTL heuristics, and bidirectional text rendering.
     */
    fun renderDocumentToPdf(
        outputFile: File,
        documentTitle: String,
        paragraphs: List<DocParagraph>,
        onProgress: (Float) -> Unit
    ) {
        val doc = PdfDocument()
        val pageWidth = 595 // A4 standard width in points
        val pageHeight = 842 // A4 standard height in points
        val margin = 44f
        val printableWidth = pageWidth - (margin * 2)

        var currentPageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas

        val textPaint = TextPaint().apply {
            color = AndroidColor.rgb(33, 37, 41)
            textSize = 11f
            isAntiAlias = true
        }

        val headerPaint = TextPaint().apply {
            color = AndroidColor.rgb(13, 71, 161) // Deep Royal Blue
            textSize = 15f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val titlePaint = TextPaint().apply {
            color = AndroidColor.rgb(21, 101, 192)
            textSize = 20f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val h2Paint = TextPaint().apply {
            color = AndroidColor.rgb(40, 116, 166)
            textSize = 13.5f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val h3Paint = TextPaint().apply {
            color = AndroidColor.rgb(55, 71, 79)
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val slideHeaderPaint = TextPaint().apply {
            color = AndroidColor.rgb(0, 131, 143)
            textSize = 13f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val tableRowPaint = TextPaint().apply {
            color = AndroidColor.rgb(45, 55, 72)
            textSize = 10.5f
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = AndroidColor.rgb(220, 224, 230)
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val accentBarPaint = Paint().apply {
            color = AndroidColor.rgb(13, 71, 161)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val footerPaint = TextPaint().apply {
            color = AndroidColor.rgb(160, 170, 181)
            textSize = 9f
            isAntiAlias = true
        }

        var yPos = 48f

        // Document Header Bar
        val isDocRtl = isRtlText(documentTitle)
        val headerBannerText = if (isDocRtl) "المستند: $documentTitle" else "Document: $documentTitle"
        val headerLayout = StaticLayout.Builder.obtain(
            headerBannerText,
            0,
            headerBannerText.length,
            headerPaint,
            printableWidth.toInt()
        )
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setAlignment(if (isDocRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        canvas.save()
        canvas.translate(margin, yPos)
        headerLayout.draw(canvas)
        canvas.restore()

        yPos += 24f
        canvas.drawLine(margin, yPos, margin + printableWidth, yPos, dividerPaint)
        yPos += 24f

        fun performPageBreak() {
            canvas.drawText("Page $currentPageNum", margin + printableWidth - 40f, pageHeight - 24f, footerPaint)
            doc.finishPage(page)

            currentPageNum++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
            page = doc.startPage(pageInfo)
            canvas = page.canvas
            yPos = 52f

            canvas.drawLine(margin, yPos - 12f, margin + printableWidth, yPos - 12f, dividerPaint)
        }

        val total = paragraphs.size
        paragraphs.forEachIndexed { pIndex, para ->
            if (para.isBlank) {
                yPos += 6f
                return@forEachIndexed
            }

            val isParaRtl = isRtlText(para.plainText)

            when (para.type) {
                DocParagraphType.TITLE -> {
                    if (yPos + 50f > pageHeight - 50f) performPageBreak()
                    yPos += 10f
                    val runs = if (para.runs.isNotEmpty()) para.runs else listOf(DocTextRun(para.plainText, isBold = true))
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = runs,
                        defaultPaint = titlePaint,
                        defaultSize = 20f,
                        defaultColor = AndroidColor.rgb(21, 101, 192),
                        defaultBold = true,
                        printableWidth = printableWidth,
                        startX = margin,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 8f
                    val titleAccentPaint = Paint().apply {
                        color = AndroidColor.rgb(21, 101, 192)
                        strokeWidth = 2.5f
                        style = Paint.Style.STROKE
                    }
                    if (isParaRtl) {
                        canvas.drawLine(margin + printableWidth - 120f, yPos, margin + printableWidth, yPos, titleAccentPaint)
                    } else {
                        canvas.drawLine(margin, yPos, margin + 120f, yPos, titleAccentPaint)
                    }
                    yPos += 14f
                }

                DocParagraphType.HEADING_1 -> {
                    if (yPos + 40f > pageHeight - 50f) performPageBreak()
                    yPos += 12f
                    if (isParaRtl) {
                        // Reverse margin accent bar on the right
                        canvas.drawRoundRect(
                            RectF(margin + printableWidth + 3f, yPos - 2f, margin + printableWidth + 8f, yPos + 18f),
                            2f,
                            2f,
                            accentBarPaint
                        )
                    } else {
                        // Left accent bar
                        canvas.drawRoundRect(
                            RectF(margin - 8f, yPos - 2f, margin - 3f, yPos + 18f),
                            2f,
                            2f,
                            accentBarPaint
                        )
                    }
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = headerPaint,
                        defaultSize = 15f,
                        defaultColor = AndroidColor.rgb(13, 71, 161),
                        defaultBold = true,
                        printableWidth = printableWidth,
                        startX = margin,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 8f
                }

                DocParagraphType.HEADING_2 -> {
                    if (yPos + 34f > pageHeight - 50f) performPageBreak()
                    yPos += 10f
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = h2Paint,
                        defaultSize = 13.5f,
                        defaultColor = AndroidColor.rgb(40, 116, 166),
                        defaultBold = true,
                        printableWidth = printableWidth,
                        startX = margin,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 6f
                }

                DocParagraphType.HEADING_3 -> {
                    if (yPos + 28f > pageHeight - 50f) performPageBreak()
                    yPos += 8f
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = h3Paint,
                        defaultSize = 12f,
                        defaultColor = AndroidColor.rgb(55, 71, 79),
                        defaultBold = true,
                        printableWidth = printableWidth,
                        startX = margin,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 5f
                }

                DocParagraphType.SLIDE_HEADER -> {
                    if (yPos + 36f > pageHeight - 50f) performPageBreak()
                    yPos += 12f
                    val pillPaint = Paint().apply {
                        color = AndroidColor.rgb(224, 247, 250)
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawRoundRect(
                        RectF(margin, yPos - 6f, margin + printableWidth, yPos + 22f),
                        4f,
                        4f,
                        pillPaint
                    )
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = slideHeaderPaint,
                        defaultSize = 13f,
                        defaultColor = AndroidColor.rgb(0, 131, 143),
                        defaultBold = true,
                        printableWidth = printableWidth - 16f,
                        startX = margin + 8f,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 10f
                }

                DocParagraphType.BULLET_ITEM -> {
                    if (yPos + 22f > pageHeight - 50f) performPageBreak()
                    val bulletPaint = TextPaint().apply {
                        color = AndroidColor.rgb(13, 71, 161)
                        textSize = 12f
                        isAntiAlias = true
                    }

                    if (isParaRtl) {
                        // Reversed margin: bullet placed on right margin
                        canvas.drawText("•", margin + printableWidth - 10f, yPos + 11f, bulletPaint)
                        yPos = drawParagraphWithStaticLayout(
                            getCanvas = { canvas },
                            runs = para.runs,
                            defaultPaint = textPaint,
                            defaultSize = 11f,
                            defaultColor = AndroidColor.rgb(33, 37, 41),
                            defaultBold = false,
                            printableWidth = printableWidth - 20f,
                            startX = margin,
                            startY = yPos,
                            maxPageY = pageHeight - 50f,
                            alignmentOverride = para.alignment,
                            onPageBreak = { performPageBreak() }
                        )
                    } else {
                        // Left margin: bullet placed on left margin
                        canvas.drawText("•", margin + 4f, yPos + 11f, bulletPaint)
                        yPos = drawParagraphWithStaticLayout(
                            getCanvas = { canvas },
                            runs = para.runs,
                            defaultPaint = textPaint,
                            defaultSize = 11f,
                            defaultColor = AndroidColor.rgb(33, 37, 41),
                            defaultBold = false,
                            printableWidth = printableWidth - 20f,
                            startX = margin + 18f,
                            startY = yPos,
                            maxPageY = pageHeight - 50f,
                            alignmentOverride = para.alignment,
                            onPageBreak = { performPageBreak() }
                        )
                    }
                    yPos += 4f
                }

                DocParagraphType.TABLE_ROW -> {
                    if (yPos + 22f > pageHeight - 50f) performPageBreak()
                    val bgPaint = Paint().apply {
                        color = if (pIndex % 2 == 0) AndroidColor.rgb(248, 249, 250) else AndroidColor.WHITE
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(margin, yPos - 6f, margin + printableWidth, yPos + 18f, bgPaint)
                    canvas.drawLine(margin, yPos + 18f, margin + printableWidth, yPos + 18f, dividerPaint)

                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = tableRowPaint,
                        defaultSize = 10.5f,
                        defaultColor = AndroidColor.rgb(45, 55, 72),
                        defaultBold = false,
                        printableWidth = printableWidth - 8f,
                        startX = margin + 4f,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 5f
                }

                DocParagraphType.NORMAL -> {
                    if (yPos + 20f > pageHeight - 50f) performPageBreak()
                    yPos = drawParagraphWithStaticLayout(
                        getCanvas = { canvas },
                        runs = para.runs,
                        defaultPaint = textPaint,
                        defaultSize = 11f,
                        defaultColor = AndroidColor.rgb(33, 37, 41),
                        defaultBold = false,
                        printableWidth = printableWidth,
                        startX = margin,
                        startY = yPos,
                        maxPageY = pageHeight - 50f,
                        alignmentOverride = para.alignment,
                        onPageBreak = { performPageBreak() }
                    )
                    yPos += 6f
                }
            }

            onProgress(((pIndex + 1).toFloat() / total.toFloat()).coerceIn(0f, 0.95f))
        }

        // Final footer
        canvas.drawText("Page $currentPageNum", margin + printableWidth - 40f, pageHeight - 24f, footerPaint)
        doc.finishPage(page)

        FileOutputStream(outputFile).use { out -> doc.writeTo(out) }
        doc.close()

        onProgress(1.0f)
    }

    /**
     * Checks if a character sequence contains strong RTL characters as its primary direction.
     */
    fun isRtlText(charSequence: CharSequence): Boolean {
        if (charSequence.isEmpty()) return false
        return TextDirectionHeuristics.FIRSTSTRONG_RTL.isRtl(charSequence, 0, charSequence.length)
    }

    /**
     * Converts a list of DocTextRuns into an Android SpannableStringBuilder, preserving
     * bold, italic, underline, custom font sizes, and custom colors.
     */
    fun buildSpannedFromRuns(
        runs: List<DocTextRun>,
        defaultSize: Float,
        defaultColor: Int,
        defaultBold: Boolean
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        for (run in runs) {
            val start = builder.length
            builder.append(run.text)
            val end = builder.length
            if (start == end) continue

            val isBold = run.isBold || defaultBold
            val isItalic = run.isItalic
            val style = when {
                isBold && isItalic -> Typeface.BOLD_ITALIC
                isBold -> Typeface.BOLD
                isItalic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) {
                builder.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (run.isUnderline) {
                builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val fontSize = run.fontSizePt ?: defaultSize
            builder.setSpan(AbsoluteSizeSpan(fontSize.toInt(), false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val color = run.colorHex?.let { parseColorSafe(it) } ?: defaultColor
            builder.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    /**
     * Core layout & drawing function using Android's StaticLayout.Builder with FIRSTSTRONG_RTL heuristics.
     * Fully replaces manual line-wrapping to provide authentic Arabic cursive shaping, bidirectional
     * numbers & mixed Latin words (BiDi), and automatic linguistic line-breaking.
     */
    fun drawParagraphWithStaticLayout(
        getCanvas: () -> Canvas,
        runs: List<DocTextRun>,
        defaultPaint: TextPaint,
        defaultSize: Float,
        defaultColor: Int,
        defaultBold: Boolean,
        printableWidth: Float,
        startX: Float,
        startY: Float,
        maxPageY: Float,
        alignmentOverride: String? = null,
        onPageBreak: () -> Unit
    ): Float {
        if (runs.isEmpty()) return startY

        val spannable = buildSpannedFromRuns(runs, defaultSize, defaultColor, defaultBold)
        if (spannable.isEmpty()) return startY

        val textPaint = TextPaint(defaultPaint).apply {
            textSize = defaultSize
            color = defaultColor
            isAntiAlias = true
            if (defaultBold) isFakeBoldText = true
        }

        val isRtl = isRtlText(spannable)

        val alignment = when {
            alignmentOverride == "center" -> Layout.Alignment.ALIGN_CENTER
            alignmentOverride == "right" -> Layout.Alignment.ALIGN_OPPOSITE
            alignmentOverride == "left" -> Layout.Alignment.ALIGN_NORMAL
            isRtl -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }

        val targetWidth = printableWidth.toInt().coerceAtLeast(10)

        val staticLayout = StaticLayout.Builder.obtain(
            spannable,
            0,
            spannable.length,
            textPaint,
            targetWidth
        )
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setAlignment(alignment)
            .setLineSpacing(2f, 1.15f)
            .setIncludePad(false)
            .build()

        val totalHeight = staticLayout.height.toFloat()
        var currentY = startY

        // If the entire paragraph fits on the current page
        if (currentY + totalHeight <= maxPageY) {
            val canvas = getCanvas()
            canvas.save()
            canvas.translate(startX, currentY)
            staticLayout.draw(canvas)
            canvas.restore()
            return currentY + totalHeight
        }

        // If paragraph doesn't fit on this page, but fits on a fresh page:
        if (currentY > 70f && totalHeight <= (maxPageY - 52f)) {
            onPageBreak()
            currentY = 52f
            val canvas = getCanvas()
            canvas.save()
            canvas.translate(startX, currentY)
            staticLayout.draw(canvas)
            canvas.restore()
            return currentY + totalHeight
        }

        // Paragraph spans across multiple pages: break by lines safely
        var lineIndex = 0
        val lineCount = staticLayout.lineCount

        while (lineIndex < lineCount) {
            var nextLineIndex = lineIndex + 1
            while (nextLineIndex < lineCount && (currentY + (staticLayout.getLineBottom(nextLineIndex - 1) - staticLayout.getLineTop(lineIndex)) <= maxPageY)) {
                nextLineIndex++
            }

            val lastLineInChunk = (nextLineIndex - 1).coerceAtLeast(lineIndex)
            val chunkStart = staticLayout.getLineStart(lineIndex)
            val chunkEnd = staticLayout.getLineEnd(lastLineInChunk)

            if (chunkStart < chunkEnd) {
                val chunkSeq = spannable.subSequence(chunkStart, chunkEnd)
                val chunkIsRtl = isRtlText(chunkSeq)
                val chunkAlign = when {
                    alignmentOverride == "center" -> Layout.Alignment.ALIGN_CENTER
                    alignmentOverride == "right" -> Layout.Alignment.ALIGN_OPPOSITE
                    alignmentOverride == "left" -> Layout.Alignment.ALIGN_NORMAL
                    chunkIsRtl -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }

                val chunkLayout = StaticLayout.Builder.obtain(
                    chunkSeq,
                    0,
                    chunkSeq.length,
                    textPaint,
                    targetWidth
                )
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setAlignment(chunkAlign)
                    .setLineSpacing(2f, 1.15f)
                    .setIncludePad(false)
                    .build()

                val canvas = getCanvas()
                canvas.save()
                canvas.translate(startX, currentY)
                chunkLayout.draw(canvas)
                canvas.restore()

                currentY += chunkLayout.height.toFloat()
            }

            lineIndex = lastLineInChunk + 1
            if (lineIndex < lineCount) {
                onPageBreak()
                currentY = 52f
            }
        }

        return currentY
    }

    private fun getAttrValue(parser: XmlPullParser, attrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name.equals(attrName, ignoreCase = true) || name.endsWith(":$attrName", ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseColorSafe(hex: String): Int? {
        return try {
            val clean = hex.trim().removePrefix("#")
            when (clean.length) {
                6 -> AndroidColor.parseColor("#$clean")
                8 -> AndroidColor.parseColor("#$clean")
                3 -> {
                    val r = clean[0]
                    val g = clean[1]
                    val b = clean[2]
                    AndroidColor.parseColor("#$r$r$g$g$b$b")
                }
                else -> null
            }
        } catch (e: Exception) {
            AppLogger.logSilentFailure(TAG, "فشل تحليل قيمة اللون: $hex", e)
            null
        }
    }
}
