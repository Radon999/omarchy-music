package org.omarchy.music

import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** The DJ, like Spotify's, as on the desktop: what you play most, songs you haven't heard
 *  in a while and new ones, in sets of a few songs, each begun with a line from the DJ.
 *  Your history is Last.fm (when it's connected) and what you play here; the new songs
 *  come from Tidal's and YouTube Music's radios. "Switch it up" starts another kind of
 *  set, and you can ask for one: a mood, a decade, a genre or an artist. Building a set
 *  blocks (run it in [Music.bg]); [Music] plays them. */
object Dj {
    const val SET = 6   // songs in a set
    val ORDER = listOf("repeat", "fresh", "throwback", "deep", "fresh", "genre", "like")
    /** Playing from songs you picked (a playlist, a folder, an album): sets of only those. */
    val FROM_ORDER = listOf("loved", "unplayed", "artist", "throwback", "mix")
    val KINDS = mapOf("repeat" to "On repeat", "fresh" to "New for you", "throwback" to "Throwback", "deep" to "Deep cuts",
        "genre" to "Your genre", "like" to "More like this", "ask" to "You asked", "loved" to "Your favourites",
        "unplayed" to "Not played yet", "artist" to "One artist", "mix" to "Mix")
    /** Words in a request and the Last.fm tags they mean. */
    val MOODS = mapOf(
        "chill" to listOf("chill", "chillout"), "chilled" to listOf("chill", "chillout"), "relax" to listOf("chill", "relaxing"),
        "relaxing" to listOf("relaxing", "chill"), "calm" to listOf("calm", "chill"), "study" to listOf("study", "chill"),
        "studying" to listOf("study", "chill"), "focus" to listOf("focus", "instrumental"), "work" to listOf("focus", "chill"),
        "sleep" to listOf("sleep", "ambient"), "lofi" to listOf("lo-fi"), "sad" to listOf("sad", "melancholic"),
        "happy" to listOf("happy", "feel good"), "upbeat" to listOf("upbeat", "happy"), "hype" to listOf("energetic", "hype"),
        "energy" to listOf("energetic"), "workout" to listOf("workout", "energetic"), "gym" to listOf("workout", "energetic"),
        "running" to listOf("running", "workout"), "party" to listOf("party", "dance"), "dance" to listOf("dance"),
        "love" to listOf("love", "romantic"), "romantic" to listOf("romantic", "love"), "angry" to listOf("aggressive"),
        "rainy" to listOf("rainy day", "chill"), "summer" to listOf("summer"), "driving" to listOf("driving"),
        "drive" to listOf("driving"), "night" to listOf("night", "late night"), "morning" to listOf("morning"),
    )
    /** Tags that say nothing about how a song sounds. */
    private val JUNK_TAGS = setOf("seen live", "favorites", "favourites", "favorite", "favourite", "albums i own", "under 2000 listeners",
        "spotify", "youtube", "male vocalists", "female vocalists", "american", "british", "usa", "uk")
    private val FILLER = setOf("play", "some", "something", "songs", "song", "music", "me", "for", "a", "the", "please", "mix", "of",
        "like", "more", "vibes", "vibe", "tunes", "stuff", "give", "i", "want", "to", "listen", "put", "on", "and")
    private val WORD = Regex("[\\p{L}\\p{N}_'-]+")
    private val DECADE = Regex("(19|20)?\\d0s")

    /** The DJ's sets in the queue (a song's [Track.dj] is its number) and the one whose line was shown. */
    @Serializable
    data class Info(val kind: String, val line: String)

    /** What the DJ plays from: kind "all" (everything), "mine" (all your songs), "folder" (path, as
     *  "Music/Album/"), "playlist" (id, one of yours) or "songs" (an album's or a service playlist's). */
    @Serializable
    data class From(val kind: String, val id: String = "", val path: String = "", val name: String = "", val songs: List<Track> = emptyList())

    @Serializable
    data class State(val on: Boolean = false, val sets: Map<String, Info> = emptyMap(), val shown: String? = null, val from: From? = null)

    /** What the DJ screen shows: on, what it's doing, the kind of set playing and its line, what it plays from. */
    data class Shown(val on: Boolean = false, val busy: String = "", val kind: String = "", val line: String = "",
                     val kinds: Map<String, String> = emptyMap(), val from: String = "")

    data class Built(val kind: String, val songs: List<Track>, val line: String)

    // ---- the parts that don't need the app ----

    /** The Last.fm tags a request means: its mood words, its decade ("80s", "2010s"), else the
     *  request itself as a genre ("house", "k-pop"). */
    fun tags(text: String): List<String> {
        val every = WORD.findAll(text.lowercase()).map { it.value }.toMutableList()
        val out = LinkedHashSet<String>()
        for (w in every) if (w !in FILLER) {
            if (DECADE.matches(w)) out += w   // "80s", "1980s", "2010s"
            MOODS[w]?.let { out += it }
        }
        while (every.isNotEmpty() && every.first() in FILLER) every.removeAt(0)   // "play some drum and bass music": "drum and bass"
        while (every.isNotEmpty() && every.last() in FILLER) every.removeAt(every.size - 1)
        if (out.isEmpty() && every.isNotEmpty()) out += every.joinToString(" ")
        return out.toList()
    }

    /** n songs of pool at random, the ones nearer the top likelier, none twice and none in used
     *  (song keys of what the DJ played already). */
    fun pick(pool: List<Track>, n: Int, used: Set<Pair<String, String>>, rng: Random = Random.Default): List<Track> {
        val seen = HashSet(used)
        val out = ArrayList<Track>()
        val weighted = pool.mapIndexed { i, t -> Math.pow(rng.nextDouble(), 1 + 8.0 * i / maxOf(1, pool.size)) to t }
        for ((_, t) in weighted.sortedByDescending { it.first }) {
            if (!seen.add(songKey(t))) continue
            out += t
            if (out.size == n) break
        }
        return out
    }

    /** "A and B": the lead artists of tracks, each once. */
    fun names(tracks: List<Track>, n: Int = 2): String {
        val ns = tracks.map { it.artist.split(",")[0].trim() }.filter { it.isNotEmpty() }.distinct().take(n)
        return if (ns.size < 3) ns.joinToString(" and ") else ns.dropLast(1).joinToString(", ") + " and " + ns.last()
    }

    fun greeting(h: Int = LocalTime.now().hour) =
        if (h in 5..11) "Good morning" else if (h in 12..16) "Good afternoon" else if (h in 17..22) "Good evening" else "Hey, night owl"

    // ---- what you like ----
    private class Taste(val mine: Map<Pair<String, String>, Track>, val recent: List<Track>, val all: List<Track>,
                        val artists: List<String>, val last: Map<Pair<String, String>, Double>)

    private var taste: Taste? = null
    private var tasteAt = 0.0
    val used: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()   // song keys the DJ has played since the app started
    private var step = 0   // where in ORDER (or FROM_ORDER) the next set comes from
    @Volatile var scope: Pair<String, List<Track>>? = null   // (name, songs) the DJ plays from; null: everything
        private set

    /** Play only songs (named name: a playlist, a folder), or with null everything. */
    fun playFrom(name: String?, songs: List<Track>?) {
        scope = if (name != null && !songs.isNullOrEmpty()) name to songs else null
        step = 0
    }

    /** (blocking) Your history, asked of Last.fm at most every ten minutes. */
    @Synchronized private fun loadTaste(): Taste {
        taste?.let { if (now() - tasteAt < 600) return it }
        val mine = HashMap<Pair<String, String>, Track>()
        for (t in Music.plays.items.values.map { it.track } + Music.mySongs()) mine[songKey(t)] = t
        var recent = emptyList<Track>()
        var all = emptyList<Track>()
        var artists = emptyList<String>()
        if (LastFm.connected) runCatching {   // else what you played here
            fun songs(rows: List<LastFm.Top>) = rows.map { Music.fmSong(it.name, it.artist, it.image, mine).copy(plays = it.plays) }
            recent = songs(LastFm.top("track", 50, "1month"))
            all = songs(LastFm.top("track", 200, "overall"))
            artists = LastFm.top("artist", 20, "3month").map { it.name }
        }
        val rows = Music.plays.items.values.toList()
        val t = now()
        if (recent.isEmpty()) recent = rows.filter { t - it.last < 30 * 86400 }.sortedByDescending { it.plays }.take(50).map { it.track.copy(plays = it.plays) }
        if (all.isEmpty()) all = rows.sortedByDescending { it.plays }.take(200).map { it.track.copy(plays = it.plays) }
        if (artists.isEmpty()) artists = (recent + all).map { it.artist.split(",")[0].trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(20).map { it.key }
        return Taste(mine, recent, all, artists, rows.associate { songKey(it.track) to it.last }).also { taste = it; tasteAt = now() }
    }

    private fun known(taste: Taste) = taste.mine.keys + taste.all.map { songKey(it) } + taste.last.keys

    private fun radio(seed: Track, key: String): List<Track> {
        val src = Music.sources[key] ?: return emptyList()
        if (key == "tidal" && !src.signedIn) return emptyList()
        return runCatching { src.radio(seed) }.getOrDefault(emptyList())   // the other service, then
    }

    /** The services new songs come from: Tidal and YouTube Music both, when both are there. */
    private fun services() = listOfNotNull("tidal".takeIf { Music.sources["tidal"]?.signedIn == true }, "ytm")

    // ---- the kinds of set ----
    private fun setRepeat(taste: Taste, first: Boolean): Pair<List<Track>, String>? {
        val songs = pick(taste.recent.take(25), SET, used)
        if (songs.size < 3) return null
        return songs to if (first) "${greeting()}, I'm your DJ. Let's start with what you've had on repeat, like “${songs[0].title}” by ${names(songs.take(1))}."
            else "Back to what you've had on repeat lately: ${names(songs, 3)}."
    }

    private fun setThrowback(taste: Taste): Pair<List<Track>, String>? {
        val recent = taste.recent.map { songKey(it) }.toSet()
        val t = now()
        val pool = taste.all.filter { songKey(it) !in recent && t - (taste.last[songKey(it)] ?: 0.0) > 21 * 86400 }
        val songs = pick(pool.take(120), SET, used)
        if (songs.size < 3) return null
        val s = songs[0]
        return songs to if (s.plays > 1) "Throwback time. You've played “${s.title}” ${s.plays} times, but not for a while."
            else "Throwback time, starting with “${s.title}” by ${names(listOf(s))}."
    }

    private fun setFresh(taste: Taste): Pair<List<Track>, String>? {
        val seeds = pick(taste.recent.take(20).ifEmpty { taste.all.take(40) }, 2, emptySet())
        if (seeds.isEmpty()) return null
        val known = known(taste) + used
        val keys = services()
        val found = seeds.mapIndexed { i, seed ->
            val got = radio(seed, keys[i % keys.size]).ifEmpty { radio(seed, keys[(i + 1) % keys.size]) }
            got.filter { songKey(it) !in known }
        }
        val mixed = (0 until (found.maxOfOrNull { it.size } ?: 0)).flatMap { i -> found.mapNotNull { it.getOrNull(i) } }
        val songs = pick(mixed.take(30), SET, used)
        if (songs.size < 3) return null
        return songs to "Something new for you, picked from ${names(seeds)}. Let's see what you think."
    }

    private fun setDeep(taste: Taste): Pair<List<Track>, String>? {
        for (name in taste.artists.take(10).shuffled().take(3)) {
            val info = runCatching { Music.artistInfo(name, null) }.getOrNull() ?: continue   // the next artist
            val known = known(taste) + used
            val songs = info.top.filter { songKey(it) !in known }.take(SET)
            if (songs.size >= 3) return songs to "Deep cuts from ${info.name.ifEmpty { name }}: songs of theirs you haven't played yet."
        }
        return null
    }

    private fun setGenre(taste: Taste): Pair<List<Track>, String>? {
        if (LastFm.key.isEmpty()) return null
        val weight = HashMap<String, Int>()
        val lead = taste.artists.take(3)
        for (name in lead) runCatching {   // else the other artists' tags
            for ((tag, n) in LastFm.artistTags(name).take(6)) if (tag.lowercase() !in JUNK_TAGS) weight.merge(tag.lowercase(), n, Int::plus)
        }
        if (weight.isEmpty()) return null
        val tag = weight.entries.sortedByDescending { it.value }.take(3).random().key
        val songs = tagSongs(tag, taste)
        if (songs.size < 3) return null
        return songs to "Some $tag, since you've been playing ${lead[0]} so much."
    }

    /** Radio of what's playing (or, when no service has one of it, of a song you play a lot). */
    private fun setLike(taste: Taste): Pair<List<Track>, String>? {
        val seeds = listOfNotNull(Music.current()) + taste.recent.take(5)
        for (seed in seeds.take(4)) for (key in services()) {
            val songs = pick(radio(seed, key).take(25).filter { songKey(it) != songKey(seed) }, SET, used)
            if (songs.size >= 3) return songs to "More like “${seed.title}” by ${names(listOf(seed))}."
        }
        return null
    }

    /** Songs Last.fm files under tag, the ones by artists you play first. */
    private fun tagSongs(tag: String, taste: Taste): List<Track> {
        if (LastFm.key.isEmpty()) return emptyList()
        val rows = runCatching { LastFm.call("tag.getTopTracks", params = mapOf("tag" to tag, "limit" to 100)).path("tracks", "track").list }
            .getOrNull() ?: return emptyList()
        val yours = taste.artists.map { norm(it) }.toSet()
        val pool = rows.mapNotNull { x ->
            val name = x["name"].str ?: return@mapNotNull null
            val artist = x["artist"]["name"].str ?: return@mapNotNull null
            val img = x["image"].list.reversed().firstNotNullOfOrNull { it["#text"].str?.takeIf { s -> s.isNotEmpty() } } ?: ""
            Music.fmSong(name, artist, img, taste.mine)
        }.sortedBy { norm(it.artist.split(",")[0]) !in yours }   // stable: Last.fm's order within each
        return pick(pool, SET, used)
    }

    // ---- playing from songs you picked ----
    /** A set of kind from the songs the DJ plays from, or null. coming: the song keys still to come in the queue. */
    private fun fromSet(kind: String, taste: Taste, first: Boolean, coming: Set<Pair<String, String>>): Pair<List<Track>, String>? {
        val (name, pool) = scope ?: return null
        var fresh = pool.filter { songKey(it) !in used }
        if (fresh.size < minOf(3, pool.size)) {   // all of them played: around again, but not with songs still to come
            fresh = pool.filter { songKey(it) !in coming }
            if (fresh.size < minOf(3, pool.size)) fresh = pool
        }
        val plays = HashMap<Pair<String, String>, Int>()
        Music.plays.items.values.forEach { plays[songKey(it.track)] = it.plays }
        taste.all.forEach { plays.merge(songKey(it), it.plays) { a, b -> maxOf(a, b) } }
        val recent = taste.recent.map { songKey(it) }.toSet()
        val t = now()
        val n = minOf(SET, fresh.size)
        val least = minOf(3, fresh.size)
        var songs: List<Track> = emptyList()
        var line = ""
        when (kind) {
            "loved" -> {
                songs = pick(fresh.filter { (plays[songKey(it)] ?: 0) > 0 }.sortedByDescending { plays[songKey(it)] }.take(30), n, emptySet())
                if (songs.isNotEmpty()) line = "From $name, the ones you keep coming back to, like “${songs[0].title}” by ${names(songs.take(1))}."
            }
            "unplayed" -> {
                songs = fresh.filter { (plays[songKey(it)] ?: 0) == 0 && songKey(it) !in taste.last }.shuffled().take(n)
                if (songs.isNotEmpty()) line = "Some from $name you haven't played yet, starting with “${songs[0].title}” by ${names(songs.take(1))}."
            }
            "throwback" -> {
                songs = fresh.filter { (plays[songKey(it)] ?: 0) > 0 && songKey(it) !in recent && t - (taste.last[songKey(it)] ?: 0.0) > 21 * 86400 }
                    .shuffled().take(n)
                if (songs.isNotEmpty()) line = "From $name, a few you haven't played in a while, like “${songs[0].title}”."
            }
            "artist" -> {
                val lead = { s: Track -> s.artist.split(",")[0].trim() }
                val count = fresh.groupingBy(lead).eachCount().filter { it.key.isNotEmpty() && it.value >= least }
                if (count.size > 1) {   // one artist in it all: that's not a set of its own
                    var r = Random.nextInt(count.values.sum())
                    val who = count.entries.first { r -= it.value; r < 0 }.key
                    songs = fresh.filter { lead(it) == who }.shuffled().take(n)
                    line = "A few from $who, from $name."
                }
            }
            else -> {
                songs = fresh.shuffled().take(n)
                if (songs.isNotEmpty()) line = "More from $name: ${names(songs, 3)}."
            }
        }
        if (songs.isEmpty() || songs.size < least) return null
        if (first) line = "${greeting()}, I'm your DJ, playing $name. " + line.replaceFirstChar { it.uppercase() }
        return songs to line
    }

    /** A set of the songs the DJ plays from that match what you asked (artist, title, album). */
    private fun askFrom(text: String): Built? {
        val (name, pool) = scope ?: return null
        val words = WORD.findAll(text).map { it.value }.filter { it.lowercase() !in FILLER }.map { norm(it) }.filter { it.isNotEmpty() }.toList()
        if (words.isEmpty()) return null
        val hits = pool.filter { t -> val all = norm("${t.title} ${t.artist} ${t.album}"); words.all { it in all } }
        val songs = pick(hits.filter { songKey(it) !in used }.shuffled() + hits.shuffled(), SET, emptySet())
        if (songs.isEmpty()) return null
        return Built("ask", songs, "From $name, you asked for ${text.trim()}: “${songs[0].title}” by ${names(songs.take(1))} first.")
    }

    /** (blocking) A set for what you asked: an artist, a mood, a decade or a genre; null if nothing fits.
     *  Playing from songs you picked: the ones of them that match, else from everywhere. */
    fun ask(text: String): Built? {
        val sc = scope ?: return askAll(text)
        askFrom(text)?.let { return it }
        return askAll(text)?.let { it.copy(line = "There's nothing like that in ${sc.first}, so from everywhere: ${it.line}") }
    }

    private fun askAll(text: String): Built? {
        val taste = loadTaste()
        val asked = text.trim()
        val words = Regex("[\\p{L}\\p{N}_'&.-]+").findAll(asked).map { it.value }.filter { it.lowercase() !in FILLER }.joinToString(" ")
        val named = HashMap<String, String>()
        taste.artists.forEach { named[norm(it)] = it }
        taste.mine.values.forEach { val a = it.artist.split(",")[0].trim(); if (a.isNotEmpty()) named[norm(a)] = a }
        var who = if (words.isNotEmpty()) bestNamed(named.values.toList(), words) { it } else null
        val moods = words.lowercase().split(" ").any { it in MOODS } || Regex("\\d0s").containsMatchIn(words)
        if (who == null && words.isNotEmpty() && !moods) {
            val info = runCatching { Music.artistInfo(words, null) }.getOrNull()   // else not an artist
            // Only an artist called just that: "drum and bass" isn't DrymBass.
            val the = Regex("^the ", RegexOption.IGNORE_CASE)
            if (info != null && norm(info.name.replace(the, "")) == norm(words.replace(the, ""))) who = info.name
        }
        if (who != null) {
            val info = runCatching { Music.artistInfo(who, null) }.getOrNull()
            val theirs = taste.mine.values.filter { t -> norm(who) in splitArtists(t.artist).map { norm(it) } }
            val songs = pick(pick(theirs, 2, used) + (info?.top ?: emptyList()), SET, used)
            if (songs.isNotEmpty()) return Built("ask", songs, "${info?.name?.ifEmpty { null } ?: who}, coming up: some you know, and some you might not.")
        }
        val tags = tags(asked)
        for (tag in tags) {
            val songs = tagSongs(tag, taste)
            if (songs.size >= 3) return Built("ask", songs, "You asked for $asked, so here's some $tag, starting with “${songs[0].title}” by ${names(songs.take(1))}.")
        }
        // A playlist of it: named by its mood ("chill"), which finds a curated one more often than a sentence.
        for (q in (tags.take(1) + asked).distinct()) for (key in services()) {
            val found = runCatching { Music.sources[key]?.mixFor(q) ?: emptyList() }.getOrDefault(emptyList())   // else the other service
            val songs = pick(found, SET, used)
            if (songs.size >= 3) return Built("ask", songs, "You asked for $asked. Here's a mix of it, starting with “${songs[0].title}” by ${names(songs.take(1))}.")
        }
        return null
    }

    /** (blocking) The next set: of kind, else the next kind in the DJ's order that has songs
     *  (avoid, the kind playing now, only when no other has). coming: the song keys still to
     *  come in the queue. Null if there are none. */
    fun build(kind: String? = null, avoid: String? = null, first: Boolean = false, coming: Set<Pair<String, String>> = emptySet()): Built? {
        val taste = loadTaste()
        val order = if (scope != null) FROM_ORDER else ORDER
        var spots = if (kind != null) listOf(-1) else order.indices.map { (step + it) % order.size }
        if (avoid != null && kind == null) spots = spots.filter { order[it] != avoid } + spots.filter { order[it] == avoid }.take(1)
        for (spot in spots) {
            val k = kind ?: order[spot]
            val got = (if (scope != null) fromSet(k, taste, first, coming) else when (k) {
                "repeat" -> setRepeat(taste, first)
                "throwback" -> setThrowback(taste)
                "fresh" -> setFresh(taste)
                "deep" -> setDeep(taste)
                "genre" -> setGenre(taste)
                "like" -> setLike(taste)
                else -> null
            }) ?: continue
            if (kind == null) step = spot + 1
            return Built(k, got.first, got.second)
        }
        return null
    }
}
