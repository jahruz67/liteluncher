package dev.liteluncher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser

/** Converts a portable JSON backup or a Lynx XML archive before any saved state is changed. */
internal object LauncherBackup {
    private const val MAX_BYTES = 1_048_576
    private val xmlRoots = mapOf(
        "settings.xml" to "settings", "desktop.xml" to "desktops", "dock.xml" to "dock",
        "folders.xml" to "folders", "favorite_applications.xml" to "favorite_applications",
        "hidden_applications.xml" to "hidden_apps", "custom_applications.xml" to "custom_apps",
        "shortcuts.xml" to "shortcuts", "searchHistory.xml" to "search_history"
    )

    fun read(stream: InputStream): String {
        val bytes = readLimited(stream)
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
            return bytes.toString(Charsets.UTF_8)
        }
        val (documents, images) = readArchive(bytes)
        require(listOf("settings.xml", "desktop.xml", "dock.xml").all { it in documents }) {
            "This ZIP is not a supported Lynx launcher backup"
        }
        return Json { encodeDefaults = true }.encodeToString(convert(documents, images))
    }

    /** Embed picked icon images so exports do not depend on another device's URI grants. */
    fun portableIcons(context: Context, json: String): String {
        val format = Json { encodeDefaults = true }
        val state = format.decodeFromString(LauncherState.serializer(), json)
        val overrides = state.overrides.mapValues { (_, override) ->
            if (override.iconUri.isBlank()) override else override.copy(iconUri = "", backupIcon = readIcon(context, override.iconUri))
        }
        val folders = state.folders.map { folder ->
            if (folder.iconUri.isBlank()) folder else folder.copy(iconUri = "", backupIcon = readIcon(context, folder.iconUri))
        }
        return format.encodeToString(state.copy(overrides = overrides, folders = folders)).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup with icon images exceeds the 1 MiB limit" }
        }
    }

    private fun readIcon(context: Context, value: String): BackupIcon {
        val uri = Uri.parse(value)
        require(uri.scheme in setOf("content", "android.resource")) { "Unsupported custom icon URI" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "A custom icon could not be read" }
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("A custom icon could not be read")
        try {
            val output = ByteArrayOutputStream()
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "A custom icon could not be saved" }
            require(output.size() <= 196_608) { "A custom icon is too large to back up" }
            return BackupIcon(bitmap = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        } finally { bitmap.recycle() }
    }

    private fun readLimited(stream: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(count <= limit - output.size()) { "Backup exceeds the 1 MiB limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readArchive(bytes: ByteArray): Pair<Map<String, Node>, Map<String, String>> {
        val documents = linkedMapOf<String, Node>()
        val images = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 128) { "Backup contains too many files" }
                val content = readLimited(zip, MAX_BYTES - total)
                total += content.size
                if (entry.name.matches(Regex("[A-Za-z0-9_-]+\\.png"))) {
                    require(entry.name !in images && content.size <= 196_608) { "Invalid or oversized backup icon" }
                    images[entry.name] = Base64.encodeToString(content, Base64.NO_WRAP)
                }
                val rootName = xmlRoots[entry.name] ?: continue
                require(entry.name !in documents) { "Backup contains duplicate files" }
                val root = parseXml(content)
                require(root.name == rootName) { "Invalid Lynx backup file: ${entry.name}" }
                documents[entry.name] = root
            }
        }
        return documents to images
    }

    private data class Node(val name: String, val attributes: Map<String, String>, val children: MutableList<Node> = mutableListOf()) {
        operator fun get(attribute: String): String = attributes[attribute].orEmpty()
        fun children(name: String): List<Node> = children.filter { it.name == name }
    }

    private fun parseXml(bytes: ByteArray): Node {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val stack = mutableListOf<Node>()
        var root: Node? = null
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL -> error("XML document declarations are not supported in backups")
                XmlPullParser.START_TAG -> {
                    require(stack.size < 32) { "Backup is nested too deeply" }
                    val node = Node(parser.name, (0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    })
                    if (stack.isEmpty()) {
                        require(root == null) { "Malformed backup XML" }
                        root = node
                    } else stack.last().children.add(node)
                    stack.add(node)
                }
                XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
            }
        }
        require(stack.isEmpty()) { "Malformed backup XML" }
        return requireNotNull(root) { "Backup XML is empty" }
    }

    private fun appKey(node: Node): String? = appKey(node["name"], node["profile"])

    private fun appKey(raw: String, profile: String = ""): String? {
        if (raw.isBlank()) return null
        val name = raw.removePrefix("A:")
        val component = ComponentName.unflattenFromString(name.substringBeforeLast('|', name))
            ?: error("Invalid application in Lynx backup")
        val serial = (if ('|' in name) name.substringAfterLast('|') else profile.ifEmpty { "0" }).toLongOrNull()
        require(serial != null && serial >= 0) { "Invalid application profile in Lynx backup" }
        return "${component.flattenToString()}|$serial"
    }

    private fun List<Node>.apps(): List<String> = filter { it.name == "application" }.mapNotNull(::appKey).distinct()

    private fun Node.ordered(): List<Node> = children.sortedWith(compareBy<Node> {
        it["position"].split(':').getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }.thenBy { it["position"].substringBefore(':').toIntOrNull() ?: Int.MAX_VALUE })

    private fun Node.icon(images: Map<String, String>): BackupIcon? {
        val bitmap = images["${this["bitmap"]}.png"].orEmpty()
        return BackupIcon(bitmap, this["icon_pack"], this["icon_name"])
            .takeIf { it.bitmap.isNotEmpty() || (it.packageName.isNotEmpty() && it.resourceName.isNotEmpty()) }
    }

    private fun Node.entryKey(shortcuts: Map<String, PinnedShortcut>): String? = when (name) {
        "application" -> appKey(this)
        "folder" -> "folder:lynx-${this["id"]}"
        "shortcut" -> shortcuts[this["id"]]?.layoutKey ?: error("Lynx backup is missing a shortcut")
        else -> null
    }

    private fun Node.position(key: String, container: String, columns: Int, rows: Int): ItemPosition {
        val coordinates = this["position"].split(':').map { it.toIntOrNull() }
        require(coordinates.size == 4 && coordinates.all { it != null }) { "Invalid item position in Lynx backup" }
        val (left, top, right, bottom) = coordinates.map { requireNotNull(it) }
        require(left >= 0 && top >= 0 && right > left && bottom > top && right <= columns && bottom <= rows) {
            "A Lynx item is outside its saved grid"
        }
        return ItemPosition(key, container, left, top, right - left, bottom - top)
    }

    private fun convert(documents: Map<String, Node>, images: Map<String, String>): LauncherState {
        val boards = documents.getValue("desktop.xml").children("board")
        val home = boards.singleOrNull { it["isHome"] == "true" }
        requireNotNull(home) { "Lynx backup must contain one home board" }
        val homeItems = home.ordered()
        val dockItems = documents.getValue("dock.xml").children
        val folderNodes = documents["folders.xml"]?.children("folder").orEmpty()
        val desktopBoards = boards.filter { it !== home }
        val prefs = preferences(documents.getValue("settings.xml"), desktopBoards.size.coerceAtLeast(1))
        val containers = linkedMapOf("home" to homeItems, "dock" to dockItems)
        desktopBoards.forEachIndexed { index, board -> containers["desktop:$index"] = board.ordered() }
        folderNodes.forEach { containers["folder:lynx-${it["id"]}"] = it.children }
        val shortcutsById = documents["shortcuts.xml"]?.children("deep_shortcut").orEmpty().associate { node ->
            val serial = node["user"].ifEmpty { "0" }.toLongOrNull()
            require(serial != null && serial >= 0) { "Invalid shortcut profile in Lynx backup" }
            val container = containers.entries.firstOrNull { (_, children) ->
                children.any { it.name == "shortcut" && it["id"] == node["db_id"] }
            }?.key ?: "library"
            node["db_id"] to PinnedShortcut(node["id"], node["package"], node["custom_label"].ifBlank { node["label"] },
                serial, container, node.icon(images))
        }
        val folders = folderNodes.map { folder ->
            require(folder["id"].isNotBlank()) { "Lynx folder is missing its ID" }
            FolderItem("lynx-${folder["id"]}", folder["name"], folder.children.apps(),
                entryOrder = folder.children.mapNotNull { it.entryKey(shortcutsById) }, backupIcon = folder.icon(images))
        }
        require(folders.distinctBy { it.id }.size == folders.size) { "Duplicate Lynx folder ID" }
        val favorites = orderedFavorites(documents["favorite_applications.xml"]?.children("application").orEmpty())
        val launches = documents["favorite_applications.xml"]?.children.orEmpty().mapNotNull { node ->
            val key = appKey(node) ?: return@mapNotNull null
            key to (node["score"].toIntOrNull() ?: 0).coerceAtLeast(0)
        }.toMap()
        val positions = containers.filterKeys { !it.startsWith("folder:") }.flatMap { (container, children) ->
            children.mapIndexedNotNull { index, node ->
                val key = node.entryKey(shortcutsById) ?: return@mapIndexedNotNull null
                if (container == "dock") ItemPosition(key, container, 0, index)
                else node.position(key, container, if (container == "home") prefs.home.columns else prefs.desktop.columns,
                    if (container == "home") prefs.home.rows else prefs.desktop.rows)
            }
        }
        val pendingWidgets = desktopBoards.flatMapIndexed { page, board ->
            board.children("widget").map { node ->
                val key = "lynx-widget:$page:${node["id"]}"
                val position = node.position(key, "desktop:$page", prefs.desktop.columns, prefs.desktop.rows)
                require(ComponentName.unflattenFromString(node["provider"]) != null) { "Invalid Lynx widget provider" }
                PortableWidget(key, node["provider"], position.column, position.row, position.columns, position.rows, page)
            }
        }
        return LauncherState(
            prefs = prefs, initialized = true,
            homeApps = homeItems.apps(), dockApps = dockItems.apps(), favoriteApps = favorites,
            homeFolders = homeItems.filter { it.name == "folder" }.map { "lynx-${it["id"]}" },
            dockFolders = dockItems.filter { it.name == "folder" }.map { "lynx-${it["id"]}" },
            folders = folders, shortcuts = shortcutsById.values.toList(), launches = launches, positions = positions,
            pendingWidgets = pendingWidgets,
            lynxSettings = documents.getValue("settings.xml").children.associate { it.name to it["value"] },
            hiddenApps = documents["hidden_applications.xml"]?.children.orEmpty().apps().toSet(),
            desktopApps = desktopBoards.mapIndexed { index, board ->
                index to board.ordered().filter { it.name != "shortcut" }.mapNotNull { it.entryKey(shortcutsById) }.distinct()
            }.toMap(),
            overrides = documents["custom_applications.xml"]?.children.orEmpty().mapNotNull { node ->
                val key = appKey(node) ?: return@mapNotNull null
                key to AppOverride(label = node["custom_label"].ifBlank { node["label"] }, backupIcon = node.icon(images))
            }.toMap(),
            history = documents["searchHistory.xml"]?.children("entry").orEmpty()
                .map { it["query"] }.filter { it.isNotBlank() }.distinct()
        )
    }

    private fun orderedFavorites(nodes: List<Node>): List<String> {
        val byName = nodes.associateBy { it["name"] }
        val successors = nodes.map { it["next"] }.toSet()
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (start in nodes.filter { it["name"] !in successors } + nodes) {
            var current: Node? = start
            while (current != null && visited.add(current["name"])) {
                appKey(current)?.let(result::add)
                current = byName[current["next"]]
            }
        }
        return result.distinct()
    }

    private fun preferences(settings: Node, pages: Int): LauncherPrefs {
        val values = settings.children.associate { it.name to it["value"] }
        fun flag(name: String, default: Boolean): Boolean = values[name]?.toBooleanStrictOrNull() ?: default
        fun number(name: String, default: Int): Int = values[name]?.toIntOrNull() ?: default
        val defaults = LauncherPrefs()
        val desktopColumns = number("desktop_columns_portrait", defaults.desktop.columns)
        val desktopRows = number("desktop_rows_portrait", defaults.desktop.rows)
        return defaults.copy(
            home = defaults.home.copy(columns = number("home_columns", defaults.home.columns), rows = number("home_rows", defaults.home.rows)),
            clock = defaults.clock.copy(clockApp = appKey(values["home_clock_app"].orEmpty()) ?: defaults.clock.clockApp),
            dock = defaults.dock.copy(allApps = flag("dock_show_all_apps", defaults.dock.allApps)),
            desktop = defaults.desktop.copy(columns = desktopColumns, rows = desktopRows, pageCount = pages,
                grid = defaults.desktop.grid.copy(columns = desktopColumns, rows = desktopRows)),
            search = defaults.search.copy(
                apps = flag("search_in_apps", defaults.search.apps), hiddenApps = flag("search_hidden_apps", defaults.search.hiddenApps),
                contacts = flag("search_in_contacts", defaults.search.contacts), shortcuts = flag("search_in_shortcuts", defaults.search.shortcuts),
                settings = flag("search_in_settings", defaults.search.settings), math = flag("search_in_math", defaults.search.math),
                web = flag("search_in_web", defaults.search.web), playStore = flag("search_google_play", defaults.search.playStore),
                website = flag("search_website", defaults.search.website)
            ),
            favorites = defaults.favorites.copy(showContacts = flag("favorites_show_contact", defaults.favorites.showContacts)),
            folders = defaults.folders.copy(labels = flag("folder_show_text", defaults.folders.labels)),
            badges = defaults.badges.copy(enabled = flag("badge_show", defaults.badges.enabled)),
            navigation = defaults.navigation.copy(infinite = values["screen_infinite_scrolling"]?.let { it != "0" } ?: defaults.navigation.infinite)
        )
    }
}
