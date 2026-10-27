package dev.liteluncher.data

/** Apply a canvas edit without discarding widgets added since that canvas was rendered. */
fun mergeWidgetEdits(current: List<WidgetItem>, expected: List<WidgetItem>, edited: List<WidgetItem>): List<WidgetItem> {
    val original = expected.associateBy { it.id }
    val replacement = edited.associateBy { it.id }
    val removed = original.keys - replacement.keys
    val merged = current.mapNotNull { item ->
        when {
            item.id in removed -> null
            original[item.id] == item -> replacement[item.id] ?: item
            else -> item
        }
    }
    val existing = merged.mapTo(mutableSetOf()) { it.id }
    return merged + edited.filter { it.id !in original && existing.add(it.id) }
}
