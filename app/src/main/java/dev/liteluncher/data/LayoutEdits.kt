package dev.liteluncher.data

import java.util.UUID

internal const val MAX_LAYOUT_FOLDERS = 128
internal const val MAX_LAYOUT_APPS = 2_048

enum class LayoutArea(val title: String) { HOME("Home"), DOCK("Dock"), FAVORITES("Favorites") }

internal sealed interface AppDropTarget {
    data class Area(val area: LayoutArea, val atKey: String? = null) : AppDropTarget
    data class CreateFolder(val area: LayoutArea, val atKey: String) : AppDropTarget
    data class Folder(val id: String, val atKey: String? = null) : AppDropTarget
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
    LayoutArea.HOME -> prefs.home.rows * prefs.home.columns - homeApps.size - homeFolders.size - shortcuts.size
    LayoutArea.DOCK -> prefs.dock.maxItems - dockApps.size - dockFolders.size
    LayoutArea.FAVORITES -> prefs.favorites.maxItems - favoriteApps.size
}.coerceAtLeast(0)

internal fun LauncherState.folderLocked(id: String): Boolean =
    (id in homeFolders && prefs.home.preventChanges) || (id in dockFolders && prefs.dock.preventChanges)

internal fun LauncherState.withLayoutApps(area: LayoutArea, keys: List<String>): LauncherState = when (area) {
    LayoutArea.HOME -> copy(homeApps = keys)
    LayoutArea.DOCK -> copy(dockApps = keys)
    LayoutArea.FAVORITES -> copy(favoriteApps = keys)
}

internal fun removeLayoutApp(state: LauncherState, area: LayoutArea, key: String): LauncherState {
    if (state.layoutLocked(area)) return state
    val next = state.withLayoutApps(area, state.layoutApps(area) - key)
    return if (area == LayoutArea.FAVORITES) next.copy(prefs = next.prefs.copy(
        favorites = next.prefs.favorites.copy(excluded = next.prefs.favorites.excluded + key)
    )) else next
}

internal fun restoreLayoutApp(state: LauncherState, area: LayoutArea, key: String, index: Int): LayoutEditResult {
    if (state.layoutLocked(area)) return LayoutEditResult(state, "${area.title} is locked")
    if (index < 0) return LayoutEditResult(if (area == LayoutArea.FAVORITES) state.copy(prefs = state.prefs.copy(
        favorites = state.prefs.favorites.copy(excluded = state.prefs.favorites.excluded - key)
    )) else state)
    if (key in state.layoutApps(area)) return LayoutEditResult(state)
    val placed = applyAppDrop(state, key, null, AppDropTarget.Area(area))
    if (placed.error != null) return placed
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
        AppDropTarget.Remove -> if (source == null) LayoutEditResult(state, "This app is not a pinned entry")
            else LayoutEditResult(removeLayoutApp(state, source, key))
        is AppDropTarget.Area -> placeLayoutApp(state, key, source, target)
        is AppDropTarget.CreateFolder -> createLayoutFolder(state, key, source, target)
        is AppDropTarget.Folder -> placeFolderApp(state, key, source, target)
    }
    val staysInFolder = target is AppDropTarget.Folder && target.id == sourceFolder ||
        target is AppDropTarget.Area && target.area == LayoutArea.FAVORITES
    if (result.error != null || sourceFolder == null || staysInFolder) return result
    return result.copy(state = result.state.copy(folders = result.state.folders.map {
        if (it.id == sourceFolder) it.copy(apps = it.apps - key) else it
    }))
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
    return LayoutEditResult(next)
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
    if (source != null && source != area && source != LayoutArea.FAVORITES && area != LayoutArea.FAVORITES) {
        next = next.withLayoutApps(source, next.layoutApps(source) - key)
    }
    if (area == LayoutArea.FAVORITES) next = next.copy(prefs = next.prefs.copy(
        favorites = next.prefs.favorites.copy(excluded = next.prefs.favorites.excluded - key)
    ))
    return LayoutEditResult(next)
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
    var next = state.copy(folders = state.folders.map { if (it.id == id) it.copy(apps = ordered) else it })
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
    return if (area == LayoutArea.HOME) state.copy(homeFolders = next) else state.copy(dockFolders = next)
}
