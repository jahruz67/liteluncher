package dev.liteluncher.data

import java.util.UUID

internal const val MAX_LAYOUT_FOLDERS = 128
internal const val MAX_LAYOUT_APPS = 2_048

enum class LayoutArea(val title: String) { HOME("Home"), DOCK("Dock"), FAVORITES("Favorites") }

internal sealed interface AppDropTarget {
    data class Area(val area: LayoutArea, val atKey: String? = null) : AppDropTarget
    data class CreateFolder(val area: LayoutArea, val atKey: String) : AppDropTarget
    data class Folder(val id: String, val atKey: String? = null) : AppDropTarget
    data class DesktopCell(val page: Int, val column: Int, val row: Int) : AppDropTarget
    data object Remove : AppDropTarget
}

internal data class LayoutEditResult(val state: LauncherState, val error: String? = null)

internal fun LauncherState.layoutApps(area: LayoutArea): List<String> = when (area) {
    LayoutArea.HOME -> homeApps
    LayoutArea.DOCK -> dockApps
    LayoutArea.FAVORITES -> favoriteApps
}

internal fun LauncherState.layoutLocked(area: LayoutArea): Boolean = when (area) {
    LayoutArea.HOME -> prefs.home.preventChanges
    LayoutArea.DOCK -> prefs.dock.preventChanges
    LayoutArea.FAVORITES -> false
}

internal fun LauncherState.layoutSpace(area: LayoutArea): Int = when (area) {
    LayoutArea.HOME -> prefs.home.rows * prefs.home.columns - ((homeApps + homeFolders.map { "folder:$it" } + shortcuts.filter { it.container == "home" }.map { it.layoutKey }).distinct().sumOf { key -> positions.find { it.container == "home" && it.key == key }?.let { it.columns * it.rows } ?: 1 })
    LayoutArea.DOCK -> MAX_LAYOUT_APPS - dockApps.size - dockFolders.size
    LayoutArea.FAVORITES -> prefs.favorites.maxItems - favoriteApps.size
}.coerceAtLeast(0)

internal fun LauncherState.folderLocked(id: String): Boolean =
    (id in homeFolders && prefs.home.preventChanges) || (id in dockFolders && prefs.dock.preventChanges) ||
        (desktopApps.values.any { "folder:$id" in it } && prefs.desktop.preventChanges)

internal fun LauncherState.withLayoutApps(area: LayoutArea, keys: List<String>): LauncherState {
    val before = layoutApps(area)
    val next = when (area) {
        LayoutArea.HOME -> copy(homeApps = keys)
        LayoutArea.DOCK -> copy(dockApps = keys)
        LayoutArea.FAVORITES -> copy(favoriteApps = keys)
    }
    if (area == LayoutArea.FAVORITES || before == keys || before.toSet() != keys.toSet()) return next
    return next.reorderPositions(if (area == LayoutArea.HOME) "home" else "dock", before, keys)
}

private fun LauncherState.reorderPositions(container: String, before: List<String>, after: List<String>): LauncherState {
    if (before.toSet() != after.toSet()) return this
    val replacements = before.zip(after).toMap()
    return copy(positions = positions.map { position ->
        if (position.container == container) replacements[position.key]?.let { position.copy(key = it) } ?: position else position
    })
}

internal fun removeLayoutApp(state: LauncherState, area: LayoutArea, key: String): LauncherState {
    if (state.layoutLocked(area)) return state
    val container = if (area == LayoutArea.HOME) "home" else "dock"
    val positioned = if (area == LayoutArea.FAVORITES) state else state.copy(positions =
        state.positions.filterNot { it.container == container } + state.workspacePositions(container,
            if (area == LayoutArea.HOME) state.prefs.home.columns else 1,
            if (area == LayoutArea.HOME) state.prefs.home.rows else MAX_LAYOUT_APPS).filterNot { it.key == key })
    val next = positioned.withLayoutApps(area, positioned.layoutApps(area) - key)
    return if (area == LayoutArea.FAVORITES) next.copy(prefs = next.prefs.copy(
        favorites = next.prefs.favorites.copy(excluded = next.prefs.favorites.excluded + key)
    )) else next
}

internal fun restoreLayoutApp(state: LauncherState, area: LayoutArea, key: String, index: Int, position: ItemPosition? = null): LayoutEditResult {
    if (state.layoutLocked(area)) return LayoutEditResult(state, "${area.title} is locked")
    if (index < 0) return LayoutEditResult(if (area == LayoutArea.FAVORITES) state.copy(prefs = state.prefs.copy(
        favorites = state.prefs.favorites.copy(excluded = state.prefs.favorites.excluded - key)
    )) else state)
    if (key in state.layoutApps(area)) return LayoutEditResult(state)
    val placed = applyAppDrop(state, key, null, AppDropTarget.Area(area))
    if (placed.error != null) return placed
    if (position != null) {
        val columns = if (area == LayoutArea.HOME) state.prefs.home.columns else 1
        val rows = if (area == LayoutArea.HOME) state.prefs.home.rows else MAX_LAYOUT_APPS
        val others = state.workspacePositions(position.container, columns, rows)
        if (position.column + position.columns > columns || position.row + position.rows > rows || others.any {
            position.column < it.column + it.columns && position.column + position.columns > it.column &&
                position.row < it.row + it.rows && position.row + position.rows > it.row
        }) return LayoutEditResult(state, "The original cell is occupied or outside the grid")
        return LayoutEditResult(placed.state.copy(positions = placed.state.positions.filterNot { it.container == position.container && it.key == key } + position))
    }
    val list = placed.state.layoutApps(area)
    return LayoutEditResult(placed.state.withLayoutApps(area, list.moveLayoutItem(list.lastIndex, index.coerceIn(0, list.lastIndex))))
}

internal fun <T> List<T>.moveLayoutItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

internal fun moveLayoutApp(state: LauncherState, area: LayoutArea, key: String, step: Int): LauncherState {
    if (state.layoutLocked(area)) return state
    val list = state.layoutApps(area)
    val index = list.indexOf(key)
    return state.withLayoutApps(area, list.moveLayoutItem(index, index + step))
}

internal fun applyAppDrop(state: LauncherState, key: String, source: LayoutArea?, target: AppDropTarget, sourceFolder: String? = null): LayoutEditResult {
    if (source != null && state.layoutLocked(source)) return LayoutEditResult(state, "${source.title} is locked")
    if (sourceFolder != null && (state.folders.none { it.id == sourceFolder } || state.folderLocked(sourceFolder)))
        return LayoutEditResult(state, "The source folder is unavailable or locked")
    val result = when (target) {
        AppDropTarget.Remove -> when {
            sourceFolder != null -> {
                val nextFolders = state.folders.map {
                    if (it.id == sourceFolder) it.copy(apps = it.apps - key, entryOrder = it.entryOrder - key) else it
                }
                LayoutEditResult(state.copy(folders = nextFolders))
            }
            source != null -> LayoutEditResult(removeLayoutApp(state, source, key))
            else -> LayoutEditResult(state, "This app is not a pinned entry")
        }
        is AppDropTarget.Area -> placeLayoutApp(state, key, source, target)
        is AppDropTarget.CreateFolder -> createLayoutFolder(state, key, source, target)
        is AppDropTarget.Folder -> placeFolderApp(state, key, source, target)
        is AppDropTarget.DesktopCell -> placeDesktopApp(state, key, source, target)
    }
    val staysInFolder = target is AppDropTarget.Folder && target.id == sourceFolder ||
        target is AppDropTarget.Area && target.area == LayoutArea.FAVORITES
    if (result.error != null || result.state == state) return result
    if (sourceFolder == null || staysInFolder) return result.copy(state = result.state.collapseSmallFolders())
    return result.copy(state = result.state.copy(folders = result.state.folders.map {
        if (it.id == sourceFolder) it.copy(apps = it.apps - key) else it
    }).collapseSmallFolders())
}

private fun placeDesktopApp(state: LauncherState, key: String, source: LayoutArea?, target: AppDropTarget.DesktopCell): LayoutEditResult {
    if (state.prefs.desktop.preventChanges) return LayoutEditResult(state, "Desktop is locked")
    if (target.page !in 0 until state.prefs.desktop.pageCount) return LayoutEditResult(state, "This desktop page is unavailable")
    val candidate = WidgetItem(0, target.column, target.row, 1, 1, target.page)
    val container = "desktop:${target.page}"
    val occupied = state.workspacePositions(container, state.prefs.desktop.columns, state.prefs.desktop.rows)
        .filterNot { it.key == key }.mapIndexed { index, p -> WidgetItem(-index - 1, p.column, p.row, p.columns, p.rows, target.page) } +
        state.widgets + state.pendingWidgets.mapIndexed { index, p -> p.location().copy(id = -100_000 - index) }
    if (!dev.liteluncher.platform.WidgetGrid.fits(candidate, occupied, state.prefs.desktop.columns, state.prefs.desktop.rows))
        return LayoutEditResult(state, "This desktop cell is occupied")
    var next = state.copy(desktopApps = state.desktopApps + (target.page to (state.desktopApps[target.page].orEmpty() + key).distinct()),
        positions = state.positions.filterNot { it.container == container && it.key == key } + ItemPosition(key, container, target.column, target.row))
    if (source != null && source != LayoutArea.FAVORITES) next = next.withLayoutApps(source, next.layoutApps(source) - key)
    return LayoutEditResult(next)
}

// Count shortcuts as folder members when normalizing restored layouts and edits.
internal fun LauncherState.collapseSmallFolders(): LauncherState {
    val small = folders.filter { folder -> folder.apps.size + shortcuts.count { it.container == "folder:${folder.id}" } == 1 }
    if (small.isEmpty()) return this
    val ids = small.map { it.id }.toSet()
    val replacements = small.associate { folder -> "folder:${folder.id}" to
        (folder.apps.firstOrNull() ?: shortcuts.find { it.container == "folder:${folder.id}" }?.layoutKey) }
    return copy(
        homeApps = (homeApps + small.filter { it.id in homeFolders }.flatMap { it.apps }).distinct(),
        dockApps = (dockApps + small.filter { it.id in dockFolders }.flatMap { it.apps }).distinct(),
        homeFolders = homeFolders - ids, dockFolders = dockFolders - ids,
        folders = folders.filterNot { it.id in ids },
        shortcuts = shortcuts.flatMap { shortcut ->
            val folder = small.find { shortcut.container == "folder:${it.id}" }
            if (folder == null) listOf(shortcut) else {
                val targets = mutableListOf<String>()
                if (folder.id in dockFolders) targets.add("dock")
                if (folder.id in homeFolders) targets.add("home")
                desktopApps.forEach { (page, keys) ->
                    if ("folder:${folder.id}" in keys) targets.add("desktop:$page")
                }
                if (targets.isEmpty()) targets.add("home")
                targets.distinct().map { shortcut.copy(container = it) }
            }
        },
        positions = positions.mapNotNull { position ->
            if (position.key !in replacements) position else replacements[position.key]?.let { position.copy(key = it) }
        },
        desktopApps = desktopApps.mapValues { (_, keys) -> keys.mapNotNull { if (it in replacements) replacements[it] else it } }
    )
}

internal fun applyFolderDrop(state: LauncherState, id: String, source: LayoutArea?, target: AppDropTarget): LayoutEditResult {
    if (state.folders.none { it.id == id }) return LayoutEditResult(state, "This folder no longer exists")
    if (state.folderLocked(id)) return LayoutEditResult(state, "This folder is locked")
    if (target == AppDropTarget.Remove) return LayoutEditResult(state.copy(
        folders = state.folders.filterNot { it.id == id }, homeFolders = state.homeFolders - id,
        dockFolders = state.dockFolders - id,
        desktopApps = state.desktopApps.mapValues { it.value - "folder:$id" },
        shortcuts = state.shortcuts.map { if (it.container == "folder:$id") it.copy(container = "library") else it },
        positions = state.positions.filterNot { it.key == "folder:$id" || it.container == "folder:$id" }
    ))
    if (target is AppDropTarget.DesktopCell) {
        val nextState = state.copy(homeFolders = state.homeFolders - id, dockFolders = state.dockFolders - id,
            desktopApps = state.desktopApps.mapValues { (_, keys) -> keys - "folder:$id" },
            positions = state.positions.filterNot { it.key == "folder:$id" && it.container.startsWith("desktop:") })
        return placeDesktopApp(nextState, "folder:$id", source, target)
    }
    val targetDesktopEntry = (target as? AppDropTarget.Folder)?.let { tf ->
        state.desktopApps.entries.find { "folder:${tf.id}" in it.value }
    }
    if (targetDesktopEntry != null) {
        val page = targetDesktopEntry.key
        val nextState = state.copy(homeFolders = state.homeFolders - id, dockFolders = state.dockFolders - id,
            desktopApps = state.desktopApps.mapValues { (_, keys) -> keys - "folder:$id" },
            positions = state.positions.filterNot { it.key == "folder:$id" })
        for (row in 0 until nextState.prefs.desktop.rows) for (column in 0 until nextState.prefs.desktop.columns) {
            val moved = placeDesktopApp(nextState, "folder:$id", source, AppDropTarget.DesktopCell(page, column, row))
            if (moved.error == null) return moved
        }
        return LayoutEditResult(state, "There is no free desktop cell for this folder")
    }
    val area = when (target) {
        is AppDropTarget.Area -> target.area
        is AppDropTarget.Folder -> if (target.id in state.dockFolders) LayoutArea.DOCK else LayoutArea.HOME
        else -> return LayoutEditResult(state)
    }
    if (area == LayoutArea.FAVORITES || state.layoutLocked(area)) return LayoutEditResult(state)
    if (source != area && state.layoutSpace(area) == 0) return LayoutEditResult(state, "${area.title} is full")
    val list = (if (area == LayoutArea.HOME) state.homeFolders else state.dockFolders) - id
    val at = (target as? AppDropTarget.Folder)?.id?.let { list.indexOf(it) } ?: -1
    val ordered = list.toMutableList().apply { add(if (at >= 0) at else size, id) }
    val next = state.copy(
        homeFolders = if (area == LayoutArea.HOME) ordered else if (source == LayoutArea.HOME) state.homeFolders - id else state.homeFolders,
        dockFolders = if (area == LayoutArea.DOCK) ordered else if (source == LayoutArea.DOCK) state.dockFolders - id else state.dockFolders,
        desktopApps = state.desktopApps.mapValues { (_, keys) -> keys - "folder:$id" },
        positions = state.positions.filterNot { it.container.startsWith("desktop:") && it.key == "folder:$id" }
    )
    val before = if (area == LayoutArea.HOME) state.homeFolders else state.dockFolders
    val container = if (area == LayoutArea.HOME) "home" else "dock"
    val anchor = (target as? AppDropTarget.Area)?.atKey ?: (target as? AppDropTarget.Folder)?.let { "folder:${it.id}" }
    return LayoutEditResult(if (anchor != null) next.positionBefore(state, container, "folder:$id", anchor)
        else next.reorderPositions(container, before.map { "folder:$it" }, ordered.map { "folder:$it" }))
}

private fun createLayoutFolder(state: LauncherState, key: String, source: LayoutArea?, target: AppDropTarget.CreateFolder): LayoutEditResult {
    val area = target.area
    if (area == LayoutArea.FAVORITES) return LayoutEditResult(state, "Folders can only be created on Home or Dock")
    if (state.layoutLocked(area)) return LayoutEditResult(state, "${area.title} is locked")
    if (key == target.atKey) return LayoutEditResult(state)
    val apps = state.layoutApps(area)
    if (target.atKey !in apps) return LayoutEditResult(state, "This app is no longer on ${area.title}")
    if (state.folders.size >= MAX_LAYOUT_FOLDERS) return LayoutEditResult(state, "The folder limit has been reached")
    val folder = FolderItem(UUID.randomUUID().toString(), "Folder", listOf(target.atKey, key))
    var next = state.withLayoutApps(area, apps - folder.apps.toSet()).copy(folders = state.folders + folder)
    next = if (area == LayoutArea.HOME) next.copy(homeFolders = next.homeFolders + folder.id)
        else next.copy(dockFolders = next.dockFolders + folder.id)
    if (source != null && source != area && source != LayoutArea.FAVORITES)
        next = next.withLayoutApps(source, next.layoutApps(source) - key)
    val container = if (area == LayoutArea.HOME) "home" else "dock"
    val anchor = state.workspacePositions(container, if (area == LayoutArea.HOME) state.prefs.home.columns else 1,
        if (area == LayoutArea.HOME) state.prefs.home.rows else MAX_LAYOUT_APPS).find { it.key == target.atKey }
    return LayoutEditResult(if (anchor == null) next else next.copy(positions = next.positions.filterNot {
        it.container == container && it.key in folder.apps
    } + anchor.copy(key = "folder:${folder.id}")))
}

private fun placeLayoutApp(state: LauncherState, key: String, source: LayoutArea?, target: AppDropTarget.Area): LayoutEditResult {
    val area = target.area
    if (state.layoutLocked(area)) return LayoutEditResult(state, "${area.title} is locked")
    val list = state.layoutApps(area)
    if (key !in list && state.layoutSpace(area) == 0) return LayoutEditResult(state, "${area.title} is full. Remove an entry or increase its size in Settings.")
    val from = list.indexOf(key)
    val to = target.atKey?.let { list.indexOf(it) } ?: -1
    val ordered = if (from >= 0 && to >= 0) list.moveLayoutItem(from, to)
        else (list - key).toMutableList().apply { add(if (to >= 0) to.coerceAtMost(size) else size, key) }
    var next = state.withLayoutApps(area, ordered)
    if (area != LayoutArea.FAVORITES && target.atKey != null && key !in list) {
        next = next.positionBefore(state, if (area == LayoutArea.HOME) "home" else "dock", key, target.atKey)
    }
    if (source != null && source != area && source != LayoutArea.FAVORITES && area != LayoutArea.FAVORITES) {
        next = next.withLayoutApps(source, next.layoutApps(source) - key)
    }
    if (area == LayoutArea.FAVORITES) next = next.copy(prefs = next.prefs.copy(
        favorites = next.prefs.favorites.copy(excluded = next.prefs.favorites.excluded - key)
    ))
    return LayoutEditResult(next)
}

private fun LauncherState.positionBefore(before: LauncherState, container: String, key: String, anchor: String): LauncherState {
    val columns = if (container == "home") prefs.home.columns else 1
    val rows = if (container == "home") prefs.home.rows else MAX_LAYOUT_APPS
    val old = before.workspacePositions(container, columns, rows)
    val order = before.workspaceKeys(container).toMutableList().apply {
        remove(key)
        val index = indexOf(anchor)
        add(if (index < 0) size else index, key)
    }
    val cells = (old + workspacePositions(container, columns, rows).filter { p -> old.none { it.column == p.column && it.row == p.row } })
        .sortedWith(compareBy<ItemPosition> { it.row }.thenBy { it.column })
    return copy(positions = positions.filterNot { it.container == container } + order.zip(cells) { item, cell -> cell.copy(key = item) })
}

private fun placeFolderApp(state: LauncherState, key: String, source: LayoutArea?, target: AppDropTarget.Folder): LayoutEditResult {
    val id = target.id
    val folder = state.folders.find { it.id == id } ?: return LayoutEditResult(state, "This folder no longer exists")
    if (state.folderLocked(id)) return LayoutEditResult(state, "This folder is on a locked layout")
    if (key !in folder.apps && folder.apps.size >= MAX_LAYOUT_APPS) return LayoutEditResult(state, "This folder is full")
    val from = folder.apps.indexOf(key)
    val to = target.atKey?.let { folder.apps.indexOf(it) } ?: -1
    val ordered = if (from >= 0 && to >= 0) folder.apps.moveLayoutItem(from, to)
        else (folder.apps - key).toMutableList().apply { add(if (to >= 0) to.coerceAtMost(size) else size, key) }
    val replacements = folder.apps.zip(ordered).toMap()
    val entryOrder = if (folder.apps.toSet() == ordered.toSet()) folder.entryOrder.map { replacements[it] ?: it }
        else folder.entryOrder.filter { it !in folder.apps || it in ordered } + ordered.filter { it !in folder.entryOrder }
    var next = state.copy(folders = state.folders.map { if (it.id == id) it.copy(apps = ordered, entryOrder = entryOrder.distinct()) else it })
    if (source != null && source != LayoutArea.FAVORITES) next = next.withLayoutApps(source, next.layoutApps(source) - key)
    return LayoutEditResult(next)
}

internal fun toggleLayoutFolder(state: LauncherState, area: LayoutArea, id: String): LauncherState {
    if (area == LayoutArea.FAVORITES || state.layoutLocked(area) || state.folders.none { it.id == id }) return state
    val list = if (area == LayoutArea.HOME) state.homeFolders else state.dockFolders
    if (id !in list && state.layoutSpace(area) == 0) return state
    val next = if (id in list) list - id else list + id
    return if (area == LayoutArea.HOME) state.copy(homeFolders = next) else state.copy(dockFolders = next)
}

internal fun moveLayoutFolder(state: LauncherState, area: LayoutArea, id: String, step: Int): LauncherState {
    if (area == LayoutArea.FAVORITES || state.layoutLocked(area)) return state
    val list = if (area == LayoutArea.HOME) state.homeFolders else state.dockFolders
    val index = list.indexOf(id)
    val next = list.moveLayoutItem(index, index + step)
    val changed = if (area == LayoutArea.HOME) state.copy(homeFolders = next) else state.copy(dockFolders = next)
    return changed.reorderPositions(if (area == LayoutArea.HOME) "home" else "dock", list.map { "folder:$it" }, next.map { "folder:$it" })
}
