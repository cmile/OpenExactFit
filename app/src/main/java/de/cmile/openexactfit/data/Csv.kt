package de.cmile.openexactfit.data

/**
 * just enough csv for our own exports and python's csv module: quoted fields, doubled
 * quotes inside them, commas and line breaks inside quotes, crlf or lf line ends.
 */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quoted -> when {
                c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = false
                else -> field.append(c)
            }
            c == '"' -> quoted = true
            c == ',' -> { row += field.toString(); field.clear() }
            c == '\r' -> Unit
            c == '\n' -> { row += field.toString(); field.clear(); rows += row.toList(); row.clear() }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        rows += row.toList()
    }
    return rows
}

/** the rows after the header line as column-name to value maps; blank lines are skipped. */
fun csvRecords(text: String): List<Map<String, String>> {
    val rows = parseCsv(text)
    if (rows.isEmpty()) return emptyList()
    val header = rows.first()
    return rows.drop(1)
        .filter { r -> r.any { it.isNotEmpty() } }
        .map { r -> header.indices.associate { header[it] to r.getOrElse(it) { "" } } }
}

/** one field for a csv line: quoted when it holds a comma, a quote or a line break. */
fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
