package nl.yshott.app

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.YearMonth

class PlannerRulesTest {
    @Test fun monthHasMondayFirstAndAllDaysExactlyOnce() {
        val month = YearMonth.of(2026, 10)
        val cells = PlannerRules.monthCells(month)
        assertEquals(LocalDate.of(2026, 9, 28), cells.first())
        assertEquals(35, cells.size)
        assertEquals((1..31).toList(), cells.filter { YearMonth.from(it) == month }.map { it.dayOfMonth })
    }
    @Test fun weeklyRepeatCannotGoBeforeStartOrPastEnd() {
        val e = Event(title = "Les", date = "2026-10-12", repeat = "weekly", until = "2026-10-26")
        assertTrue(PlannerRules.occurs(e, LocalDate.parse("2026-10-19")))
        assertFalse(PlannerRules.occurs(e, LocalDate.parse("2026-10-05")))
        assertFalse(PlannerRules.occurs(e, LocalDate.parse("2026-11-02")))
        assertFalse(PlannerRules.occurs(e, LocalDate.parse("2026-10-20")))
    }
    @Test fun childCompletionWaitsForParentAndPointsAreCreditedOnlyOnce() {
        val task = Task(id = "task", title = "Tas", person = "child", points = 10, parentCheck = true)
        var data = PlannerData(tasks = listOf(task))
        data = PlannerRules.completeTask(data, "task", true, "child")
        assertEquals(0, PlannerRules.points(data, "child"))
        data = PlannerRules.approveTask(data, "task")
        data = PlannerRules.approveTask(data, "task")
        data = PlannerRules.completeTask(data, "task", true, "child")
        assertEquals(10, PlannerRules.points(data, "child"))
    }
    @Test(expected = IllegalArgumentException::class) fun childCannotCompleteAnotherPersonsTask() {
        PlannerRules.completeTask(PlannerData(tasks = listOf(Task(id = "t", title = "X", person = "other"))), "t", true, "child")
    }
    @Test fun approvedRewardCannotBeChargedTwice() {
        val p = Person(id = "p", name = "Kind")
        var data = PlannerData(people = listOf(p), tasks = listOf(Task(title = "Klus", person = p.id, points = 30, status = "done")))
        data = PlannerRules.requestReward(data, Reward(id = "r", title = "IJs", points = 20), p.id)
        val request = data.rewardRequests.single().id
        data = PlannerRules.approveReward(data, request)
        data = PlannerRules.approveReward(data, request)
        assertEquals(10, PlannerRules.points(data, p.id))
    }
    @Test fun approvingProposalUpdatesExistingEventWithoutDuplicates() {
        val old = Event(id = "old", title = "Wissel", date = "2026-10-14")
        val updated = old.copy(time = "18:30", end = "19:00")
        var data = PlannerData(events = listOf(old), proposals = listOf(Proposal(id = "p", event = updated, oldEvent = old.id)))
        data = PlannerRules.approveProposal(data, "p")
        data = PlannerRules.approveProposal(data, "p")
        assertEquals(1, data.events.size)
        assertEquals("18:30", data.events.single().time)
    }
    @Test fun coParentDefaultNeverExposesTitlePlaceOrNotes() {
        val e = Event(title = "Persoonlijke afspraak", date = "2026-10-12", place = "Adres", note = "Privé", private = false)
        val visible = PlannerRules.visibleToCoParent(e, false)
        assertEquals("Bezet", visible.title); assertEquals("", visible.place); assertEquals("", visible.note)
        assertEquals(e, PlannerRules.visibleToCoParent(e, true))
    }
    @Test fun inactiveResidenceProposalDoesNotBecomeActualSchedule() {
        val r = Residence(child = "child", starts = "2026-10-12")
        assertNull(PlannerRules.residenceAt(r, LocalDate.parse("2026-10-13")))
        assertEquals("Papa", PlannerRules.residenceAt(r.copy(status = "active"), LocalDate.parse("2026-10-13")))
    }
    @Test fun calendarExportEscapesNotesAndUsesUtcTimes() {
        val text = CalendarExport.ics(listOf(Event(title = "Les, afspraak", date = "2026-10-12", time = "15:00", end = "16:00", note = "a\nb")))
        assertTrue(text.contains("DTSTART:20261012T130000Z"))
        assertTrue(text.contains("SUMMARY:Les\\, afspraak"))
        assertTrue(text.contains("DESCRIPTION:a\\nb"))
    }
    @Test fun examplePlannerPassesValidation() {
        PlannerRules.validate(exampleData())
    }
    @Test fun invalidRepeatEndNeverCrashesCalendar() {
        val event = Event(title = "Les", date = "2026-10-12", repeat = "weekly", until = "geen datum")
        assertFalse(PlannerRules.occurs(event, LocalDate.parse("2026-10-19")))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRepeatEndIsRejectedBeforeSaving() {
        PlannerRules.validate(PlannerData(events = listOf(Event(title = "Les", date = "2026-10-12", until = "2026-02-30"))))
    }
    @Test(expected = IllegalArgumentException::class) fun repeatCannotEndBeforeItStarts() {
        PlannerRules.validate(PlannerData(events = listOf(Event(title = "Les", date = "2026-10-12", until = "2026-10-11"))))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedProposalCannotEnterPlanner() {
        PlannerRules.validate(PlannerData(proposals = listOf(Proposal(event = Event(title = "Voorstel", date = "geen datum")))))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedMealBackupIsRejected() {
        PlannerRules.validate(PlannerData(meals = listOf(Meal(date = "2026-02-30", title = "Pasta"))))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedResidenceBackupIsRejected() {
        PlannerRules.validate(PlannerData(residence = listOf(Residence(child = "child", starts = "geen datum"))))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateTaskIdentifiersAreRejected() {
        val task = Task(id = "same", title = "Tas")
        PlannerRules.validate(PlannerData(tasks = listOf(task, task.copy(title = "Kamer"))))
    }
    @Test(expected = IllegalArgumentException::class) fun negativeRewardChargeIsRejected() {
        PlannerRules.validate(PlannerData(rewardRequests = listOf(RewardRequest(person = "child", reward = "r", title = "IJs", points = -10))))
    }
}
