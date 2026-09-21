package fr.poemedujour.app

import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

private data class Poem(val id: Long, val collection: String, val title: String, val text: String, val page: Int, val pdfPath: String)
private data class PoemBackground(val name: String, val brush: Brush, val content: Color)

class MainActivity : ComponentActivity() {
    private val poems = mutableStateListOf<Poem>()
    private var mode by mutableStateOf("Jour")
    private var index by mutableIntStateOf(0)
    private var showLibrary by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var showFavoritesOnly by mutableStateOf(false)
    private var importing by mutableStateOf(false)
    private var importMessage by mutableStateOf("")
    private var fontChoice by mutableStateOf("Serif")
    private var backgroundChoice by mutableStateOf("Parchemin")
    private var favorites by mutableStateOf(setOf<Long>())

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importFolder(it) }
    }

    private val backgrounds = listOf(
        PoemBackground("Parchemin", Brush.linearGradient(listOf(Color(0xFFF5EBD7), Color(0xFFE6D3B3))), Color(0xFF493B2A)),
        PoemBackground("Aube", Brush.linearGradient(listOf(Color(0xFFF8D8C8), Color(0xFFD9B7D8))), Color(0xFF3E3041)),
        PoemBackground("Forêt", Brush.linearGradient(listOf(Color(0xFFDCE8D6), Color(0xFFAFC8B5))), Color(0xFF26382C)),
        PoemBackground("Ciel", Brush.linearGradient(listOf(Color(0xFFDCECF5), Color(0xFFA9C8DC))), Color(0xFF263B49)),
        PoemBackground("Nuit", Brush.linearGradient(listOf(Color(0xFF273449), Color(0xFF111827))), Color(0xFFF4F1E8)),
        PoemBackground("Minimal", Brush.linearGradient(listOf(Color(0xFFF8F7F4), Color(0xFFE9E6DF))), Color(0xFF302F2B))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        loadPreferences()
        setContent { App() }
        loadPoemsInBackground()
    }

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)

    private fun loadPreferences() {
        val p = prefs()
        fontChoice = p.getString("font", "Serif") ?: "Serif"
        backgroundChoice = p.getString("background", "Parchemin") ?: "Parchemin"
        favorites = p.getStringSet("favorites", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    private fun savePreferences() {
        prefs().edit()
            .putString("font", fontChoice)
            .putString("background", backgroundChoice)
            .putStringSet("favorites", favorites.map { it.toString() }.toSet())
            .apply()
    }

    private fun loadPoemsInBackground() {
        Thread {
            val found = mutableListOf<Poem>()
            val dir = File(filesDir, "pdfs"); dir.mkdirs()
            dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.forEach { found.addAll(parsePdf(it)) }
            runOnUiThread {
                poems.clear()
                poems.addAll(found)
                index = prefs().getInt("index", 0).coerceIn(0, maxOf(0, poems.size - 1))
            }
        }.start()
    }

    private fun parsePdf(file: File): List<Poem> {
        val result = mutableListOf<Poem>()
        try {
            PDDocument.load(file).use { doc ->
                val name = file.nameWithoutExtension
                for (p in 0 until doc.numberOfPages) {
                    val text = try {
                        com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                            startPage = p + 1
                            endPage = p + 1
                        }.getText(doc).trim()
                    } catch (_: Exception) { "" }
                    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isEmpty()) continue
                    val title = lines.firstOrNull { it.length in 2..100 && !it.matches(Regex(".*\\d{1,2}.*")) } ?: "Page §{p + 1}"
                    val body = lines.dropWhile { it != title }.drop(1).joinToString("\n")
                    if (body.length >= 20) result.add(Poem((file.absolutePath + p).hashCode().toLong(), name, title, body, p, file.absolutePath))
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun importFolder(treeUri: Uri) {
        if (importing) return
        importing = true
        importMessage = "Recherche des PDF…"
        Thread {
            try {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
                val pdfs = root?.listFiles()?.filter { it.isFile && it.name?.endsWith(".pdf", true) == true } ?: emptyList()
                val dir = File(filesDir, "pdfs"); dir.mkdirs()
                val all = mutableListOf<Poem>()
                pdfs.forEachIndexed { n, docFile ->
                    runOnUiThread { importMessage = "Import du PDF §{n + 1}/§{pdfs.size}…" }
                    val safeName = (docFile.name ?: "recueil_$n.pdf").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(dir, safeName)
                    contentResolver.openInputStream(docFile.uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                    all.addAll(parsePdf(out))
                }
                runOnUiThread {
                    poems.clear(); poems.addAll(all); index = poems.lastIndex.coerceAtLeast(0); saveIndex()
                    importing = false
                    importMessage = if (all.isEmpty()) "Aucun poème détecté dans ce dossier." else "§{all.size} poèmes importés."
                }
            } catch (_: Exception) {
                runOnUiThread { importing = false; importMessage = "Impossible d’importer ce dossier." }
            }
        }.start()
    }

    @Composable fun App() {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                when {
                    showSettings -> SettingsScreen()
                    showLibrary -> LibraryScreen()
                    else -> HomeScreen()
                }
                if (importing) AlertDialog(onDismissRequest = {}, title = { Text("Importation") }, text = { Text(importMessage) }, confirmButton = {})
            }
        }
    }

    private fun currentFont(): FontFamily = when (fontChoice) {
        "Sans" -> FontFamily.SansSerif
        "Cursive" -> FontFamily.Cursive
        "Monospace" -> FontFamily.Monospace
        else -> FontFamily.Serif
    }

    private fun currentBackground(): PoemBackground = backgrounds.firstOrNull { it.name == backgroundChoice } ?: backgrounds.first()

    @Composable fun HomeScreen() {
        val current = poems.getOrNull(if (mode == "Aléatoire" && poems.isNotEmpty()) abs(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR) * 31 % poems.size) else index)
        val bg = currentBackground()
        val quoteStyle = TextStyle(fontFamily = currentFont(), color = bg.content)

        Box(Modifier.fillMaxSize().background(bg.brush)) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Poème du Jour", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = bg.content)
                        if (poems.isNotEmpty()) Text("§{current?.collection ?: ""}  •  §{current?.page?.plus(1) ?: 0}", color = bg.content.copy(alpha = .72f))
                    }
                    TextButton(onClick = { showSettings = true }) { Text("⚙", color = bg.content, style = MaterialTheme.typography.titleLarge) }
                }

                Spacer(Modifier.height(18.dp))

                if (current == null) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .72f))) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Ton espace de poésie", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text("Ajoute un recueil PDF pour commencer.")
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { folderPicker.launch(null) }) { Text("+ Ajouter un PDF") }
                        }
                    }
                } else {
                    Card(
                        Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .18f))
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(current.title, style = quoteStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { toggleFavorite(current.id) }) {
                                    Text(if (favorites.contains(current.id)) "♥" else "♡", color = bg.content, style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(current.text, style = quoteStyle.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.12f), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(18.dp))
                            Text("— Li Hongzhi", style = quoteStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { index = (index - 1 + poems.size) % poems.size; saveIndex() }) { Text("‹") }
                        Button(onClick = { index = (index + 1) % poems.size; saveIndex() }) { Text("Suivant ›") }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { mode = if (mode == "Jour") "Aléatoire" else "Jour" }) { Text(if (mode == "Jour") "Mode aléatoire" else "Mode ordre", color = bg.content) }
                    TextButton(onClick = { showLibrary = true }) { Text("Bibliothèque", color = bg.content) }
                    TextButton(onClick = { folderPicker.launch(null) }) { Text("+ PDF", color = bg.content) }
                }
                if (importMessage.isNotEmpty() && !importing) Text(importMessage, style = MaterialTheme.typography.bodySmall, color = bg.content.copy(alpha = .75f))
            }
        }
    }

    private fun toggleFavorite(id: Long) {
        favorites = if (favorites.contains(id)) favorites - id else favorites + id
        savePreferences()
    }

    private fun saveIndex() { prefs().edit().putInt("index", index).apply() }

    @Composable fun LibraryScreen() {
        val list = if (showFavoritesOnly) poems.filter { favorites.contains(it.id) } else poems
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLibrary = false }) { Text("‹ Retour") }
                Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(selected = !showFavoritesOnly, onClick = { showFavoritesOnly = false }, label = { Text("Tous") })
                FilterChip(selected = showFavoritesOnly, onClick = { showFavoritesOnly = true }, label = { Text("♥ Favoris (§{favorites.size})") })
            }
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                Text(if (showFavoritesOnly) "Aucun favori pour le moment." else "Aucun poème importé.")
            } else {
                LazyColumn {
                    items(list) { p ->
                        ListItem(
                            headlineContent = { Text(p.title, fontFamily = currentFont()) },
                            supportingContent = { Text(p.collection) },
                            trailingContent = { TextButton(onClick = { toggleFavorite(p.id) }) { Text(if (favorites.contains(p.id)) "♥" else "♡") } }
                        )
                    }
                }
            }
        }
    }

    @Composable fun SettingsScreen() {
        val bg = currentBackground()
        Column(
            Modifier.fillMaxSize().background(bg.brush).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showSettings = false }) { Text("‹ Retour", color = bg.content) }
                Text("Personnalisation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = bg.content)
            }
            Spacer(Modifier.height(18.dp))
            Text("Police", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = bg.content)
            listOf("Serif", "Sans", "Cursive", "Monospace").forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = fontChoice == f, onClick = { fontChoice = f; savePreferences() })
                    Text(
                        when (f) { "Serif" -> "Classique élégante"; "Sans" -> "Moderne"; "Cursive" -> "Manuscrite"; else -> "Machine à écrire" },
                        fontFamily = when (f) { "Serif" -> FontFamily.Serif; "Sans" -> FontFamily.SansSerif; "Cursive" -> FontFamily.Cursive; else -> FontFamily.Monospace },
                        color = bg.content
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Fond", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = bg.content)
            backgrounds.forEach { b ->
                Row(Modifier.fillMaxWidth().background(b.brush, RoundedCornerShape(16.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = backgroundChoice == b.name, onClick = { backgroundChoice = b.name; savePreferences() })
                    Text(b.name, color = b.content, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}
