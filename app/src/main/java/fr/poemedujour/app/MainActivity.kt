package fr.poemedujour.app

import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

private data class Poem(val id: Long, val collection: String, val title: String, val text: String, val page: Int, val pdfPath: String)

class MainActivity : ComponentActivity() {
    private val poems = mutableStateListOf<Poem>()
    private var mode by mutableStateOf("Jour")
    private var index by mutableIntStateOf(0)
    private var showLibrary by mutableStateOf(false)
    private var importing by mutableStateOf(false)
    private var importMessage by mutableStateOf("")

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { App() }
        loadPoemsInBackground()
    }

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)

    private fun loadPoemsInBackground() {
        Thread {
            val found = mutableListOf<Poem>()
            val dir = File(filesDir, "pdfs"); dir.mkdirs()
            dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.forEach { found.addAll(parsePdf(it)) }
            runOnUiThread { poems.clear(); poems.addAll(found); index = prefs().getInt("index", 0).coerceIn(0, maxOf(0, poems.size - 1)) }
        }.start()
    }

    private fun parsePdf(file: File): List<Poem> {
        val result = mutableListOf<Poem>()
        try {
            PDDocument.load(file).use { doc ->
                val name = file.nameWithoutExtension
                for (p in 0 until doc.numberOfPages) {
                    val text = try { com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = p + 1; endPage = p + 1 }.getText(doc).trim() } catch (_: Exception) { "" }
                    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isEmpty()) continue
                    val title = lines.firstOrNull { it.length in 2..100 && !it.matches(Regex(".*\\d{1,2}.*")) } ?: "Page ${p + 1}"
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
                    runOnUiThread { importMessage = "Import du PDF ${n + 1}/${pdfs.size}…" }
                    val safeName = (docFile.name ?: "recueil_$n.pdf").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(dir, safeName)
                    contentResolver.openInputStream(docFile.uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                    all.addAll(parsePdf(out))
                }
                runOnUiThread {
                    poems.clear(); poems.addAll(all); index = poems.lastIndex.coerceAtLeast(0); saveIndex(); importing = false
                    importMessage = if (all.isEmpty()) "Aucun poème détecté dans ce dossier." else "${all.size} poèmes importés."
                }
            } catch (_: Exception) {
                runOnUiThread { importing = false; importMessage = "Impossible d’importer ce dossier." }
            }
        }.start()
    }

    @Composable fun App() {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                if (showLibrary) LibraryScreen() else HomeScreen()
                if (importing) AlertDialog(onDismissRequest = {}, title = { Text("Importation") }, text = { Text(importMessage) }, confirmButton = {})
            }
        }
    }

    @Composable fun HomeScreen() {
        val current = poems.getOrNull(if (mode == "Aléatoire" && poems.isNotEmpty()) abs(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR) * 31 % poems.size) else index)
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Text("Poème du Jour", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp)); Text(if (poems.isEmpty()) "Ajoute ton premier recueil PDF" else "${current?.collection ?: ""}  •  ${current?.page?.plus(1) ?: 0}")
            Spacer(Modifier.height(24.dp))
            if (current == null) {
                Button(onClick = { folderPicker.launch(null) }) { Text("+ Ajouter un PDF") }
                Spacer(Modifier.height(10.dp)); Text("Choisis le dossier qui contient ton PDF (par exemple Téléchargements), puis l'application importe automatiquement tous les PDF de ce dossier.")
            } else {
                Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(18.dp)); Text(current.text); Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { OutlinedButton(onClick = { index = (index - 1 + poems.size) % poems.size; saveIndex() }) { Text("‹ Précédent") }; Button(onClick = { index = (index + 1) % poems.size; saveIndex() }) { Text("Suivant ›") } }
            }
            Spacer(Modifier.height(12.dp)); if (importMessage.isNotEmpty() && !importing) { Text(importMessage, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { mode = if (mode == "Jour") "Aléatoire" else "Jour" }) { Text(if (mode == "Jour") "Mode aléatoire" else "Mode ordre") }; TextButton(onClick = { showLibrary = true }) { Text("Bibliothèque (${poems.size})") }; TextButton(onClick = { folderPicker.launch(null) }) { Text("+ PDF") } }
        }
    }

    private fun saveIndex() { prefs().edit().putInt("index", index).apply() }

    @Composable fun LibraryScreen() {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row { TextButton(onClick = { showLibrary = false }) { Text("‹ Retour") }; Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp)); if (poems.isEmpty()) Text("Aucun poème importé.") else LazyColumn { items(poems) { p -> ListItem(headlineContent = { Text(p.title) }, supportingContent = { Text(p.collection) }) } }
        }
    }
}
