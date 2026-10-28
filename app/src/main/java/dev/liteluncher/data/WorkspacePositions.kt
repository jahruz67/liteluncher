package dev.liteluncher.data

internal val LauncherState.minimumDesktopPages: Int get() = ((desktopApps.filterValues { it.isNotEmpty() }.keys +
    widgets.map { it.page } + pendingWidgets.map { it.page } + shortcuts.mapNotNull {
        it.container.takeIf { name -> name.startsWith("desktop:") }?.substringAfter(':')?.toIntOrNull()
    }).filter { it in 0..15 }.maxOrNull() ?: 0) + 1

internal fun LauncherState.desktopObstacles(exceptRestoreKey: String = ""): List<WidgetItem> =
    (0 until prefs.desktop.pageCount).flatMap { page ->
        workspacePositions("desktop:$page", prefs.desktop.columns, prefs.desktop.rows).mapIndexed { index, p ->
            WidgetItem(-1 - page * MAX_LAYOUT_APPS - index, p.column, p.row, p.columns, p.rows, page)
        }
    } + pendingWidgets.filter { it.key != exceptRestoreKey }.mapIndexed { index, p -> p.location().copy(id = -100_000 - index) }

internal fun LauncherState.workspaceKeys(container: String): List<String> {
    val entries = when {
        container == "home" -> homeApps + homeFolders.map { "folder:$it" }
        container == "dock" -> dockApps + dockFolders.map { "folder:$it" }
        container.startsWith("desktop:") -> container.substringAfter(':').toIntOrNull()?.let { desktopApps[it] }.orEmpty()
        container.startsWith("folder:") -> folders.find { "folder:${it.id}" == container }?.apps.orEmpty()
        else -> emptyList()
    } + shortcuts.filter { it.container == container }.map { it.layoutKey }
    val order = if (container.startsWith("folder:")) folders.find { "folder:${it.id}" == container }?.entryOrder.orEmpty()
        else positions.filter { it.container == container }.sortedWith(compareBy<ItemPosition> { it.row }.thenBy { it.column }).map { it.key }
    return (order.filter { it in entries } + entries).distinct()
}

/** Keep holes and missing apps in place; new entries use the first unoccupied cell. */
internal fun LauncherState.workspacePositions(container: String, columns: Int, rows: Int): List<ItemPosition> {
    val keys = workspaceKeys(container)
    val occupied = BooleanArray(columns * rows)
    val result = mutableListOf<ItemPosition>()
    fun reserve(position: ItemPosition): Boolean {
        if (position.column < 0 || position.row < 0 || position.columns < 1 || position.rows < 1 ||
            position.column > columns - position.columns || position.row > rows - position.rows) return false
        for (y in position.row until position.row + position.rows) for (x in position.column until position.column + position.columns) {
            if (occupied[y * columns + x]) return false
        }
        for (y in position.row until position.row + position.rows) for (x in position.column until position.column + position.columns) occupied[y * columns + x] = true
        result.add(position)
        return true
    }
    positions.filter { it.container == container && it.key in keys }.distinctBy { it.key }.forEach { reserve(it) }
    val placed = result.map { it.key }.toSet()
    for (key in keys.filterNot { it in placed }) {
        val slot = occupied.indexOfFirst { !it }
        if (slot < 0) break
        reserve(ItemPosition(key, container, slot % columns, slot / columns))
    }
    return result
}

internal fun LauncherState.sanitizePositions(): LauncherState {
    val containers = setOf("home", "dock") + (0 until 16).map { "desktop:$it" } + folders.map { "folder:${it.id}" }
    val candidates = containers.associateWith { workspaceKeys(it).toSet() }
    val positions = this.positions.asSequence().filter { position ->
        val columns = if (position.container == "dock") 1 else 16
        val rows = if (position.container == "dock") MAX_LAYOUT_APPS else 32
        position.key in candidates[position.container].orEmpty() && position.column >= 0 && position.row >= 0 &&
            position.columns in 1..columns && position.rows in 1..rows &&
            position.column <= columns - position.columns && position.row <= rows - position.rows
    }.distinctBy { it.container to it.key }.groupBy { it.container }.values.flatMap { it.take(MAX_LAYOUT_APPS) }
    val widgets = pendingWidgets.asSequence().filter { widget ->
        widget.key.isNotBlank() && widget.key.length <= 512 && widget.provider.length <= 512 &&
            android.content.ComponentName.unflattenFromString(widget.provider) != null &&
            widget.page in 0..15 && widget.column >= 0 && widget.row >= 0 &&
            widget.columns in 1..16 && widget.rows in 1..32 &&
            widget.column <= 16 - widget.columns && widget.row <= 32 - widget.rows
    }.distinctBy { it.key }.take(64).toList()
    return copy(positions = positions, pendingWidgets = widgets,
        shortcuts = shortcuts.map { if (it.container in containers || it.container == "library") it else it.copy(container = "home") },
        lynxSettings = lynxSettings.entries.asSequence().filter { it.key.length <= 128 && it.value.length <= 2_048 }
            .take(512).associate { it.toPair() })
}
