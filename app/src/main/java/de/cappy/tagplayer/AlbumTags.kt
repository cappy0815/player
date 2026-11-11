package de.cappy.tagplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// =====================================================================
//  Logik: Album-Ordner finden, Vorschläge ermitteln, in alle Dateien schreiben
// =====================================================================

/** Ein Album-Ordner mit seinen Titeln (CD1/CD2-Unterordner gehören dazu) */
class AlbumFolder(val dir: String, val songs: List<Song>) {
    val name: String get() = File(dir).name
    /** CD-Nummer je Datei, wenn das Album in CD-Unterordnern liegt ("CD1", "Disc 2" …) */
    fun discOf(s: Song): Int? {
        val parent = File(s.path).parentFile ?: return null
        if (parent.path == dir) return null
        return Regex("(\\d+)").find(parent.name)?.groupValues?.get(1)?.toIntOrNull()
    }
}

/** Was in alle Dateien eines Ordners geschrieben wird. Leeres Feld = nicht ändern. */
data class AlbumFields(
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val genre: String = "",
    val setTrackTotal: Boolean = true,
    val renumber: Boolean = false,
    val setDisc: Boolean = true,
    val cover: ByteArray? = null          // null = Cover nicht ändern
)

object AlbumTagger {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val PREFERRED = listOf("cover", "folder", "front", "albumart", "album", "frontcover")

    /** Alle Album-Ordner unterhalb von [dir] (inkl. [dir] selbst) */
    fun albumFolders(dir: String): List<AlbumFolder> =
        Library.songs.filter { it.path.startsWith("$dir/") }
            .groupBy { AlbumHints.albumDir(it)?.path ?: File(it.path).parent ?: "" }
            .filterKeys { it.isNotBlank() }
            .map { (d, l) -> AlbumFolder(d, l.sortedBy { it.path.lowercase() }) }
            .sortedBy { it.dir.lowercase() }

    /** Bilddatei im Ordner (oder in einem CD-Unterordner): bevorzugt cover/folder/front, sonst die größte */
    fun folderImage(dir: String): File? {
        val files = (File(dir).listFiles().orEmpty().toList() +
            File(dir).listFiles().orEmpty().filter { it.isDirectory }.flatMap { it.listFiles().orEmpty().toList() })
            .filter { it.isFile && it.extension.lowercase() in IMG && it.length() > 2000 }
        if (files.isEmpty()) return null
        return files.minWithOrNull(compareBy<File> { f ->
            val n = f.nameWithoutExtension.lowercase()
            PREFERRED.indexOfFirst { n == it || n.startsWith(it) }.let { if (it < 0) 99 else it }
        }.thenByDescending { it.length() })
    }

    /** Bild laden, auf max. 800 px verkleinern, als JPEG (das lesen alle Player) */
    fun loadImage(f: File): ByteArray? = try { toJpeg(f.readBytes()) } catch (_: Exception) { null }

    fun toJpeg(data: ByteArray, max: Int = 800): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= max && o.outHeight / (sample * 2) >= max) sample *= 2
        var b = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = max.toFloat() / maxOf(b.width, b.height)
        if (scale < 1f) b = Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun <T> majority(l: List<T>): T? = l.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Vorschlag: Ordnername für Album/Interpret, sonst die häufigsten Tags */
    data class Proposal(val fields: AlbumFields, val tags: Map<String, TagData>, val image: File?,
                        val majorityCoverPath: String?, val coversConsistent: Boolean, val coverCount: Int)

    fun propose(f: AlbumFolder): Proposal {
        val tags = f.songs.associate { s -> s.path to (try { Tags.read(s.path) } catch (_: Exception) { TagData() }) }
        val t = tags.values.toList()
        val hint = AlbumHints.forSong(f.songs.first(), t.firstOrNull())
        val album = if (hint?.fromFolder == true && File(f.dir).name.isNotBlank()) hint.album
            else majority(t.map { it.album.trim() }.filter { it.isNotEmpty() }) ?: ""
        val artists = t.map { it.artist.trim() }.filter { it.isNotEmpty() }.distinct()
        val albumArtist = majority(t.map { it.albumArtist.trim() }.filter { it.isNotEmpty() })
            ?: hint?.artist?.ifBlank { null }
            ?: (if (artists.size == 1) artists[0] else if (artists.size > 1) "Diverse Interpreten" else "")
        val md5 = tags.mapValues { Diag.md5(it.value.cover) }
        val withCover = md5.values.filter { it.isNotEmpty() }
        val top = withCover.groupingBy { it }.eachCount().maxByOrNull { it.value }
        return Proposal(
            AlbumFields(album = album, albumArtist = albumArtist,
                year = majority(t.map { it.year.trim().take(4) }.filter { it.length == 4 }) ?: "",
                genre = majority(t.map { it.genre.trim() }.filter { it.isNotEmpty() }) ?: ""),
            tags, folderImage(f.dir),
            top?.let { tp -> md5.entries.first { it.value == tp.key }.key },
            withCover.size == md5.size && withCover.toSet().size == 1, withCover.toSet().size
        )
    }

    /** Titelnummer aus dem Dateinamen ("03 - Titel.mp3"), sonst Position */
    private fun numberFromName(s: Song, pos: Int): String =
        Regex("^\\s*(\\d{1,3})").find(s.fileName)?.groupValues?.get(1)?.toIntOrNull()?.toString() ?: (pos + 1).toString()

    /** In alle Dateien schreiben. Rückgabe: geänderte Pfade */
    suspend fun apply(ctx: android.content.Context, f: AlbumFolder, a: AlbumFields, progress: (Int) -> Unit = {}): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val changed = ArrayList<String>(); val errors = ArrayList<String>()
        val byDisc = f.songs.groupBy { f.discOf(it) }
        f.songs.forEachIndexed { i, s ->
            try {
                val old = Tags.read(s.path)
                val disc = f.discOf(s)
                val inDisc = byDisc[disc].orEmpty()
                var neu = old.copy(
                    album = a.album.ifBlank { old.album },
                    albumArtist = a.albumArtist.ifBlank { old.albumArtist },
                    year = a.year.ifBlank { old.year },
                    genre = a.genre.ifBlank { old.genre },
                    trackTotal = if (a.setTrackTotal) inDisc.size.toString() else old.trackTotal,
                    disc = if (a.setDisc && disc != null) disc.toString() else old.disc,
                    track = if (a.renumber) numberFromName(s, inDisc.indexOf(s)) else old.track
                )
                if (a.cover != null) neu = neu.copy(cover = a.cover, coverMime = "image/jpeg")
                val coverChange = a.cover != null && Diag.md5(old.cover) != Diag.md5(a.cover)
                if (neu.diff(old).isNotEmpty() || coverChange) {
                    Tags.write(s.path, neu, writeCover = coverChange)
                    changed += s.path
                }
            } catch (e: Exception) { errors += "${s.fileName}: ${e.message ?: e.javaClass.simpleName}" }
            withContext(Dispatchers.Main) { progress(i + 1) }
        }
        Diag.log(ctx,
            "Album-Tags Ordner ${f.dir}: Album „${a.album}“, Interpret „${a.albumArtist}“, Cover ${if (a.cover != null) "ja" else "nein"} · ${changed.size} geändert, ${errors.size} Fehler")
        changed to errors
    }
}

// =====================================================================
//  Oberfläche
// =====================================================================

/** Einstieg: ein Album-Ordner → Formular, mehrere → Liste mit „Alle automatisch setzen“ */
@Composable
fun AlbumTagsScreen(dir: String, preset: TagData? = null) {
    val folders = remember(dir, Library.version) { AlbumTagger.albumFolders(dir) }
    when {
        folders.isEmpty() -> Column { TopBar("Album-Tags", onBack = { Navi.back() }); Text("Keine Titel in diesem Ordner", color = TextDim, modifier = Modifier.padding(16.dp)) }
        folders.size == 1 -> AlbumFolderForm(folders[0], preset)
        else -> AlbumFolderBatch(dir, folders)
    }
}

@Composable
private fun AlbumFolderForm(f: AlbumFolder, preset: TagData?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = Prefs.get(ctx)
    var prop by remember { mutableStateOf<AlbumTagger.Proposal?>(null) }
    var a by remember { mutableStateOf(AlbumFields()) }
    // Cover-Quelle: 0 nicht ändern, 1 Bilddatei, 2 häufigstes Cover, 3 online, 4 eigenes Bild
    var coverSrc by remember { mutableIntStateOf(0) }
    var imgFile by remember { mutableStateOf<ByteArray?>(null) }
    var imgMajority by remember { mutableStateOf<ByteArray?>(null) }
    var imgOnline by remember { mutableStateOf<ByteArray?>(null) }
    var imgOwn by remember { mutableStateOf<ByteArray?>(null) }
    var cands by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    val allFiles = remember(AppState.resumeTick.intValue) { hasAllFiles() }

    LaunchedEffect(f.dir) {
        val p = withContext(Dispatchers.IO) { AlbumTagger.propose(f) }
        prop = p
        a = if (preset != null) p.fields.copy(album = preset.album.ifBlank { p.fields.album },
            albumArtist = preset.albumArtist.ifBlank { p.fields.albumArtist }, year = preset.year.ifBlank { p.fields.year },
            genre = preset.genre.ifBlank { p.fields.genre }) else p.fields
        imgFile = p.image?.let { withContext(Dispatchers.IO) { AlbumTagger.loadImage(it) } }
        imgMajority = p.majorityCoverPath?.let { path -> withContext(Dispatchers.IO) { try { Tags.read(path).cover } catch (_: Exception) { null } } }
        if (preset?.cover != null) imgOwn = preset.cover
        coverSrc = when {
            preset?.cover != null -> 4
            imgFile != null -> 1                        // Bild im Ordner = sehr wahrscheinlich das Cover
            !p.coversConsistent && imgMajority != null -> 2
            else -> 0
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            imgOwn = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { AlbumTagger.toJpeg(it) } }
            if (imgOwn != null) coverSrc = 4
        }
    }
    val cover = when (coverSrc) { 1 -> imgFile; 2 -> imgMajority; 3 -> imgOnline; 4 -> imgOwn; else -> null }

    Column(Modifier.fillMaxSize()) {
        TopBar("Album-Tags für Ordner", onBack = { Navi.back() })
        val p = prop
        if (p == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }; return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding()) {
            Text("${Folders.shortName(f.dir)} · ${f.songs.size} Titel" +
                if (f.songs.any { f.discOf(it) != null }) " · ${f.songs.mapNotNull { f.discOf(it) }.distinct().size} CDs" else "",
                color = TextDim, fontSize = 12.sp)
            if (!allFiles) {
                Spacer(Modifier.height(8.dp))
                Card { Text("Zum Speichern braucht die App „Zugriff auf alle Dateien“.", color = AccentSoft, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp)); Pill("Berechtigung erteilen", filled = true) { openAllFilesSettings(ctx) } }
            }

            SectionTitle("Online abgleichen (optional)")
            Pill(if (searching) "Suche …" else "Album suchen", enabled = !searching && a.album.isNotBlank()) {
                searching = true
                scope.launch {
                    cands = try { AlbumSearch.search(AlbumHint(a.albumArtist, a.album, true), f.songs.size, prefs.threshold, thorough = true) } catch (_: Exception) { emptyList() }
                    if (cands.isEmpty()) Toast.makeText(ctx, "Nichts gefunden", Toast.LENGTH_SHORT).show()
                    searching = false
                }
            }
            cands.take(6).forEach { c ->
                Spacer(Modifier.height(6.dp))
                CandidateRow(c, prefs.threshold) {
                    scope.launch {
                        a = a.copy(album = c.album.ifBlank { a.album }, albumArtist = c.albumArtist.ifBlank { a.albumArtist },
                            year = c.year.ifBlank { a.year }, genre = c.genre.ifBlank { a.genre })
                        imgOnline = Covers.forCandidate(c)?.let { AlbumTagger.toJpeg(it) }
                        if (imgOnline != null && coverSrc != 1) coverSrc = 3
                        cands = emptyList()
                        Toast.makeText(ctx, "Übernommen – prüfen und speichern", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            SectionTitle("Für alle ${f.songs.size} Titel")
            Field("Album", a.album) { a = a.copy(album = it) }
            Field("Album-Interpret", a.albumArtist) { a = a.copy(albumArtist = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Jahr", a.year, Modifier.weight(1f), number = true) { a = a.copy(year = it) }
                Field("Genre", a.genre, Modifier.weight(1.6f)) { a = a.copy(genre = it) }
            }
            Text("Leere Felder bleiben in den Dateien unverändert. Titel und Interpret der einzelnen Lieder werden nicht angefasst.",
                color = TextDim, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            SwitchRow("Titelanzahl setzen", "„von“ = Anzahl Titel im Ordner bzw. je CD", a.setTrackTotal) { a = a.copy(setTrackTotal = it) }
            if (f.songs.any { f.discOf(it) != null })
                SwitchRow("CD-Nummer aus Unterordner", "CD1, Disc 2 … → Feld „CD“", a.setDisc) { a = a.copy(setDisc = it) }
            SwitchRow("Titelnummern neu vergeben", "Aus der Zahl am Anfang des Dateinamens, sonst nach Dateiname sortiert", a.renumber) { a = a.copy(renumber = it) }

            SectionTitle("Cover")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(null, 96.dp, 12.dp, bytes = cover ?: imgMajority)
                Spacer(Modifier.width(14.dp))
                Text(when {
                    p.coverCount == 0 -> "Bisher hat keine Datei ein Cover."
                    p.coversConsistent -> "Alle Dateien haben dasselbe Cover."
                    else -> "Im Ordner gibt es ${p.coverCount} verschiedene Cover" + (if (p.tags.values.any { it.cover == null }) ", einige Dateien haben keins." else ".")
                }, color = if (p.coversConsistent) Good else AccentSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            CoverOption("Nicht ändern", coverSrc == 0, true) { coverSrc = 0 }
            CoverOption("Bild im Ordner" + (p.image?.let { " (${it.name})" } ?: " – keins gefunden"), coverSrc == 1, imgFile != null) { coverSrc = 1 }
            CoverOption("Häufigstes Cover im Ordner übernehmen", coverSrc == 2, imgMajority != null && !p.coversConsistent) { coverSrc = 2 }
            CoverOption("Online-Treffer" + if (imgOnline == null) " – erst oben ein Album wählen" else "", coverSrc == 3, imgOnline != null) { coverSrc = 3 }
            CoverOption("Eigenes Bild …", coverSrc == 4, true) { if (imgOwn == null) pick.launch("image/*") else coverSrc = 4 }

            Spacer(Modifier.height(16.dp))
            Pill(if (saving) "Schreibe … $done/${f.songs.size}" else "In alle ${f.songs.size} Dateien schreiben", filled = true,
                enabled = !saving && allFiles, modifier = Modifier.fillMaxWidth()) {
                saving = true
                AppScope.launch {
                    val (changed, errors) = AlbumTagger.apply(ctx, f, a.copy(cover = cover)) { done = it }
                    if (changed.isNotEmpty()) { Library.rescan(ctx, changed); Library.load(ctx) }
                    saving = false
                    Toast.makeText(ctx, "${changed.size} Dateien geändert" + if (errors.isNotEmpty()) ", ${errors.size} Fehler: ${errors.first()}" else "",
                        Toast.LENGTH_LONG).show()
                    if (errors.isEmpty() && Navi.top is Nav.AlbumTags) Navi.back()
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun CoverOption(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(on, onClick, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Accent))
        Text(label, color = if (enabled) Color.White else TextDim.copy(alpha = .6f), fontSize = 14.sp)
    }
}

/** Mehrere Album-Ordner: Vorschau je Ordner und „Alle automatisch setzen“ */
@Composable
private fun AlbumFolderBatch(dir: String, folders: List<AlbumFolder>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val props = remember { mutableStateMapOf<String, AlbumTagger.Proposal>() }
    var setAlbum by remember { mutableStateOf(true) }
    var setArtist by remember { mutableStateOf(true) }
    var setTotal by remember { mutableStateOf(true) }
    var coverImg by remember { mutableStateOf(true) }
    var coverUnify by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val result = remember { mutableStateMapOf<String, String>() }
    val allFiles = remember(AppState.resumeTick.intValue) { hasAllFiles() }

    LaunchedEffect(dir) {
        for (f in folders) { val p = withContext(Dispatchers.IO) { AlbumTagger.propose(f) }; props[f.dir] = p }
    }
    val total = folders.sumOf { it.songs.size }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Surface2,
        title = { Text("${folders.size} Ordner bearbeiten?", color = Color.White) },
        text = { Text("Schreibt in bis zu $total Dateien. Jeder Ordner bekommt sein eigenes Album (aus dem Ordnernamen) und – je nach Auswahl – sein Cover.", color = TextDim) },
        confirmButton = { TextButton(onClick = {
            confirm = false; running = true
            AppScope.launch {
                val all = ArrayList<String>()
                for (f in folders) {
                    val p = props[f.dir] ?: withContext(Dispatchers.IO) { AlbumTagger.propose(f) }
                    result[f.dir] = "läuft …"
                    val cover = when {
                        coverImg && p.image != null -> withContext(Dispatchers.IO) { AlbumTagger.loadImage(p.image) }
                        coverUnify && !p.coversConsistent && p.majorityCoverPath != null -> withContext(Dispatchers.IO) { try { Tags.read(p.majorityCoverPath).cover } catch (_: Exception) { null } }
                        else -> null
                    }
                    val fields = AlbumFields(album = if (setAlbum) p.fields.album else "", albumArtist = if (setArtist) p.fields.albumArtist else "",
                        setTrackTotal = setTotal, cover = cover)
                    val (changed, errors) = AlbumTagger.apply(ctx, f, fields)
                    all += changed
                    result[f.dir] = "${changed.size} geändert" + (if (cover != null) " · Cover" else "") + if (errors.isNotEmpty()) " · ${errors.size} Fehler" else ""
                }
                if (all.isNotEmpty()) { Library.rescan(ctx, all); Library.load(ctx) }
                running = false
                Toast.makeText(ctx, "${all.size} Dateien geändert", Toast.LENGTH_LONG).show()
            }
        }) { Text("Schreiben", color = Accent) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen", color = TextDim) } }
    )

    Column(Modifier.fillMaxSize()) {
        TopBar("Album-Tags für ${folders.size} Ordner", onBack = { Navi.back() })
        LazyColumn(Modifier.fillMaxSize(), state = rememberListState("albumtags:$dir")) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("${Folders.shortName(dir)} · $total Titel in ${folders.size} Album-Ordnern", color = TextDim, fontSize = 12.sp)
                    if (!allFiles) { Spacer(Modifier.height(8.dp)); Pill("„Zugriff auf alle Dateien“ erteilen", filled = true) { openAllFilesSettings(ctx) } }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("Album = Ordnername", "Jeder Ordner bekommt sein eigenes Album", setAlbum) { setAlbum = it }
                    SwitchRow("Album-Interpret einheitlich", "Häufigster Album-Interpret bzw. Interpret im Ordner", setArtist) { setArtist = it }
                    SwitchRow("Titelanzahl setzen", null, setTotal) { setTotal = it }
                    SwitchRow("Cover aus Bilddatei im Ordner", "cover.jpg, folder.jpg … – sonst die größte Bilddatei", coverImg) { coverImg = it }
                    SwitchRow("Uneinheitliche Cover angleichen", "Ohne Bilddatei: das häufigste Cover im Ordner für alle", coverUnify) { coverUnify = it }
                    Spacer(Modifier.height(6.dp))
                    Pill(if (running) "Schreibe …" else "Alle automatisch setzen", filled = true, enabled = !running && allFiles && props.size == folders.size,
                        modifier = Modifier.fillMaxWidth()) { confirm = true }
                    Spacer(Modifier.height(4.dp))
                    Text("Einzelnen Ordner antippen, um die Werte von Hand festzulegen oder online abzugleichen.", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(folders, key = { it.dir }) { f ->
                val p = props[f.dir]
                Row(Modifier.fillMaxWidth().clickable { Navi.go(Nav.AlbumTags(f.dir)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Cover(f.songs.first(), 48.dp, 8.dp, px = 150)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.dir.removePrefix(dir).trimStart('/').ifBlank { f.name }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (p == null) "lese …" else "„${p.fields.album}“ · ${p.fields.albumArtist.ifBlank { "–" }} · ${f.songs.size} Titel",
                            color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p != null) Text(buildList {
                            add(if (p.image != null) "Bild: ${p.image.name}" else "kein Bild")
                            add(when { p.coverCount == 0 -> "ohne Cover"; p.coversConsistent -> "Cover einheitlich"; else -> "${p.coverCount} Cover" })
                            if (p.tags.values.map { it.album.trim() }.distinct().size > 1) add("Album uneinheitlich")
                        }.joinToString(" · "), color = if (p.coversConsistent && p.tags.values.map { it.album.trim() }.distinct().size == 1) Good else AccentSoft, fontSize = 11.sp)
                        result[f.dir]?.let { Text(it, color = Accent, fontSize = 11.sp) }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
