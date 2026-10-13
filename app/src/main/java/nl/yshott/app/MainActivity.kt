@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package nl.yshott.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Dutch = Locale.forLanguageTag("nl-NL")
private val Navy = Color(0xFF10334C)
private val Mint = Color(0xFFDDF3EB)
private val Lavender = Color(0xFFEAE5FB)
private val Peach = Color(0xFFFBEADF)
private val Surface = Color(0xFFF4F8FC)
private fun dateTitle(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Dutch))
private fun fmtDay(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Dutch))

class MainActivity : ComponentActivity() {
    private lateinit var tts: TextToSpeech
    private var voiceReady = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) voiceReady = tts.setLanguage(Dutch) >= TextToSpeech.LANG_AVAILABLE
        }
        setContent { YShottApp(this) { text -> say(text) } }
    }
    private fun say(text: String) {
        if (voiceReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, newId())
        else Toast.makeText(this, "Installeer een Nederlandse voorleesstem in de telefooninstellingen.", Toast.LENGTH_LONG).show()
    }
    override fun onDestroy() { tts.stop(); tts.shutdown(); super.onDestroy() }
}

private data class FormRequest(val type: String, val id: String = "", val date: String = LocalDate.now().toString(), val slot: String = "Diner")
private data class NavItem(val page: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable private fun YShottApp(activity: MainActivity, speak: (String) -> Unit) {
    val store = remember { LocalStore(activity) }
    val initialLoad = remember { runCatching { store.load() } }
    var data by remember { mutableStateOf(initialLoad.getOrNull() ?: PlannerData()) }
    var demo by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("today") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val day = LocalDate.parse(dateText)
    var calendarView by rememberSaveable { mutableStateOf("Maand") }
    var activePerson by remember { mutableStateOf(store.childProfile) }
    val child = activePerson.isNotEmpty()
    var form by remember { mutableStateOf<FormRequest?>(null) }
    var pinGate by remember { mutableStateOf<String?>(null) }
    var toastText by remember { mutableStateOf("") }
    var cooking by rememberSaveable { mutableStateOf("") }
    var list by rememberSaveable { mutableStateOf("Boodschappen") }
    fun notify(text: String) { toastText = text; Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    LaunchedEffect(Unit) {
        if (demo && initialLoad.getOrNull() == null) {
            demo = false
            notify("De planner kon niet worden hersteld. De opgeslagen gegevens zijn niet overschreven.")
        }
    }
    fun change(next: PlannerData, message: String = "Bewaard op dit apparaat") {
        runCatching { store.save(next) }.onSuccess { data = next; notify(message) }.onFailure { notify(it.message ?: "Opslaan is niet gelukt") }
    }
    fun openExample() {
        runCatching { store.load() ?: exampleData().also { store.save(it) } }
            .onSuccess { data = it; activePerson = store.childProfile; demo = true }
            .onFailure { notify("De bestaande planner kon niet worden geladen. Er is niets overschreven.") }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            activity.contentResolver.openOutputStream(uri)?.use { it.write(appJson.encodeToString(data).toByteArray()) } ?: error("Geen bestandstoegang")
        }.onSuccess { notify("Bestand opgeslagen") }.onFailure { notify("Bestand kon niet worden opgeslagen") }
    }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) runCatching { activity.contentResolver.openOutputStream(uri)?.use { it.write(CalendarExport.ics(data.events).toByteArray()) } ?: error("Geen bestandstoegang") }
            .onSuccess { notify("Agenda geëxporteerd") }.onFailure { notify("Exporteren is niet gelukt") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !child) runCatching {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { readLimited(it, 1_000_001) } ?: error("Geen bestandstoegang")
            require(bytes.size <= 1_000_000) { "De back-up is te groot" }
            appJson.decodeFromString<PlannerData>(bytes.toString(Charsets.UTF_8)).also { PlannerRules.validate(it) }
        }.onSuccess { form = null; change(it, "Back-up hersteld") }.onFailure { notify(it.message ?: "Geen geldige back-up") }
    }
    val dark = data.settings.dark
    val scheme = if (dark) darkColorScheme(primary = Color(0xFFAAC9E2), background = Color(0xFF111C28), surface = Color(0xFF1B2A3A), secondaryContainer = Color(0xFF28473F))
    else lightColorScheme(primary = Navy, onPrimary = Color.White, secondaryContainer = Mint, onSecondaryContainer = Navy, background = Surface, surface = Color.White, outlineVariant = Color(0xFFE1E8EE), surfaceVariant = Color(0xFFEAF1F6), onSurface = Navy, onBackground = Navy)
    MaterialTheme(colorScheme = scheme, typography = ReferenceTypography) {
        if (!demo) {
            Landing(onExample = ::openExample, onUnavailable = { notify("De echte accountkoppeling wordt nog aangesloten. Deze versie verstuurt nog geen bevestigingsmail.") })
            return@MaterialTheme
        }
        val person = data.people.find { it.id == activePerson }
        val nav = if (child) listOf(NavItem("today", "Mijn dag", Icons.Outlined.WbSunny), NavItem("tasks", "Klusjes", Icons.Outlined.CheckCircleOutline), NavItem("rewards", "Beloningen", Icons.Outlined.CardGiftcard), NavItem("chat", "Chat", Icons.Outlined.ChatBubbleOutline))
            else listOf(NavItem("today", "Vandaag", Icons.Outlined.Home), NavItem("agenda", "Agenda", Icons.Outlined.CalendarMonth), NavItem("chat", "Chat", Icons.Outlined.ChatBubbleOutline), NavItem("more", "Meer", Icons.Outlined.MoreHoriz))
        var profileMenu by remember { mutableStateOf(false) }
        Scaffold(
            topBar = { ReferenceHeader(data,child,activePerson,
                onChild = { pinGate = it }, onParent = { pinGate = "parent" },
                onListen = { speak("Dit is jouw dag. Tik op een luidspreker om te horen wat je gaat doen.") },
                onNotifications = { notify("Online meldingen worden nog aangesloten.") }) },
            bottomBar = { ReferenceNav(child,page) { page = it; cooking = ""; if(child) speak(when(it) { "today" -> "Mijn dag"; "tasks" -> "Klusjes"; "rewards" -> "Beloningen"; else -> "Chat" }) } },
            floatingActionButton = {
                if (!child && page in listOf("tasks", "shopping", "recipes", "rewards", "chat")) FloatingActionButton(onClick = {
                    form = FormRequest(when(page) { "agenda" -> "event"; "tasks" -> "task"; "shopping" -> "grocery"; "recipes" -> "recipe"; "rewards" -> "reward"; else -> "proposal" }, date = dateText)
                }, containerColor = Navy, contentColor = Color.White) { Icon(Icons.Outlined.Add, "Toevoegen") }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 3.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(if(child)8.dp else 12.dp)) {
                val visibleEvents = data.events.filter { PlannerRules.occurs(it, day) && (!child || it.person == activePerson || it.relatedChild == activePerson) }.sortedBy { it.time }
                val visibleTasks = data.tasks.filter { !child || it.person == activePerson }
                when (page) {
                    "today" -> ReferenceToday(data,child,activePerson,speak,
                        onAgenda = { page = "agenda"; dateText = LocalDate.now().toString() },
                        onTasks = { page = "tasks" }, onCook = { cooking = it; page = "cook" },
                        onMeal = { form = FormRequest("meal", slot = "Diner") },
                        onEdit = { form = FormRequest("event", it.id) },
                        onDone = { t -> runCatching { PlannerRules.completeTask(data,t.id,child,activePerson) }
                            .onSuccess { change(it,if(child&&t.parentCheck)"Je klusje wacht op controle van een ouder" else "Klusje klaar") }
                            .onFailure { notify(it.message ?: "Probeer opnieuw") } },
                        onSettings = { page = "settings" })
                    "agenda" -> {
                        ReferenceAgendaTitle { form = FormRequest("event", date = dateText) }
                        ReferenceViewSwitch(calendarView) { calendarView = it }
                        ReferenceCalendar(day,calendarView,data) { dateText = it.toString() }
                        ReferenceDayTitle(day,data)
                        if (visibleEvents.isEmpty()) EmptyCard("📅", "Geen afspraken op deze dag")
                        visibleEvents.forEach { e -> ReferenceEventCard(e,data,true,{ form = FormRequest("event",e.id) }) }
                        data.proposals.filter { it.status == "pending" && it.event.date == dateText }.forEach { p ->
                            YCard {
                                Text("Voorstel: " + p.event.title, fontWeight = FontWeight.Bold); Text(p.event.time + " · wacht op akkoord")
                                Row { TextButton(onClick = { change(PlannerRules.approveProposal(data, p.id), "Voorbeeldvoorstel goedgekeurd en toegevoegd") }) { Text("Goedkeuren") }
                                    TextButton(onClick = { change(data.copy(proposals = data.proposals.map { if (it.id == p.id) it.copy(status = "rejected") else it }), "Voorstel afgewezen") }) { Text("Afwijzen") } }
                            }
                        }
                    }
                    "tasks" -> {
                        ScreenTitle(if (child) "🧺 Mijn klusjes" else "Taken & klusjes")
                        if (visibleTasks.isEmpty()) EmptyCard("✅", "Er staan nog geen klusjes")
                        visibleTasks.forEach { t ->
                            TaskCard(t, data, child, speak, onDone = {
                                runCatching {
                                    if (!child && t.status == "pending") PlannerRules.approveTask(data, t.id)
                                    else PlannerRules.completeTask(data, t.id, child, activePerson)
                                }.onSuccess { change(it, "Taak bijgewerkt") }.onFailure { notify(it.message ?: "Probeer opnieuw") }
                            }, onEdit = { form = FormRequest("task", t.id) })
                        }
                    }
                    "shopping" -> {
                        ScreenTitle("Boodschappen")
                        val lists = (listOf("Boodschappen") + data.groceries.map { it.list }).distinct()
                        ChoiceRow(lists, list) { list = it }
                        Button(onClick = { form = FormRequest("staples") }) { Text("Vaste boodschappen aanvinken") }
                        val items = data.groceries.filter { it.list == list }.sortedWith(compareBy<Grocery> { it.done }.thenBy { it.category })
                        if (items.isEmpty()) EmptyCard("🛒", "Je lijst is nog leeg")
                        items.forEach { g ->
                            YCard { Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = g.done, onCheckedChange = { v -> change(data.copy(groceries = data.groceries.map { if (it.id == g.id) it.copy(done = v) else it })) })
                                Column(Modifier.weight(1f)) { Text(g.name, fontWeight = FontWeight.Medium); Text(g.amount + " · " + g.category, fontSize = 12.sp) }
                                IconButton(onClick = { form = FormRequest("grocery", g.id) }) { Icon(Icons.Outlined.Edit, "Artikel bewerken") }
                            } }
                        }
                    }
                    "meals" -> {
                        ScreenTitle("Ons weekmenu")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = { dateText = day.minusWeeks(1).toString() }) { Icon(Icons.Outlined.ChevronLeft, "Vorige week") }
                            Text(fmtDay(day.minusDays((day.dayOfWeek.value - 1).toLong())))
                            IconButton(onClick = { dateText = day.plusWeeks(1).toString() }) { Icon(Icons.Outlined.ChevronRight, "Volgende week") }
                        }
                        TextButton(onClick = {
                            val first = day.minusDays((day.dayOfWeek.value - 1).toLong())
                            val copies = data.meals.filter { LocalDate.parse(it.date) in first..first.plusDays(6) }.map { it.copy(id = newId(), date = LocalDate.parse(it.date).plusWeeks(1).toString()) }
                            change(data.copy(meals = data.meals.filterNot { old -> copies.any { it.date == old.date && it.slot == old.slot } } + copies), "Weekmenu gekopieerd")
                        }) { Text("Kopieer naar volgende week") }
                        val first = day.minusDays((day.dayOfWeek.value - 1).toLong())
                        (0..6).forEach { n ->
                            val d = first.plusDays(n.toLong())
                            YCard {
                                Text(fmtDay(d), fontWeight = FontWeight.Bold)
                                listOf("Ontbijt", "Lunch", "Diner").forEach { slot ->
                                    val m = data.meals.firstOrNull { it.date == d.toString() && it.slot == slot }
                                    TextButton(onClick = { form = FormRequest("meal", m?.id ?: "", d.toString(), slot) }) { Text(slot + ": " + (m?.title ?: "+ Toevoegen")) }
                                    if (m?.recipe?.isNotEmpty() == true) TextButton(onClick = { cooking = m.recipe; page = "cook" }) { Text("🍳 Koken met dit recept") }
                                }
                            }
                        }
                    }
                    "recipes" -> {
                        ScreenTitle("Onze recepten")
                        YCard { Text("AI-recepten zoeken wordt aangesloten na de gratis AI-koppeling.", fontSize = 13.sp); Text("Zelf een recept of bronlink toevoegen werkt in deze testversie.", fontSize = 12.sp) }
                        if (data.recipes.isEmpty()) EmptyCard("📖", "Bewaar je eerste recept")
                        data.recipes.forEach { r ->
                            YCard {
                                Text("🍳 " + r.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(r.minutes.toString() + " min · " + r.persons + " personen", fontSize = 12.sp)
                                Row {
                                    TextButton(onClick = { cooking = r.id; page = "cook" }) { Text("Kookweergave") }
                                    TextButton(onClick = { form = FormRequest("recipe", r.id) }) { Text("Bewerken") }
                                }
                                TextButton(onClick = { val additions = r.ingredients.filter { line -> data.groceries.none { !it.done && it.list == list && it.name.equals(line, true) } }.map { Grocery(name = it, list = list) }; change(data.copy(groceries = data.groceries + additions), "Ingrediënten toegevoegd"); page = "shopping" }) { Text("🛒 Ingrediënten op de lijst") }
                            }
                        }
                    }
                    "cook" -> {
                        val r = data.recipes.find { it.id == cooking }
                        if (r == null) EmptyCard("📖", "Kies eerst een recept") else CookingScreen(r, speak)
                    }
                    "rewards" -> {
                        ScreenTitle(if (child) "🎁 Mijn beloningen" else "Punten & beloningen")
                        val persons = data.people.filter { !child || it.id == activePerson }
                        persons.forEach { p -> YCard { Text(p.emoji + " " + p.name + "   ★ " + PlannerRules.points(data, p.id) + " punten", fontSize = if (child) 23.sp else 17.sp) } }
                        data.rewards.forEach { r ->
                            YCard {
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(r.emoji, fontSize = 44.sp); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(r.title, fontWeight = FontWeight.Bold); Text(r.points.toString() + " punten") }; if (child) Speaker("Voor " + r.points + " punten: " + r.title, speak) }
                                if (child) Button(onClick = { runCatching { PlannerRules.requestReward(data, r, activePerson) }.onSuccess { change(it, "Je aanvraag wacht op je ouder"); speak("Je aanvraag wacht op je ouder") }.onFailure { notify(it.message ?: "Nog niet mogelijk"); speak(it.message ?: "Nog niet mogelijk") } }) { Icon(Icons.Outlined.CardGiftcard, null); Text("  Aanvragen") }
                                else TextButton(onClick = { form = FormRequest("reward", r.id) }) { Text("Bewerken") }
                            }
                        }
                        data.rewardRequests.filter { !child || it.person == activePerson }.forEach { r -> YCard {
                            Text(r.title + " · " + r.status, fontWeight = FontWeight.Medium)
                            if (!child && r.status == "pending") Row { TextButton(onClick = { runCatching { PlannerRules.approveReward(data, r.id) }.onSuccess { change(it, "Beloning goedgekeurd") }.onFailure { notify(it.message ?: "Niet genoeg punten") } }) { Text("Goedkeuren") }
                                TextButton(onClick = { change(data.copy(rewardRequests = data.rewardRequests.map { if (it.id == r.id) it.copy(status = "rejected") else it }), "Aanvraag afgewezen") }) { Text("Afwijzen") } }
                        } }
                    }
                    "chat" -> {
                        ScreenTitle(if (child) "💬 Gezinschat" else "Gezinschat")
                        Text("Voorbeeldberichten blijven op dit apparaat. De gedeelde online chat wordt nog gekoppeld.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        data.messages.forEach { m ->
                            YCard {
                                Text(data.people.find { it.id == m.person }?.name ?: "Ouder", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(m.text, fontSize = if (child) 20.sp else 16.sp)
                                if (child) Speaker(m.text, speak)
                                val p = data.proposals.find { it.id == m.proposal }
                                if (p != null) {
                                    Text(p.event.date + " · " + p.event.time + " · " + p.status)
                                    if (!child && p.status == "pending") Row {
                                        TextButton(onClick = { change(PlannerRules.approveProposal(data, p.id), "Voorbeeldvoorstel staat nu in de agenda") }) { Text("Goedkeuren") }
                                        TextButton(onClick = { change(data.copy(proposals = data.proposals.map { if (it.id == p.id) it.copy(status = "rejected") else it }), "Voorstel afgewezen") }) { Text("Afwijzen") }
                                    }
                                }
                            }
                        }
                        var message by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(value = message, onValueChange = { message = it.take(2000) }, label = { Text("Bericht") }, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                            IconButton(onClick = { if (message.isNotBlank()) { change(data.copy(messages = data.messages + Message(person = activePerson, text = message.trim(), at = java.time.Instant.now().toString())), "Voorbeeldbericht bewaard"); message = "" } }) { Icon(Icons.Outlined.Send, "Bericht bewaren") }
                        })
                        if (child) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("👍" to "Goed", "❤️" to "Lief", "❓" to "Ik heb een vraag").forEach { (symbol, text) ->
                                    FilledTonalButton(onClick = { speak(text); change(data.copy(messages = data.messages + Message(person = activePerson, text = symbol + " " + text, at = java.time.Instant.now().toString())), "Bericht bewaard") }) { Text(symbol, fontSize = 30.sp) }
                                }
                            }
                        }
                    }
                    "residence" -> {
                        ScreenTitle("Verblijfsschema")
                        Text("Maak je eigen herhaling. Een concept hoort in de uiteindelijke online app pas actief te worden na akkoord van de andere ouder.", fontSize = 12.sp)
                        Button(onClick = { form = FormRequest("residence") }) { Text("Nieuw schema maken") }
                        data.residence.forEach { r ->
                            YCard {
                                Text((data.people.find { it.id == r.child }?.name ?: "Kind") + " · " + r.status, fontWeight = FontWeight.Bold)
                                Text("Start: " + r.starts + " · herhaalt na " + r.repeatsWeeks + " weken", fontSize = 12.sp)
                                Text("Wisselen: " + r.switchTime + (if (r.location.isNotBlank()) " · " + r.location else ""), fontSize = 12.sp)
                                r.days.chunked(7).forEachIndexed { index, days -> Text("Week " + (index + 1) + ": " + days.joinToString(" / "), fontSize = 11.sp) }
                                TextButton(onClick = { form = FormRequest("residence", r.id) }) { Text("Aanpassen") }
                                if (r.status == "concept") TextButton(onClick = { change(data.copy(residence = data.residence.map { if (it.id == r.id) it.copy(status = "active") else it }), "Schema actief in voorbeeldmodus") }) { Text("Activeren in voorbeeldmodus") }
                            }
                        }
                    }
                    "more" -> {
                        ScreenTitle("Ons gezin")
                        listOf("tasks" to "🧺 Taken en klusjes", "shopping" to "🛒 Boodschappen", "meals" to "🍽️ Weekmenu", "recipes" to "📖 Recepten", "rewards" to "🎁 Beloningen", "residence" to "🏠 Verblijfsschema's", "connections" to "🔗 Agenda's en online verbinding", "settings" to "⚙️ Instellingen").forEach { (p, title) ->
                            YCard { Row(Modifier.fillMaxWidth().clickable { page = p }, verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontSize = 18.sp); Icon(Icons.Outlined.ChevronRight, "Open " + title) } }
                        }
                    }
                    "connections" -> {
                        ScreenTitle("Agenda's en verbinding")
                        YCard {
                            Text("Online diensten", fontWeight = FontWeight.Bold)
                            Text("De echte account-, e-mail-, AI-, locatie- en synchronisatiekoppelingen worden nog ingericht. Deze testversie maakt geen online accounts aan.", fontSize = 13.sp)
                        }
                        listOf("Google Agenda", "Outlook / Microsoft 365", "Apple Agenda", "ICS / iCal-abonnementen").forEach { title -> YCard { Text(title, fontWeight = FontWeight.Medium); Text("Voorbereid in het plan; autorisatie en synchronisatie nog niet aangesloten.", fontSize = 12.sp) } }
                        YCard {
                            Text("Neem je afspraken mee", fontWeight = FontWeight.Bold)
                            Text("De eenmalige ICS-export werkt. De automatisch bijgewerkte abonnementslink komt na aansluiting van de backend.", fontSize = 12.sp)
                            Button(onClick = { calendarLauncher.launch("YShott-agenda.ics") }) { Text("Agenda exporteren") }
                        }
                        YCard { Text("Delen met een co-ouder", fontWeight = FontWeight.Bold); Text("Standaard alleen bezet/vrij. Volledige details pas na jouw bewuste keuze. Dit wordt ook op de server afgedwongen.", fontSize = 13.sp) }
                    }
                    "settings" -> {
                        ScreenTitle("Instellingen")
                        YCard {
                            Text("Gezinsleden", fontWeight = FontWeight.Bold)
                            data.people.forEach { p ->
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(p.emoji + " " + p.name, Modifier.weight(1f)); TextButton(onClick = { form = FormRequest("person", p.id) }) { Text("Bewerken") } }
                            }
                            TextButton(onClick = { form = FormRequest("person") }) { Text("+ Gezinslid toevoegen") }
                        }
                        YCard { Row(verticalAlignment = Alignment.CenterVertically) { Text("Donkere weergave", Modifier.weight(1f)); Switch(checked = dark, onCheckedChange = { change(data.copy(settings = data.settings.copy(dark = it))) }) } }
                        YCard {
                            Text("Onderdelen op Vandaag", fontWeight = FontWeight.Bold)
                            listOf("residence" to "Verblijf", "agenda" to "Afspraken", "tasks" to "Taken", "meal" to "Avondeten").forEach { (key, title) ->
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = key in data.settings.homeCards, onCheckedChange = { selected ->
                                    val next = if (selected) data.settings.homeCards + key else data.settings.homeCards - key
                                    change(data.copy(settings = data.settings.copy(homeCards = next.distinct())))
                                }); Text(title) }
                            }
                        }
                        YCard {
                            Text("Back-up", fontWeight = FontWeight.Bold)
                            Text("De lokale planner is versleuteld opgeslagen. Een geëxporteerde JSON-back-up is leesbaar: kies zelf een veilige bestemming.", fontSize = 12.sp)
                            TextButton(onClick = { exportLauncher.launch("YShott-backup.json") }) { Text("Back-up opslaan") }
                            TextButton(onClick = { form = FormRequest("restore") }) { Text("Back-up herstellen") }
                        }
                        YCard { Text("Over deze versie", fontWeight = FontWeight.Bold); Text("YShott 0.1.1 · native Android-testversie. Online diensten en apparaatmeldingen zijn nog niet aangesloten.", fontSize = 12.sp) }
                    }
                }
                Text("Voorbeeldmodus · alleen op dit apparaat",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.fillMaxWidth(),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        if (pinGate != null) PinDialog(store, onDismiss = { pinGate = null }, onAccepted = { value ->
            if (pinGate == "parent") { activePerson = ""; store.childProfile = ""; page = "today" }
            else { activePerson = pinGate ?: ""; store.childProfile = activePerson; page = "today"; speak("Dit is jouw dag") }
            pinGate = null
        })
        form?.let { request ->
            when (request.type) {
                "staples" -> StaplesDialog(data, list, onDismiss = { form = null }, onSave = { change(it, "Vaste boodschappen toegevoegd"); form = null })
                "restore" -> AlertDialog(onDismissRequest = { form = null }, title = { Text("Back-up herstellen?") }, text = { Text("De gekozen back-up vervangt de huidige voorbeeldplanner. Maak eerst een export als je die wilt bewaren.") }, confirmButton = { TextButton(onClick = { form = null; importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Bestand kiezen") } }, dismissButton = { TextButton(onClick = { form = null }) { Text("Annuleren") } })
                else -> EditDialog(request, data, list, onDismiss = { form = null }, onSaved = { result -> change(result); form = null }, onError = ::notify)
            }
        }
    }
}

@Composable private fun Landing(onExample: () -> Unit, onUnavailable: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(45.dp))
            Icon(Icons.Outlined.Home, null, Modifier.size(52.dp), tint = Navy)
            Text("YShott", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("Samen een beetje meer overzicht.", fontSize = 18.sp)
            OutlinedTextField(email, { email = it }, label = { Text("E-mailadres") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("Je viercijferige pincode") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(family, { family = it.take(24) }, label = { Text("Gezinscode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = onUnavailable, modifier = Modifier.fillMaxWidth()) { Text("Inloggen / aanmelden") }
            TextButton(onClick = onUnavailable) { Text("Nieuw gezin maken") }
            YCard {
                Text("Testversie 0.1", fontWeight = FontWeight.Bold)
                Text("De online accountkoppeling is nog in uitvoering. Je kunt hieronder het ontwerp en de lokale functies testen met voorbeeldgegevens.", fontSize = 13.sp)
                FilledTonalButton(onClick = onExample, modifier = Modifier.fillMaxWidth()) { Text("Voorbeeld bekijken") }
            }
        }
    }
}

@Composable private fun YCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun ScreenTitle(text: String) { Text(text, fontSize = 29.sp, fontWeight = FontWeight.Bold) }
@Composable private fun SectionTitle(text: String, onClick: () -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 19.sp); TextButton(onClick = onClick) { Text("Bekijk →", fontSize = 12.sp) } } }
@Composable private fun EmptyCard(emoji: String, text: String) { YCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(emoji, fontSize = 36.sp); Spacer(Modifier.width(14.dp)); Text(text, fontSize = 16.sp) } } }
@Composable private fun Speaker(text: String, speak: (String) -> Unit) { FilledIconButton(onClick = { speak(text) }, modifier = Modifier.size(50.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Navy, contentColor = Color.White)) { Icon(Icons.Outlined.VolumeUp, "Luister: " + text, Modifier.size(27.dp)) } }
@Composable private fun PictoCard(emoji: String, title: String, subtitle: String?, color: Color, onListen: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.padding(18.dp).heightIn(min = 70.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 46.sp); Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Navy); if (subtitle != null) Text(subtitle, color = Navy, fontSize = 13.sp) }
            FilledIconButton(onClick = onListen, modifier = Modifier.size(50.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Navy, contentColor = Color.White)) { Icon(Icons.Outlined.VolumeUp, "Uitleg beluisteren", Modifier.size(27.dp)) }
        }
    }
}
@Composable private fun EventCard(e: Event, data: PlannerData, child: Boolean, speak: (String) -> Unit, onEdit: () -> Unit) {
    if (child) ReferenceChildCard(referenceSymbol(e.title+" "+e.emoji),e.title,if(e.emoji=="🏊")DesignLavender else Color(0xFFE2F0FC),{speak(e.title+". Om "+e.time+". "+e.note)})
    else YCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.time, Modifier.width(58.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(e.title, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text((data.people.find { it.id == e.person }?.name ?: "Iedereen") + " · " + e.end, fontSize = 12.sp); if (e.place.isNotBlank()) Text(e.place, fontSize = 12.sp); if (e.note.isNotBlank()) Text(e.note, fontSize = 12.sp) }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Afspraak bewerken") }
        }
    }
}
@Composable private fun TaskCard(t: Task, data: PlannerData, child: Boolean, speak: (String) -> Unit, onDone: () -> Unit, onEdit: () -> Unit) {
    if(child) { ReferenceChildTask(t,speak,onDone); return }
    YCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.emoji, fontSize = if (child) 46.sp else 30.sp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontSize = if (child) 21.sp else 17.sp, fontWeight = FontWeight.Bold)
                Text(when(t.status) { "done" -> "✅ Klaar"; "pending" -> "⏳ Ouder kijkt nog"; else -> if (t.points > 0) "★ " + t.points + " punten" else "Nog te doen" }, fontSize = 12.sp)
                if (!child) Text(data.people.find { it.id == t.person }?.name ?: "Iedereen", fontSize = 11.sp)
            }
            if (child) Speaker(t.title + ". " + if(t.status == "pending") "Je ouder kijkt het nog na." else "Tik op het vinkje als je klaar bent.", speak)
            else IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Taak bewerken") }
        }
        if (t.status == "open" || (!child && t.status == "pending")) Button(onClick = onDone, modifier = if(child) Modifier.fillMaxWidth().heightIn(min = 48.dp) else Modifier) { Icon(Icons.Outlined.Check, null); Text(if (t.status == "pending") "  Goedkeuren" else "  Klaar") }
    }
}
@Composable private fun ChoiceRow(choices: List<String>, selected: String, onChoice: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { choices.forEach { text -> FilterChip(selected = text == selected, onClick = { onChoice(text) }, label = { Text(text) }) } }
}
@Composable private fun CalendarGrid(day: LocalDate, view: String, data: PlannerData, onDay: (LocalDate) -> Unit, speak: (String) -> Unit) {
    val month = YearMonth.from(day)
    val cells = if (view == "Maand") PlannerRules.monthCells(month) else if (view == "Week") {
        val first = day.minusDays((day.dayOfWeek.value - 1).toLong()); (0..6).map { first.plusDays(it.toLong()) }
    } else listOf(day)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onDay(when(view) { "Maand" -> day.minusMonths(1); "Week" -> day.minusWeeks(1); else -> day.minusDays(1) }) }) { Icon(Icons.Outlined.ChevronLeft, "Vorige periode") }
        Text(if(view == "Maand") day.format(DateTimeFormatter.ofPattern("MMMM yyyy", Dutch)).replaceFirstChar { it.titlecase(Dutch) } else fmtDay(day), fontWeight = FontWeight.Bold)
        IconButton(onClick = { onDay(when(view) { "Maand" -> day.plusMonths(1); "Week" -> day.plusWeeks(1); else -> day.plusDays(1) }) }) { Icon(Icons.Outlined.ChevronRight, "Volgende periode") }
    }
    if (view != "Dag") Row(Modifier.fillMaxWidth()) { listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp) } }
    cells.chunked(if(view == "Dag") 1 else 7).forEach { days ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            days.forEach { d ->
                val residence = data.residence.firstOrNull { it.status == "active" }?.let { PlannerRules.residenceAt(it, d) }
                val fill = if(d == day) Navy else if(residence == "Mama") Peach else if(residence != null) Mint else MaterialTheme.colorScheme.surface
                val foreground = if(d == day) Color.White else if(YearMonth.from(d) != month) Color.Gray else if(residence != null) Navy else MaterialTheme.colorScheme.onSurface
                Column(Modifier.weight(1f).heightIn(min = 52.dp).background(fill, RoundedCornerShape(10.dp)).clickable { onDay(d) }.semantics { contentDescription = dateTitle(d) + (if(residence != null) ", bij " + residence else "") }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(d.dayOfMonth.toString(), color = foreground, fontWeight = if(d == day) FontWeight.Bold else FontWeight.Normal)
                    val count = data.events.count { PlannerRules.occurs(it, d) }
                    if(count > 0) Text("•".repeat(minOf(count, 3)), color = if(d == day) Color.White else Color(0xFFAA85DC), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) { Text("🟢 Papa", fontSize = 11.sp); Text("🟠 Mama", fontSize = 11.sp) }
}
@Composable private fun CookingScreen(recipe: Recipe, speak: (String) -> Unit) {
    ScreenTitle(recipe.name)
    Text(recipe.minutes.toString() + " minuten · " + recipe.persons + " personen")
    YCard { Text("Ingrediënten", fontWeight = FontWeight.Bold); recipe.ingredients.forEach { Text("• " + it, fontSize = 18.sp) } }
    val checked = remember(recipe.id) { mutableStateMapOf<Int, Boolean>() }
    recipe.steps.forEachIndexed { index, step -> YCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = checked[index] == true, onCheckedChange = { checked[index] = it }); Text((index + 1).toString() + ". " + step, Modifier.weight(1f), fontSize = 20.sp); IconButton(onClick = { speak(step) }) { Icon(Icons.Outlined.VolumeUp, "Stap voorlezen") } }
    } }
    var minutes by rememberSaveable { mutableStateOf("5") }
    var end by rememberSaveable { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(end) {
        while (end > 0 && android.os.SystemClock.elapsedRealtime() < end) { remaining = (end - android.os.SystemClock.elapsedRealtime()) / 1000; kotlinx.coroutines.delay(1000) }
        if (end > 0) { remaining = 0; end = 0; speak("De kooktimer is klaar") }
    }
    YCard {
        Text("Kooktimer", fontWeight = FontWeight.Bold)
        if(end > 0) Text((remaining / 60).toString() + ":" + (remaining % 60).toString().padStart(2, '0'), fontSize = 30.sp)
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("Minuten") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)); Spacer(Modifier.width(12.dp)); Button(onClick = { end = android.os.SystemClock.elapsedRealtime() + ((minutes.toLongOrNull() ?: 5).coerceIn(1, 180) * 60000) }) { Text("Start") } }
        if(end > 0) TextButton(onClick = { end = 0 }) { Text("Stop") }
        Text("Deze timer geeft gesproken uitleg zolang deze testapp open is. Achtergrondmeldingen worden nog aangesloten.", fontSize = 11.sp)
    }
}
@Composable private fun PinDialog(store: LocalStore, onDismiss: () -> Unit, onAccepted: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val setup = !store.hasPin()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(setup) "Kies een ouderpincode" else "Ouderpincode") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if(setup) "Met deze pincode open je de ouderinstellingen vanuit de kindmodus. Bewaar hem zelf; e-mailherstel is nog niet gekoppeld." else "Voer de ouderpincode in.", fontSize = 13.sp)
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, label = { Text("Vier cijfers") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if(setup) OutlinedTextField(repeat, { repeat = it.filter(Char::isDigit).take(4) }, label = { Text("Herhaal pincode") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { require(pin.length == 4) { "Voer vier cijfers in" }; if(setup) { require(pin == repeat) { "De pincodes verschillen" }; store.setPin(pin) } else require(store.verifyPin(pin)) { "Pincode klopt niet" } }
                .onSuccess { onAccepted(pin) }.onFailure { error = it.message ?: "Probeer opnieuw" }
        }) { Text("Verder") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } })
}

private fun <T> List<T>.upsert(value: T, id: (T) -> String): List<T> = filterNot { id(it) == id(value) } + value

@Composable private fun EditDialog(request: FormRequest, data: PlannerData, list: String, onDismiss: () -> Unit, onSaved: (PlannerData) -> Unit, onError: (String) -> Unit) {
    val oldEvent = data.events.find { it.id == request.id }
    val oldTask = data.tasks.find { it.id == request.id }
    val oldGrocery = data.groceries.find { it.id == request.id }
    val oldRecipe = data.recipes.find { it.id == request.id }
    val oldMeal = data.meals.find { it.id == request.id }
    val oldReward = data.rewards.find { it.id == request.id }
    val oldPerson = data.people.find { it.id == request.id }
    val oldResidence = data.residence.find { it.id == request.id }
    val values = remember(request) {
        mutableStateMapOf(
            "title" to "", "name" to "", "emoji" to "📅", "date" to request.date, "time" to "09:00", "end" to "10:00",
            "person" to "", "relatedChild" to "", "dropOff" to "", "pickUp" to "", "place" to "", "note" to "",
            "repeat" to "none", "until" to "", "private" to "false", "points" to "5", "parentCheck" to "true",
            "amount" to "", "list" to list, "category" to "Overig", "minutes" to "30", "persons" to "4",
            "ingredients" to "", "steps" to "", "url" to "", "recipe" to "", "slot" to request.slot,
            "child" to "false", "weeks" to "2", "switchTime" to "18:00", "previous" to ""
        ).apply {
            when (request.type) {
                "event", "proposal" -> if(oldEvent != null) putAll(mapOf("title" to oldEvent.title, "date" to oldEvent.date, "time" to oldEvent.time, "end" to oldEvent.end, "emoji" to oldEvent.emoji, "person" to oldEvent.person, "relatedChild" to oldEvent.relatedChild, "dropOff" to oldEvent.dropOff, "pickUp" to oldEvent.pickUp, "place" to oldEvent.place, "note" to oldEvent.note, "repeat" to oldEvent.repeat, "until" to oldEvent.until, "private" to oldEvent.private.toString()))
                "task" -> if(oldTask != null) putAll(mapOf("title" to oldTask.title, "person" to oldTask.person, "date" to oldTask.date, "points" to oldTask.points.toString(), "parentCheck" to oldTask.parentCheck.toString(), "emoji" to oldTask.emoji)) else put("emoji", "🎒")
                "grocery" -> if(oldGrocery != null) putAll(mapOf("name" to oldGrocery.name, "amount" to oldGrocery.amount, "list" to oldGrocery.list, "category" to oldGrocery.category))
                "recipe" -> if(oldRecipe != null) putAll(mapOf("name" to oldRecipe.name, "minutes" to oldRecipe.minutes.toString(), "persons" to oldRecipe.persons.toString(), "ingredients" to oldRecipe.ingredients.joinToString("\n"), "steps" to oldRecipe.steps.joinToString("\n"), "url" to oldRecipe.url))
                "meal" -> if(oldMeal != null) putAll(mapOf("title" to oldMeal.title, "date" to oldMeal.date, "slot" to oldMeal.slot, "recipe" to oldMeal.recipe))
                "reward" -> if(oldReward != null) putAll(mapOf("title" to oldReward.title, "points" to oldReward.points.toString(), "emoji" to oldReward.emoji)) else put("emoji", "🎁")
                "person" -> if(oldPerson != null) putAll(mapOf("name" to oldPerson.name, "child" to oldPerson.child.toString(), "emoji" to oldPerson.emoji)) else put("emoji", "🙂")
                "residence" -> if(oldResidence != null) putAll(mapOf("person" to oldResidence.child, "date" to oldResidence.starts, "weeks" to oldResidence.repeatsWeeks.toString(), "switchTime" to oldResidence.switchTime, "place" to oldResidence.location))
            }
        }
    }
    val parents = data.people.filter { !it.child }.map { it.name }.ifEmpty { listOf("Papa", "Mama") }
    var residenceDays by remember(request) { mutableStateOf(oldResidence?.days ?: List(14) { parents.first() }) }
    var error by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }
    fun v(key: String) = values[key]?.trim().orEmpty()
    @Composable fun Input(label: String, key: String, number: Boolean = false, multiple: Boolean = false) {
        OutlinedTextField(value = values[key].orEmpty(), onValueChange = { values[key] = if(number) it.filter(Char::isDigit).take(4) else it.take(if(multiple) 5000 else 200) },
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = !multiple,
            minLines = if(multiple) 3 else 1, keyboardOptions = KeyboardOptions(keyboardType = if(number) KeyboardType.Number else KeyboardType.Text))
    }
    @Composable fun SelectPerson(label: String, key: String = "person", childrenOnly: Boolean = false) {
        Text(label, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterChip(selected = v(key).isEmpty(), onClick = { values[key] = "" }, label = { Text("Geen / iedereen") })
            data.people.filter { !childrenOnly || it.child }.forEach { p -> FilterChip(selected = v(key) == p.id, onClick = { values[key] = p.id }, label = { Text(p.emoji + " " + p.name) }) }
        }
    }
    val title = mapOf("event" to "Afspraak", "proposal" to "Afspraakvoorstel", "task" to "Taak / klusje", "grocery" to "Artikel", "recipe" to "Recept", "meal" to "Maaltijd", "reward" to "Beloning", "person" to "Gezinslid", "residence" to "Verblijfsschema")[request.type] ?: "Toevoegen"
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(request.type) {
                "event", "proposal" -> {
                    Input("Naam", "title"); Input("Datum (jjjj-mm-dd)", "date"); Input("Begintijd (uu:mm)", "time"); Input("Eindtijd (uu:mm)", "end")
                    SelectPerson("Voor wie?"); SelectPerson("Betreft welk kind?", "relatedChild", true)
                    SelectPerson("Wie brengt?", "dropOff"); SelectPerson("Wie haalt op?", "pickUp")
                    Input("Locatie", "place"); Input("Notities", "note", multiple = true); Input("Pictogram (emoji)", "emoji")
                    val overlapping = runCatching {
                        val candidate = Event(id = if(request.type == "event") request.id else "preview", title = v("title"), date = v("date"), time = v("time"), end = v("end"),
                            person = v("person"), relatedChild = v("relatedChild"), dropOff = v("dropOff"), pickUp = v("pickUp"), repeat = v("repeat"), until = v("until"))
                        PlannerRules.conflicts(candidate, data.events.filterNot { it.id == v("previous") }, LocalDate.parse(v("date")))
                    }.getOrDefault(emptyList())
                    if(overlapping.isNotEmpty()) Text(
                        "Let op: op deze dag overlapt dit met " + overlapping.joinToString { it.title } + ". Je kunt de tijden aanpassen of toch bewaren.",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp
                    )
                    Text("Herhaling", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("none" to "Geen", "daily" to "Dagelijks", "weekly" to "Wekelijks", "monthly" to "Maandelijks").forEach { (key, text) ->
                            FilterChip(selected = v("repeat") == key, onClick = { values["repeat"] = key }, label = { Text(text) })
                        }
                    }
                    if(v("repeat") != "none") Input("Herhalen tot (optioneel)", "until")
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Persoonlijke afspraak", Modifier.weight(1f)); Switch(checked = v("private") == "true", onCheckedChange = { values["private"] = it.toString() }) }
                    if(request.type == "proposal") {
                        Text("Een wijziging van een bestaande afspraak?", fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            FilterChip(selected = v("previous").isEmpty(), onClick = { values["previous"] = "" }, label = { Text("Nieuwe afspraak") })
                            data.events.forEach { e -> FilterChip(selected = v("previous") == e.id, onClick = { values["previous"] = e.id }, label = { Text(e.title) }) }
                        }
                    }
                }
                "task" -> {
                    Input("Taak", "title"); SelectPerson("Voor wie?"); Input("Deadline (optioneel, jjjj-mm-dd)", "date"); Input("Punten", "points", true); Input("Pictogram (emoji)", "emoji")
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Eerst oudercontrole", Modifier.weight(1f)); Switch(checked = v("parentCheck") == "true", onCheckedChange = { values["parentCheck"] = it.toString() }) }
                }
                "grocery" -> { Input("Artikel", "name"); Input("Hoeveelheid", "amount"); Input("Lijst / winkel", "list"); Input("Categorie", "category") }
                "recipe" -> { Input("Naam", "name"); Input("Bereidingstijd (min)", "minutes", true); Input("Personen", "persons", true); Input("Bronlink (optioneel)", "url"); Input("Ingrediënten: één per regel", "ingredients", multiple = true); Input("Stappen: één per regel", "steps", multiple = true) }
                "meal" -> {
                    Input("Maaltijd", "title"); Input("Datum (jjjj-mm-dd)", "date")
                    ChoiceRow(listOf("Ontbijt", "Lunch", "Diner"), v("slot")) { values["slot"] = it }
                    Text("Recept koppelen", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        FilterChip(selected = v("recipe").isEmpty(), onClick = { values["recipe"] = "" }, label = { Text("Losse maaltijd") })
                        data.recipes.forEach { r -> FilterChip(selected = v("recipe") == r.id, onClick = { values["recipe"] = r.id; values["title"] = r.name }, label = { Text(r.name) }) }
                    }
                }
                "reward" -> { Input("Beloning", "title"); Input("Benodigde punten", "points", true); Input("Pictogram (emoji)", "emoji") }
                "person" -> {
                    Input("Voornaam", "name"); Input("Pictogram (emoji)", "emoji")
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Kinderprofiel", Modifier.weight(1f)); Switch(checked = v("child") == "true", onCheckedChange = { values["child"] = it.toString() }) }
                }
                "residence" -> {
                    SelectPerson("Kind", childrenOnly = true); Input("Startdatum (jjjj-mm-dd)", "date")
                    Text("Herhaling", fontSize = 12.sp)
                    ChoiceRow(listOf("1", "2", "3", "4"), v("weeks")) { selected ->
                        values["weeks"] = selected
                        residenceDays = List(selected.toInt() * 7) { i -> residenceDays.getOrNull(i) ?: parents.first() }
                    }
                    Input("Wisseltijd (uu:mm)", "switchTime"); Input("Wissellocatie", "place")
                    Text("Tik op een dag om het huishouden te wijzigen.", fontSize = 12.sp)
                    val start = runCatching { LocalDate.parse(v("date")) }.getOrElse { LocalDate.now() }
                    residenceDays.chunked(7).forEachIndexed { week, days ->
                        Text("Week " + (week + 1), fontWeight = FontWeight.Bold)
                        days.forEachIndexed { index, household ->
                            val i = week * 7 + index
                            TextButton(onClick = {
                                val next = (parents.indexOf(household) + 1).mod(parents.size)
                                residenceDays = residenceDays.mapIndexed { n, old -> if(n == i) parents[next] else old }
                            }) { Text(fmtDay(start.plusDays(i.toLong())) + " · " + household) }
                        }
                    }
                    Text("Dit wordt als concept opgeslagen. Uitzonderingen en akkoord door een tweede account worden met de online backend toegevoegd.", fontSize = 11.sp)
                }
            }
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if(request.id.isNotEmpty() && request.type != "person") TextButton(onClick = { deleteConfirm = true }) { Text("Verwijderen", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val id = request.id.ifEmpty { newId() }
            val result = when(request.type) {
                "event", "proposal" -> {
                    require(v("title").isNotBlank()) { "Vul een naam in" }
                    val e = Event(id = if(request.type == "event") id else newId(), title = v("title"), date = v("date"), time = v("time"), end = v("end"), person = v("person"), place = v("place"), note = v("note"), emoji = v("emoji").ifEmpty { "📅" }, repeat = v("repeat"), until = v("until"), private = v("private") == "true", relatedChild = v("relatedChild"), dropOff = v("dropOff"), pickUp = v("pickUp"))
                    LocalDate.parse(e.date); LocalTime.parse(e.time); LocalTime.parse(e.end); require(e.end > e.time) { "Eindtijd moet na begintijd liggen" }
                    if(request.type == "event") data.copy(events = data.events.upsert(e) { it.id })
                    else {
                        val p = Proposal(event = e, oldEvent = v("previous"))
                        data.copy(proposals = data.proposals + p, messages = data.messages + Message(text = "Voorstel: " + e.title, at = java.time.Instant.now().toString(), proposal = p.id))
                    }
                }
                "task" -> {
                    require(v("title").isNotBlank()) { "Vul een taak in" }
                    if(v("date").isNotEmpty()) LocalDate.parse(v("date"))
                    val t = Task(id, v("title"), v("person"), v("date"), v("points").toIntOrNull() ?: 0, v("parentCheck") == "true", oldTask?.status ?: "open", v("emoji").ifEmpty { "🎒" })
                    data.copy(tasks = data.tasks.upsert(t) { it.id })
                }
                "grocery" -> {
                    require(v("name").isNotBlank()) { "Vul een artikel in" }
                    val g = Grocery(id, v("name"), v("amount"), v("list").ifEmpty { "Boodschappen" }, v("category").ifEmpty { "Overig" }, oldGrocery?.done ?: false)
                    data.copy(groceries = data.groceries.upsert(g) { it.id })
                }
                "recipe" -> {
                    require(v("name").isNotBlank()) { "Vul een receptnaam in" }
                    require(v("url").isEmpty() || v("url").startsWith("https://")) { "Gebruik een https-bronlink" }
                    val r = Recipe(id, v("name"), (v("persons").toIntOrNull() ?: 4).coerceIn(1, 100), (v("minutes").toIntOrNull() ?: 30).coerceIn(1, 1000), v("ingredients").lines().filter { it.isNotBlank() }, v("steps").lines().filter { it.isNotBlank() }, v("url"))
                    data.copy(recipes = data.recipes.upsert(r) { it.id })
                }
                "meal" -> {
                    require(v("title").isNotBlank()) { "Vul een maaltijd in" }; LocalDate.parse(v("date"))
                    val m = Meal(id, v("date"), v("slot"), v("title"), v("recipe"))
                    data.copy(meals = data.meals.filterNot { it.date == m.date && it.slot == m.slot } + m)
                }
                "reward" -> {
                    require(v("title").isNotBlank()) { "Vul een beloning in" }
                    val r = Reward(id, v("title"), (v("points").toIntOrNull() ?: 10), v("emoji").ifEmpty { "🎁" })
                    data.copy(rewards = data.rewards.upsert(r) { it.id })
                }
                "person" -> {
                    require(v("name").isNotBlank()) { "Vul een naam in" }
                    val p = Person(id, v("name"), v("emoji").ifEmpty { "🙂" }, oldPerson?.color ?: 0xFF9ACBB8, v("child") == "true")
                    data.copy(people = data.people.upsert(p) { it.id })
                }
                "residence" -> {
                    require(v("person").isNotEmpty()) { "Kies een kind" }; LocalDate.parse(v("date")); LocalTime.parse(v("switchTime"))
                    val r = Residence(id, v("person"), v("date"), v("weeks").toInt(), residenceDays, v("switchTime"), v("place"), "concept")
                    data.copy(residence = data.residence.upsert(r) { it.id })
                }
                else -> data
            }
            PlannerRules.validate(result); result
        }.onSuccess(onSaved).onFailure { error = it.message ?: "Controleer je invoer" }
    }) { Text("Bewaren") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } })
    if(deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("Verwijderen?") }, text = { Text("Dit item wordt uit de lokale voorbeeldplanner verwijderd.") }, confirmButton = { TextButton(onClick = {
        val next = when(request.type) {
            "event" -> data.copy(events = data.events.filterNot { it.id == request.id })
            "task" -> data.copy(tasks = data.tasks.filterNot { it.id == request.id })
            "grocery" -> data.copy(groceries = data.groceries.filterNot { it.id == request.id })
            "recipe" -> data.copy(recipes = data.recipes.filterNot { it.id == request.id }, meals = data.meals.map { if(it.recipe == request.id) it.copy(recipe = "") else it })
            "meal" -> data.copy(meals = data.meals.filterNot { it.id == request.id })
            "reward" -> data.copy(rewards = data.rewards.filterNot { it.id == request.id })
            "residence" -> data.copy(residence = data.residence.filterNot { it.id == request.id })
            else -> data
        }
        runCatching { PlannerRules.validate(next) }.onSuccess { deleteConfirm = false; onSaved(next) }.onFailure { onError(it.message ?: "Deze wijziging kan niet worden bewaard"); deleteConfirm = false }
    }) { Text("Verwijderen") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Annuleren") } })
}

@Composable private fun StaplesDialog(data: PlannerData, list: String, onDismiss: () -> Unit, onSave: (PlannerData) -> Unit) {
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var staples by remember { mutableStateOf(data.staples) }
    var newItem by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Vaste boodschappen") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vink aan wat je deze keer nodig hebt.")
            staples.forEach { name -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = selected[name] == true, onCheckedChange = { selected[name] = it }); Text(name) } }
            OutlinedTextField(newItem, { newItem = it.take(100) }, label = { Text("Nieuw vast artikel") }, trailingIcon = { IconButton(onClick = { if(newItem.isNotBlank()) { staples = (staples + newItem.trim()).distinct(); newItem = "" } }) { Icon(Icons.Outlined.Add, "Vast artikel toevoegen") } })
        }
    }, confirmButton = { TextButton(onClick = {
        val additions = staples.filter { selected[it] == true && data.groceries.none { g -> !g.done && g.list == list && g.name.equals(it, true) } }.map { Grocery(name = it, list = list) }
        onSave(data.copy(staples = staples, groceries = data.groceries + additions))
    }) { Text("Op mijn lijst") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } })
}

private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while(output.size() < maximum) {
        val count = input.read(buffer, 0, minOf(buffer.size, maximum - output.size()))
        if(count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
