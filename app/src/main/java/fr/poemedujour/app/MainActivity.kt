package fr.poemedujour.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import kotlin.math.abs

private data class Poem(val id: Long, val collection: String, val title: String, val text: String, val page: Int, val pdfPath: String)

class MainActivity : ComponentActivity() {
    private val poems = mutableStateListOf<Poem>()
    private var mode by mutableStateOf("Jour")
    private var index by mutableIntStateOf(0)
    private var showLibrary by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        loadPoems()
        setContent { App() }
    }

    private fun loadPoems() {
        poems.clear()
        val dir = File(filesDir, "pdfs"); dir.mkdirs()
        dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.forEach { file -> parsePdf(file) }
        index = prefs().getInt("index", 0).coerceIn(0, maxOf(0, poems.size - 1))
    }

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)

    private fun parsePdf(file: File) {
        try {
            PDDocument.load(file).use { doc ->
                val name = file.nameWithoutExtension
                val total = doc.numberOfPages
                for (p in 0 until total) {
                    val text = try { com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = p + 1; endPage = p + 1 }.getText(doc).trim() } catch (_: Exception) { "" }
                    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isEmpty()) continue
                    val title = lines.firstOrNull { it.length in 2..100 && !it.matches(Regex(".*\\d{1,2}.*")) } ?: "Page ${p + 1}"
                    val body = lines.dropWhile { it != title }.drop(1).joinToString("\n")
                    if (body.length >= 20) poems.add(Poem((file.absolutePath + p).hashCode().toLong(), name, title, body, p, file.absolutePath))
                }
            }
        } catch (_: Exception) { }
    }

    private fun importPdf(uri: Uri) {
        val name = (contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "recueil_${System.currentTimeMillis()}.pdf").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(File(filesDir, "pdfs"), name)
        contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        parsePdf(out); index = poems.lastIndex.coerceAtLeast(0)
    }

    @Composable fun App() {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                if (showLibrary) LibraryScreen() else HomeScreen()
            }
        }
    }

    @Composable fun HomeScreen() {
        val context = LocalContext.current
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importPdf(it) } }
        val current = poems.getOrNull(if (mode == "Aléatoire" && poems.isNotEmpty()) abs(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) * 31 % poems.size) else index)
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Text("Poème du Jour", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(if (poems.isEmpty()) "Ajoute ton premier recueil PDF" else "${current?.collection ?: ""}  •  ${current?.page?.plus(1) ?: 0}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            if (current == null) {
                Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("+ Ajouter un PDF") }
                Spacer(Modifier.height(12.dp)); Text("Les PDF restent sur le téléphone. L'application fonctionne hors connexion.")
            } else {
                Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(18.dp))
                Text(current.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = { index = (index - 1 + poems.size) % poems.size; saveIndex() }) { Text("‹ Précédent") }
                    Button(onClick = { index = (index + 1) % poems.size; saveIndex() }) { Text("Suivant ›") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { mode = if (mode == "Jour") "Aléatoire" else "Jour" }) { Text(if (mode == "Jour") "Mode aléatoire" else "Mode ordre") }
                TextButton(onClick = { showLibrary = true }) { Text("Bibliothèque (${poems.size})") }
                TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("+ PDF") }
            }
        }
    }

    private fun saveIndex() { prefs().edit().putInt("index", index).apply() }

    @Composable fun LibraryScreen() {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLibrary = false }) { Text("‹ Retour") }
                Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            if (poems.isEmpty()) Text("Aucun poème importé.") else LazyColumn { items(poems) { p ->
                ListItem(headlineContent = { Text(p.title) }, supportingContent = { Text(p.collection) }, modifier = Modifier.fillMaxWidth())
            } }
        }
    }
}
