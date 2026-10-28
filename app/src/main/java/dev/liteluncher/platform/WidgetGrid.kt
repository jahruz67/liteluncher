package dev.liteluncher.platform

import dev.liteluncher.data.WidgetItem

/** All coordinates are zero-based. Failed placements leave persisted widgets recoverable. */
internal object WidgetGrid {
    fun overlaps(a: WidgetItem, b: WidgetItem): Boolean =
        a.page == b.page && a.column < b.column + b.columns && a.column + a.columns > b.column &&
            a.row < b.row + b.rows && a.row + a.rows > b.row

    fun fits(item: WidgetItem, others: List<WidgetItem>, columns: Int, rows: Int): Boolean =
        item.column >= 0 && item.row >= 0 && item.columns > 0 && item.rows > 0 &&
            item.columns <= columns && item.rows <= rows &&
            item.column <= columns - item.columns && item.row <= rows - item.rows &&
            others.none { it.id != item.id && overlaps(item, it) }

    fun place(item: WidgetItem, others: List<WidgetItem>, columns: Int, rows: Int): WidgetItem? {
        if (item.columns !in 1..columns || item.rows !in 1..rows) return null
        val bounded = item
        if (fits(bounded, others, columns, rows)) return bounded
        for (row in 0..(rows - bounded.rows)) {
            for (column in 0..(columns - bounded.columns)) {
                val candidate = bounded.copy(column = column, row = row)
                if (fits(candidate, others, columns, rows)) return candidate
            }
        }
        return null
    }

    data class Layout(val placed: List<WidgetItem>, val overflow: List<WidgetItem>)

    fun arrange(items: List<WidgetItem>, columns: Int, rows: Int, blocked: List<WidgetItem> = emptyList()): Layout {
        val placed = mutableListOf<WidgetItem>()
        val overflow = mutableListOf<WidgetItem>()
        items.distinctBy { it.id }.forEach { item ->
            val next = place(item, placed + blocked, columns, rows)
            if (next == null) overflow += item else placed += next
        }
        return Layout(placed, overflow)
    }
}
