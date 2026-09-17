package fr.poemedujour.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.util.*

data class Poem(val id: Long, val collection: String, val title: String, val text: String, val page: Int, val pdfPath: String, val date: String?)

class MainActivity : ComponentActivity() {
    private val poems = mutableStateListOf<Poem>()
    private var pendingPoems = mutableStateListOf<Poem>()
    private var selectedIds = mutableStateOf(setOf<Long>())
    private var mode by mutableStateOf("Jour")
    private var index by mutableIntStateOf(0)
    private var showLibrary by mutableStateOf(false)
    private var showSelection by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var openedPoem by mutableStateOf<Poem?>(null)
    private var importing by mutableStateOf(false)
    private var importMessage by mutableStateOf("")

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { importFolder(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        createNotificationChannel()
        setContent { App() }
        loadPoemsInBackground()
    }

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)

    private fun loadPoemsInBackground() {
        Thread {
            val found = mutableListOf<Poem>()
            val dir = File(filesDir, "pdfs"); dir.mkdirs()
            dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.forEach { found.addAll(parsePdf(it)) }
            runOnUiThread {
                poems.clear(); poems.addAll(found)
                index = prefs().getInt("index", 0).coerceIn(0, maxOf(0, poems.size - 1))
            }
        }.start()
    }

    private fun extractDate(text: String): String? {
        val numeric = Regex("\\b(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4})\\b").find(text)?.groupValues?.get(1)
        if (numeric != null) return numeric.replace('.', '/').replace('-', '/')
        val months = "janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre"
        return Regex("\\b\\d{1,2}\\s+(?:$months)\\s+\\d{4}\\b", RegexOption.IGNORE_CASE).find(text)?.value
    }

    private fun parsePdf(file: File): List<Poem> {
        val result = mutableListOf<Poem>()
        try {
            PDDocument.load(file).use { doc ->
                val name = file.nameWithoutExtension
                for (p in 0 until doc.numberOfPages) {
                    val text = try {
                        com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = p + 1; endPage = p + 1 }.getText(doc).trim()
                    } catch (_: Exception) { "" }
                    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isEmpty()) continue
                    val title = lines.firstOrNull { it.length in 2..100 && !it.matches(Regex(".*\\d{1,2}.*")) } ?: "Page ${p + 1}"
                    val body = lines.dropWhile { it != title }.drop(1).joinToString("\n")
                    if (body.length < 20) continue
                    val meta = (title + " " + body.take(500)).lowercase()
                    val excluded = listOf("table des matières", "table des matieres", "sommaire", "contents", "index", "notes", "note de", "notes de", "commentaires", "commentaire", "préface", "preface", "introduction", "avant-propos", "avant propos").any { meta.contains(it) }
                    if (excluded) continue
                    result.add(Poem((file.absolutePath + p).hashCode().toLong(), name, title, body, p, file.absolutePath, extractDate(text)))
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun importFolder(treeUri: Uri) {
        if (importing) return
        importing = true; importMessage = "Recherche des PDF…"
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
                    importing = false; pendingPoems.clear(); pendingPoems.addAll(all); selectedIds.value = all.map { it.id }.toSet()
                    importMessage = if (all.isEmpty()) "Aucun poème détecté dans ce dossier." else "${all.size} poèmes détectés. Vérifie la sélection avant de valider."
                    showSelection = all.isNotEmpty()
                }
            } catch (_: Exception) { runOnUiThread { importing = false; importMessage = "Impossible d’importer ce dossier." } }
        }.start()
    }

    private fun validateSelection() {
        val chosen = pendingPoems.filter { it.id in selectedIds.value }
        poems.clear(); poems.addAll(chosen); index = 0; saveIndex(); pendingPoems.clear(); selectedIds.value = emptySet(); showSelection = false
        importMessage = "${chosen.size} poèmes sélectionnés."
    }

    @Composable fun App() {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                when {
                    showSelection -> SelectionScreen()
                    openedPoem != null -> PoemDetailScreen(openedPoem!!)
                    showSettings -> SettingsScreen()
                    showLibrary -> LibraryScreen()
                    else -> HomeScreen()
                }
                if (importing) AlertDialog(onDismissRequest = {}, title = { Text("Importation") }, text = { Text(importMessage) }, confirmButton = {})
            }
        }
    }

    @Composable fun HomeScreen() {
        val current = poems.getOrNull(index)
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Text("Poème du Jour", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp)); Text(if (poems.isEmpty()) "Ajoute ton premier recueil PDF" else "${current?.collection ?: ""}  •  ${current?.page?.plus(1) ?: 0}${current?.date?.let { "  •  $it" } ?: ""}")
            Spacer(Modifier.height(24.dp))
            if (current == null) {
                Button(onClick = { folderPicker.launch(null) }) { Text("+ Ajouter un PDF") }
                Spacer(Modifier.height(10.dp)); Text("Choisis le dossier qui contient ton PDF (par exemple Téléchargements), puis l'application importe automatiquement tous les PDF de ce dossier.")
            } else {
                Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(18.dp)); Text(current.text); Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = { if (mode == "Aléatoire") index = randomOtherIndex() else index = (index - 1 + poems.size) % poems.size; saveIndex() }) { Text("‹ Précédent") }
                    Button(onClick = { if (mode == "Aléatoire") index = randomOtherIndex() else index = (index + 1) % poems.size; saveIndex() }) { Text("Suivant ›") }
                }
            }
            Spacer(Modifier.height(12.dp)); if (importMessage.isNotEmpty() && !importing) { Text(importMessage, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { mode = if (mode == "Jour") "Aléatoire" else "Jour"; if (mode == "Aléatoire" && poems.size > 1) index = randomOtherIndex() }) { Text(if (mode == "Jour") "Mode aléatoire" else "Mode ordre") }
                TextButton(onClick = { showLibrary = true }) { Text("Bibliothèque (${poems.size})") }
                TextButton(onClick = { showSettings = true }) { Text("Réglages") }
                TextButton(onClick = { folderPicker.launch(null) }) { Text("+ PDF") }
            }
        }
    }

    private fun randomOtherIndex(): Int {
        if (poems.size <= 1) return 0
        var n = Random().nextInt(poems.size)
        while (n == index) n = Random().nextInt(poems.size)
        return n
    }

    @Composable fun SelectionScreen() {
        val selected = selectedIds.value
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Sélection des poèmes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("${selected.size} / ${pendingPoems.size} sélectionnés")
            Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { selectedIds.value = pendingPoems.map { it.id }.toSet() }) { Text("Tout sélectionner") }; TextButton(onClick = { selectedIds.value = emptySet() }) { Text("Tout désélectionner") } }
            LazyColumn(Modifier.weight(1f)) { items(pendingPoems) { p -> val checked = p.id in selected; ListItem(headlineContent = { Text(p.title) }, supportingContent = { Text("${p.collection} • page ${p.page + 1}${p.date?.let { " • $it" } ?: ""}") }, leadingContent = { Checkbox(checked = checked, onCheckedChange = { on -> selectedIds.value = if (on) selected + p.id else selected - p.id }) }) } }
            Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { OutlinedButton(onClick = { pendingPoems.clear(); selectedIds.value = emptySet(); showSelection = false }) { Text("Annuler") }; Button(onClick = { validateSelection() }, enabled = selected.isNotEmpty()) { Text("Valider la sélection") } }
        }
    }

    @Composable fun LibraryScreen() {
        var filter by remember { mutableStateOf("Tous") }
        val collections = listOf("Tous") + poems.map { it.collection }.distinct().sorted()
        val filtered = if (filter == "Tous") poems else poems.filter { it.collection == filter }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { showLibrary = false }) { Text("‹ Retour") }; Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Recueil : $filter") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { collections.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { filter = c; expanded = false }) } }
            }
            Spacer(Modifier.height(10.dp)); Text("${filtered.size} poème(s)", style = MaterialTheme.typography.bodySmall)
            if (filtered.isEmpty()) Text("Aucun poème dans ce recueil.") else LazyColumn { items(filtered) { p -> ListItem(modifier = Modifier.clickable { openedPoem = p }, headlineContent = { Text(p.title) }, supportingContent = { Text("${p.date ?: "Date inconnue"} • page ${p.page + 1} • ${p.collection}") }) } }
        }
    }

    @Composable fun PoemDetailScreen(poem: Poem) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = { openedPoem = null }) { Text("‹ Retour à la bibliothèque") }
            Spacer(Modifier.height(8.dp)); Text(poem.collection, style = MaterialTheme.typography.bodyMedium); Text(poem.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(poem.date ?: "Date inconnue", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(20.dp)); Text(poem.text)
        }
    }

    @Composable fun SettingsScreen() {
        val enabled = prefs().getBoolean("notif_enabled", false)
        var notifEnabled by remember { mutableStateOf(enabled) }
        var hour by remember { mutableIntStateOf(prefs().getInt("notif_hour", 9)) }
        var minute by remember { mutableIntStateOf(prefs().getInt("notif_minute", 0)) }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            TextButton(onClick = { showSettings = false }) { Text("‹ Retour") }
            Text("Réglages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Notification quotidienne"); Switch(checked = notifEnabled, onCheckedChange = { notifEnabled = it; prefs().edit().putBoolean("notif_enabled", it).apply(); if (it) scheduleNotification(hour, minute) else cancelNotification() }) }
            Spacer(Modifier.height(18.dp)); Text("Heure : %02d:%02d".format(hour, minute)); Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { hour = (hour + 1) % 24; prefs().edit().putInt("notif_hour", hour).apply(); if (notifEnabled) scheduleNotification(hour, minute) }) { Text("+ 1 h") }
                Button(onClick = { minute = (minute + 5) % 60; prefs().edit().putInt("notif_minute", minute).apply(); if (notifEnabled) scheduleNotification(hour, minute) }) { Text("+ 5 min") }
                OutlinedButton(onClick = { minute = 0; prefs().edit().putInt("notif_minute", 0).apply(); if (notifEnabled) scheduleNotification(hour, 0) }) { Text(":00") }
            }
            Spacer(Modifier.height(10.dp)); Text("Tu peux programmer l'heure par pas de 5 minutes. La notification ouvre l'application.")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("poeme_du_jour", "Poème du Jour", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun scheduleNotification(hour: Int, minute: Int) {
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, NotificationReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        alarm.cancel(pi); alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    private fun cancelNotification() {
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(this, 1001, Intent(this, NotificationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pi)
    }

    private fun saveIndex() { prefs().edit().putInt("index", index).apply() }
}
