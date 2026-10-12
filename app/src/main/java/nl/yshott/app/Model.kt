package nl.yshott.app

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable data class Person(val id: String = newId(), val name: String, val emoji: String = "🙂", val color: Long = 0xFF9ACBB8, val child: Boolean = false)
@Serializable data class Event(val id: String = newId(), val title: String, val date: String, val time: String = "09:00", val end: String = "10:00", val person: String = "", val place: String = "", val note: String = "", val emoji: String = "📅", val repeat: String = "none", val until: String = "", val private: Boolean = false, val relatedChild: String = "", val dropOff: String = "", val pickUp: String = "")
@Serializable data class Task(val id: String = newId(), val title: String, val person: String = "", val date: String = "", val points: Int = 0, val parentCheck: Boolean = true, val status: String = "open", val emoji: String = "🎒")
@Serializable data class Grocery(val id: String = newId(), val name: String, val amount: String = "", val list: String = "Boodschappen", val category: String = "Overig", val done: Boolean = false)
@Serializable data class Recipe(val id: String = newId(), val name: String, val persons: Int = 4, val minutes: Int = 30, val ingredients: List<String> = emptyList(), val steps: List<String> = emptyList(), val url: String = "")
@Serializable data class Meal(val id: String = newId(), val date: String, val slot: String = "Diner", val title: String, val recipe: String = "")
@Serializable data class Reward(val id: String = newId(), val title: String, val points: Int = 10, val emoji: String = "🎁")
@Serializable data class RewardRequest(val id: String = newId(), val person: String, val reward: String, val title: String, val points: Int, val status: String = "pending")
@Serializable data class Message(val id: String = newId(), val person: String = "", val text: String, val at: String, val proposal: String = "")
@Serializable data class Proposal(val id: String = newId(), val event: Event, val oldEvent: String = "", val status: String = "pending")
@Serializable data class Residence(val id: String = newId(), val child: String, val starts: String, val repeatsWeeks: Int = 2, val days: List<String> = List(14) { "Papa" }, val switchTime: String = "18:00", val location: String = "", val status: String = "concept")
@Serializable data class Settings(val dark: Boolean = false, val homeCards: List<String> = listOf("residence", "agenda", "tasks", "meal"), val quietFrom: String = "21:00", val quietTo: String = "07:00", val notifications: Boolean = true)
@Serializable data class PlannerData(
    val people: List<Person> = emptyList(),
    val events: List<Event> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val groceries: List<Grocery> = emptyList(),
    val staples: List<String> = listOf("Melk", "Brood", "Fruit", "Koffie"),
    val recipes: List<Recipe> = emptyList(),
    val meals: List<Meal> = emptyList(),
    val rewards: List<Reward> = emptyList(),
    val rewardRequests: List<RewardRequest> = emptyList(),
    val messages: List<Message> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val residence: List<Residence> = emptyList(),
    val settings: Settings = Settings()
)

object PlannerRules {
    fun occurs(event: Event, date: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(event.date) }.getOrNull() ?: return false
        if (date < start) return false
        if (event.until.isNotEmpty() && date > LocalDate.parse(event.until)) return false
        if (date == start) return true
        return when (event.repeat) {
            "daily" -> true
            "weekly" -> date.dayOfWeek == start.dayOfWeek
            "monthly" -> date.dayOfMonth == start.dayOfMonth
            else -> false
        }
    }
    fun monthCells(month: YearMonth): List<LocalDate> {
        val first = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
        val count = (((month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth()) + 6) / 7) * 7
        return (0 until count).map { first.plusDays(it.toLong()) }
    }
    fun points(data: PlannerData, person: String): Int =
        data.tasks.filter { it.person == person && it.status == "done" }.sumOf { it.points } -
        data.rewardRequests.filter { it.person == person && it.status == "approved" }.sumOf { it.points }
    fun completeTask(data: PlannerData, id: String, childMode: Boolean, activePerson: String): PlannerData {
        val t = data.tasks.firstOrNull { it.id == id } ?: error("Taak bestaat niet")
        require(!childMode || t.person == activePerson) { "Dit is niet jouw klusje" }
        if (t.status != "open") return data
        return data.copy(tasks = data.tasks.map {
            if (it.id == id) it.copy(status = if (childMode && it.parentCheck) "pending" else "done") else it
        })
    }
    fun approveTask(data: PlannerData, id: String): PlannerData =
        data.copy(tasks = data.tasks.map { if (it.id == id && it.status == "pending") it.copy(status = "done") else it })
    fun requestReward(data: PlannerData, reward: Reward, person: String): PlannerData {
        require(points(data, person) >= reward.points) { "Je hebt nog niet genoeg punten" }
        require(data.rewardRequests.none { it.reward == reward.id && it.person == person && it.status == "pending" }) { "Deze aanvraag wacht al op akkoord" }
        return data.copy(rewardRequests = data.rewardRequests + RewardRequest(person = person, reward = reward.id, title = reward.title, points = reward.points))
    }
    fun approveReward(data: PlannerData, id: String): PlannerData {
        val r = data.rewardRequests.firstOrNull { it.id == id } ?: error("Aanvraag bestaat niet")
        if (r.status != "pending") return data
        require(points(data, r.person) >= r.points) { "Er zijn niet genoeg punten over" }
        return data.copy(rewardRequests = data.rewardRequests.map { if (it.id == id) it.copy(status = "approved") else it })
    }
    fun approveProposal(data: PlannerData, id: String): PlannerData {
        val p = data.proposals.firstOrNull { it.id == id } ?: error("Voorstel bestaat niet")
        if (p.status != "pending") return data
        return data.copy(
            proposals = data.proposals.map { if (it.id == id) it.copy(status = "approved") else it },
            events = data.events.filterNot { it.id == p.oldEvent || it.id == p.event.id } + p.event
        )
    }
    fun residenceAt(r: Residence, day: LocalDate): String? {
        if (r.status != "active" || r.days.isEmpty()) return null
        val distance = ChronoUnit.DAYS.between(LocalDate.parse(r.starts), day)
        if (distance < 0) return null
        return r.days[(distance % r.days.size).toInt()]
    }
    fun visibleToCoParent(e: Event, shareFullDetails: Boolean): Event =
        if (shareFullDetails) e else e.copy(title = "Bezet", note = "", place = "", person = "", emoji = "🔒")
    fun validate(data: PlannerData) {
        require(data.people.map { it.id }.distinct().size == data.people.size)
        require(data.tasks.all { it.points >= 0 })
        require(data.rewards.all { it.points > 0 })
        require(data.events.all {
            runCatching { LocalDate.parse(it.date); LocalTime.parse(it.time); LocalTime.parse(it.end) }.isSuccess &&
            it.end > it.time
        }) { "Controleer de datum en begin-/eindtijd" }
        require(data.residence.all { it.repeatsWeeks in 1..8 && it.days.size == it.repeatsWeeks * 7 })
        require(data.people.all { points(data, it.id) >= 0 }) { "Deze wijziging zou al bestede punten verwijderen" }
    }
}

fun exampleData(): PlannerData {
    val now = LocalDate.now()
    val child = Person(id = "mila", name = "Mila", emoji = "👧", child = true, color = 0xFFB6A0E2)
    val dad = Person(id = "papa", name = "Papa", emoji = "👨", color = 0xFF9ACBB8)
    val mother = Person(id = "mama", name = "Mama", emoji = "👩", color = 0xFFF2B99A)
    val pasta = Recipe(id = "pasta", name = "Pasta met groenten", ingredients = listOf("400 g pasta", "2 paprika's", "1 courgette", "400 g tomatenblokjes"), steps = listOf("Kook de pasta volgens de verpakking.", "Snijd de paprika en courgette.", "Bak de groenten en voeg tomatenblokjes toe.", "Meng met de pasta en serveer."))
    return PlannerData(
        people = listOf(dad, mother, child),
        events = listOf(Event(title = "School ophalen", date = now.toString(), time = "15:00", end = "15:30", person = dad.id, emoji = "🏫", relatedChild = child.id, pickUp = dad.id), Event(title = "Zwemles", date = now.toString(), time = "17:00", end = "18:00", person = child.id, emoji = "🏊", note = "Papa brengt en haalt op")),
        tasks = listOf(Task(title = "Sporttas klaarzetten", person = child.id, date = now.toString(), points = 5, emoji = "🎒"), Task(title = "Kamer opruimen", person = child.id, points = 10, emoji = "🧺")),
        recipes = listOf(pasta),
        meals = listOf(Meal(date = now.toString(), title = pasta.name, recipe = pasta.id)),
        rewards = listOf(Reward(title = "Samen een spel spelen", points = 10, emoji = "🎲"), Reward(title = "Een ijsje kiezen", points = 20, emoji = "🍦")),
        residence = listOf(Residence(child = child.id, starts = now.toString(), days = List(14) { if (it < 7) "Papa" else "Mama" }, status = "active"))
    )
}
