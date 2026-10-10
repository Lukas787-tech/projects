package com.lukas.jarvis.ui.rooms

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.lukas.jarvis.auto.Routine
import com.lukas.jarvis.brief.DayBrief
import com.lukas.jarvis.control.Appointment
import com.lukas.jarvis.core.Countdown
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.ListBook
import com.lukas.jarvis.data.ListItem
import com.lukas.jarvis.data.Memory
import com.lukas.jarvis.data.NamedList
import com.lukas.jarvis.data.Task
import com.lukas.jarvis.data.Tracker
import com.lukas.jarvis.data.TrackerStatus
import com.lukas.jarvis.maps.GeoPoint
import com.lukas.jarvis.maps.SavedPlace
import com.lukas.jarvis.ui.theme.MochiTheme
import com.lukas.jarvis.web.Forecast
import com.lukas.jarvis.web.Headline
import com.lukas.jarvis.web.WeatherDay
import com.lukas.jarvis.web.WeatherNow
import java.time.LocalDate

/** Believable rooms for previews and screenshots: a day, a library, a person's things. */
object RoomSamples {
    private const val NOW = 1_760_000_000_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    val tasks = listOf(
        Task(id = 1, title = "Call the landlord about the heating", dueAt = NOW + 2 * HOUR),
        Task(id = 2, title = "Pick up the parcel", dueAt = NOW + 5 * HOUR),
        Task(id = 3, title = "Water the plants", repeatRule = "weekly"),
        Task(id = 4, title = "Book the dentist", done = true)
    )

    val trackers = listOf(
        TrackerStatus(Tracker(id = 1, name = "groceries", label = "Groceries", budget = 300.0), 182.40, 0.0, NOW - 9 * 24 * HOUR, 14, 1200.0, 0.0),
        TrackerStatus(Tracker(id = 2, name = "wallet", label = "Wallet", startingBalance = 120.0), 0.0, 0.0, NOW, 3, 43.5, 0.0),
        TrackerStatus(Tracker(id = 3, name = "water", label = "Water", kind = Tracker.KIND_COUNT, unit = "glasses", period = Tracker.PERIOD_DAILY), 5.0, 0.0, NOW, 40, 180.0, 0.0),
        TrackerStatus(Tracker(id = 4, name = "run", label = "Run", kind = Tracker.KIND_COUNT, unit = "runs", period = Tracker.PERIOD_WEEKLY), 1.0, 0.0, NOW, 12, 12.0, 0.0)
    )

    val streaks = mapOf(
        3L to com.lukas.jarvis.data.Streak(current = 6, best = 6, today = true, week = listOf(false, true, true, true, true, true, true)),
        4L to com.lukas.jarvis.data.Streak(current = 0, best = 3, today = false, week = listOf(true, false, false, true, false, false, false))
    )

    val brief = DayBrief(
        greeting = "Good afternoon, Lukas",
        dateLine = "Thursday, 9 October",
        forecast = Forecast(
            now = WeatherNow("Berlin", 14.0, 12.0, "Light rain", 11.0, 78, 60, isDay = true),
            days = listOf(
                WeatherDay("Today", 15.0, 9.0, "Light rain", 60, "07:31", "18:52"),
                WeatherDay("Fri", 17.0, 8.0, "Partly cloudy", 10),
                WeatherDay("Sat", 19.0, 10.0, "Sunny", 0)
            ),
            rainFrom = "17:00"
        ),
        placeName = "Berlin",
        dueToday = tasks.take(2),
        overdue = emptyList(),
        appointments = listOf(
            Appointment("Lunch with Anna", NOW + HOUR, NOW + 2 * HOUR, "Café Mitte", false),
            Appointment("Yoga", NOW + 6 * HOUR, NOW + 7 * HOUR, null, false)
        ),
        trackers = trackers,
        battery = "64%",
        connection = "Wi-Fi",
        headlines = listOf(
            Headline("City opens three new cycle lanes", "Tagesspiegel", "2h"),
            Headline("Rain to clear by the weekend", "rbb24", "4h")
        )
    )

    val routines = listOf(
        Routine("morning", listOf("How does my day look?", "What's the weather?", "Play the radio"), time = "07:00"),
        Routine("bedtime", listOf("Set an alarm for 7", "Do not disturb until 7"))
    )

    val places = listOf(
        SavedPlace("home", GeoPoint(52.52, 13.40)),
        SavedPlace("car", GeoPoint(52.51, 13.39)),
        SavedPlace("gym", GeoPoint(52.50, 13.42))
    )

    val countdowns = listOf(
        Countdown(1, "Anna", LocalDate.now().plusDays(12), yearly = true, birthday = true),
        Countdown(2, "Lisbon trip", LocalDate.now().plusDays(40))
    )

    val memories = listOf(
        Memory(id = 1, content = "Anna is vegetarian and loves Thai food", pinned = true),
        Memory(id = 2, content = "The bike lock code is in the blue notebook", kind = Memory.KIND_FACT),
        Memory(id = 3, content = "Prefers window seats on trains", kind = Memory.KIND_PREFERENCE),
        Memory(id = 4, content = "Ideas for the garden: tomatoes, mint, a bench under the apple tree", kind = Memory.KIND_NOTE),
        Memory(id = 5, content = "A good day — the long walk by the lake and dinner with Anna", kind = Memory.KIND_JOURNAL)
    )

    /** A night's upkeep: one repeat put away. */
    val tidied = listOf(
        com.lukas.jarvis.data.Tidied(6, com.lukas.jarvis.data.Upkeep.Reason.Repeat, keptId = 2, at = NOW - HOUR) to
            Memory(id = 6, content = "Bike lock code: in the blue notebook", archived = true)
    )

    /** The rent, logging itself. */
    val repeats = listOf(
        com.lukas.jarvis.data.Recurring(id = 1, trackerId = 1, amount = 12.99, note = "Veg box", anchor = NOW - 3 * DAY, logged = 1, nextAt = NOW + 27 * DAY)
    )

    val lists = ListBook(
        listOf(
            NamedList("shopping", listOf(ListItem("oat milk"), ListItem("tomatoes"), ListItem("coffee beans", done = true), ListItem("bread"))),
            NamedList("packing", listOf(ListItem("passport"), ListItem("charger")))
        )
    )

    val entries = listOf(
        Entry(id = 1, trackerId = 1, amount = 23.80, direction = Entry.DIR_OUT, note = "Market", occurredAt = NOW - HOUR),
        Entry(id = 2, trackerId = 1, amount = 7.40, direction = Entry.DIR_OUT, note = "Café Mitte", occurredAt = NOW - 26 * HOUR)
    )

    val todayActions = TodayActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    val libraryActions = LibraryActions(
        {}, {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}, {}, {}, {},
        { _, _, _, _ -> }, {}, { _, _, _, _ -> }, {}, {}, {}
    )
}

@Composable
fun TodaySample(loading: Boolean = false) = TodayRoom(
    name = "Mochi",
    brief = if (loading) null else RoomSamples.brief,
    loading = loading,
    trackers = RoomSamples.trackers,
    routines = RoomSamples.routines,
    savedPlaces = RoomSamples.places,
    countdowns = RoomSamples.countdowns,
    actions = RoomSamples.todayActions,
    streaks = RoomSamples.streaks
)

@Composable
fun LibrarySample(shelf: Shelf) = LibraryRoom(
    shelf = shelf,
    memories = RoomSamples.memories,
    lists = RoomSamples.lists,
    trackers = RoomSamples.trackers,
    entries = RoomSamples.entries,
    tasks = RoomSamples.tasks,
    placeReminders = emptyList(),
    actions = RoomSamples.libraryActions,
    streaks = RoomSamples.streaks,
    repeats = RoomSamples.repeats,
    tidied = RoomSamples.tidied
)

@Preview(name = "Today", widthDp = 411, heightDp = 1400)
@Composable
private fun TodayPreview() = MochiTheme(themeMode = "light") { TodaySample() }

@Preview(name = "Library: memory", widthDp = 411, heightDp = 891)
@Composable
private fun LibraryPreview() = MochiTheme(themeMode = "light") { LibrarySample(Shelf.Memory) }

/** A You room with a pool, a profile and a short history of things done. */
fun youSampleState(settings: com.lukas.jarvis.core.Settings = com.lukas.jarvis.core.Settings(userName = "Lukas")): YouState = YouState(
    settings = settings,
    version = "Mochi 6.0 · build 260",
    pool = listOf(
        com.lukas.jarvis.llm.PoolEntry(
            com.lukas.jarvis.llm.Endpoint(id = "a", providerId = com.lukas.jarvis.llm.Providers.LLM7, baseUrl = "", apiKey = "", model = "default"),
            com.lukas.jarvis.llm.Health(successes = 42, latencyMs = 1300)
        ),
        com.lukas.jarvis.llm.PoolEntry(
            com.lukas.jarvis.llm.Endpoint(id = "b", providerId = com.lukas.jarvis.llm.Providers.GROQ, baseUrl = "", apiKey = "k", model = "llama-3.3-70b-versatile"),
            com.lukas.jarvis.llm.Health(successes = 7, latencyMs = 600)
        )
    ),
    poolSummary = "2 models, 2 ready",
    lastUsedEndpoint = "LLM7 · default",
    profiles = listOf(com.lukas.jarvis.core.Profile.of("Night", settings).copy(autoAt = "22:00")),
    access = SystemAccess(canText = true, canCall = true),
    actionLog = listOf(
        com.lukas.jarvis.data.ActionRecord(1, "send_message", "Text Anna", "Running ten minutes late", "Outward", "Sent", "tap", 1_760_000_000_000L),
        com.lukas.jarvis.data.ActionRecord(2, "delete_task", "Delete \"Water the plants\"", "", "Sensitive", "Cancelled", "cancelled", 1_759_990_000_000L)
    )
)

@Composable
fun YouSample(tab: YouTab) = YouRoom(youSampleState(), tab, YouActions(onBack = {}, onTab = {}, onUpdate = {}))
