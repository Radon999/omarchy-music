package org.omarchy.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.launch
import java.io.File

/** Your songs' moods, as on the desktop: from Chartmetric (with a token of yours, in Settings),
 *  else Last.fm's tags of the song (or of its artist), else guessed from its genre, its
 *  artist's genres on MusicBrainz, or its title. A scan reads only the songs it hasn't yet and
 *  keeps what it found in moods.json: the DJ and the mood playlists in the Music tab use it. */
object Moods {
    /** Each mood and the tags (Last.fm's, Chartmetric's moods and activities) that mean it. */
    val MOODS = linkedMapOf(
        "happy" to setOf("happy", "feel good", "feelgood", "upbeat", "fun", "cheerful", "joyful", "uplifting", "summer", "sunny",
            "positive", "optimistic", "carefree", "playful", "bright"),
        "sad" to setOf("sad", "melancholic", "melancholy", "depressing", "depressed", "heartbreak", "heartbroken", "lonely",
            "sorrow", "breakup", "crying", "grief", "longing", "bittersweet", "emotional", "tearjerker"),
        "chill" to setOf("chill", "chillout", "chill out", "relaxing", "relax", "calm", "mellow", "lo-fi", "lofi", "easy listening",
            "laid back", "laid-back", "smooth", "downtempo", "peaceful", "soothing", "sleep", "rainy day"),
        "energetic" to setOf("energetic", "energy", "hype", "workout", "gym", "running", "powerful", "intense", "fast", "exciting",
            "driving", "adrenaline", "pump up", "motivation", "motivational"),
        "romantic" to setOf("romantic", "love", "love songs", "love song", "sensual", "sexy", "slow jams", "tender", "passionate",
            "intimate", "seductive"),
        "dark" to setOf("dark", "haunting", "gothic", "ominous", "eerie", "creepy", "brooding", "sinister", "menacing", "noir", "mysterious"),
        "angry" to setOf("aggressive", "angry", "rage", "furious", "hate", "brutal", "violent", "anger"),
        "dreamy" to setOf("dreamy", "ethereal", "dream pop", "dreampop", "shoegaze", "atmospheric", "hypnotic", "spacey", "trippy",
            "psychedelic", "floating"),
        "party" to setOf("party", "dance", "club", "banger", "dancefloor", "dance floor", "clubbing", "groovy", "celebration"),
        "focus" to setOf("focus", "study", "studying", "concentration", "instrumental", "work", "reading", "coding"),
    )
    /** A genre and the moods it usually means. */
    private val GENRE_MOODS = mapOf(
        "metal" to listOf("angry", "energetic"), "metalcore" to listOf("angry", "energetic"), "punk" to listOf("energetic", "angry"),
        "hardcore" to listOf("angry", "energetic"), "grunge" to listOf("dark", "angry"), "drum and bass" to listOf("energetic", "party"),
        "dnb" to listOf("energetic", "party"), "dubstep" to listOf("energetic", "dark"), "edm" to listOf("party", "energetic"),
        "house" to listOf("party"), "techno" to listOf("party", "energetic"), "trance" to listOf("energetic", "dreamy"),
        "disco" to listOf("party", "happy"), "funk" to listOf("party", "happy"), "dance" to listOf("party"),
        "electropop" to listOf("party", "happy"), "k-pop" to listOf("happy", "party"), "pop" to listOf("happy"),
        "hip hop" to listOf("energetic"), "hip-hop" to listOf("energetic"), "rap" to listOf("energetic"),
        "trap" to listOf("energetic", "dark"), "drill" to listOf("dark", "energetic"), "r&b" to listOf("romantic", "chill"),
        "rnb" to listOf("romantic", "chill"), "soul" to listOf("romantic", "chill"), "neo-soul" to listOf("chill", "romantic"),
        "jazz" to listOf("chill"), "blues" to listOf("sad"), "ambient" to listOf("focus", "dreamy"), "classical" to listOf("focus"),
        "piano" to listOf("focus", "chill"), "lo-fi" to listOf("chill", "focus"), "folk" to listOf("chill"), "acoustic" to listOf("chill"),
        "singer-songwriter" to listOf("chill", "sad"), "shoegaze" to listOf("dreamy"), "dream pop" to listOf("dreamy"),
        "post-rock" to listOf("focus", "dreamy"), "emo" to listOf("sad"), "darkwave" to listOf("dark"), "gothic" to listOf("dark"),
        "industrial" to listOf("dark", "angry"), "reggae" to listOf("chill", "happy"), "reggaeton" to listOf("party"),
        "latin" to listOf("party", "happy"), "gospel" to listOf("happy"), "soundtrack" to listOf("focus"),
        "synthwave" to listOf("dreamy", "energetic"), "vaporwave" to listOf("dreamy"), "chillwave" to listOf("chill", "dreamy"),
        "psychedelic" to listOf("dreamy"), "ballad" to listOf("sad", "romantic"), "rock" to listOf("energetic"),
        "art rock" to listOf("dreamy"), "experimental" to listOf("dark"), "experimental rock" to listOf("dark", "dreamy"),
        "idm" to listOf("focus", "dreamy"), "trip hop" to listOf("chill", "dark"), "trip-hop" to listOf("chill", "dark"),
        "art pop" to listOf("dreamy"), "electronic" to listOf("focus"), "electronica" to listOf("focus", "dreamy"),
        "hauntology" to listOf("dark", "dreamy"), "indie" to listOf("dreamy"), "indie pop" to listOf("happy", "dreamy"),
        "folktronica" to listOf("chill"), "new wave" to listOf("happy", "party"), "synth-pop" to listOf("happy", "party"),
        "synthpop" to listOf("happy", "party"), "bossa nova" to listOf("chill", "romantic"), "country" to listOf("happy"),
        "doom metal" to listOf("dark", "sad"), "black metal" to listOf("dark", "angry"), "death metal" to listOf("angry"),
        "noise" to listOf("angry", "dark"),
    )
    /** Words in a title that hint at a mood (the weakest guess there is). */
    private val TITLE_MOODS = mapOf(
        "love" to "romantic", "lover" to "romantic", "kiss" to "romantic", "heart" to "romantic", "baby" to "romantic",
        "cry" to "sad", "crying" to "sad", "tears" to "sad", "alone" to "sad", "lonely" to "sad", "goodbye" to "sad", "sorry" to "sad",
        "miss" to "sad", "broken" to "sad", "party" to "party", "dance" to "party", "dancing" to "party", "club" to "party",
        "happy" to "happy", "sunshine" to "happy", "sun" to "happy", "summer" to "happy", "dream" to "dreamy", "dreams" to "dreamy",
        "night" to "dark", "dark" to "dark", "devil" to "dark", "ghost" to "dark", "fire" to "energetic", "run" to "energetic",
        "rage" to "angry", "hate" to "angry", "sleep" to "chill", "rain" to "chill", "slow" to "chill",
    )
    private const val LASTFM_PACE = 250L   // ms between Last.fm requests (it allows five a second)
    internal var mbPace = 1100L            // and between MusicBrainz's (one a second)

    @Serializable
    data class Song(val moods: List<String> = emptyList(), val from: String = "", val t: Double = 0.0)

    @Serializable
    private data class Saved(val songs: Map<String, Song> = emptyMap())

    var scanning by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var version by mutableIntStateOf(0)   // bumped as moods come in, for the screens
        private set
    @Volatile var stop = false
    private val songs = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private var file: File? = null

    // What a scan asks: swapped in the tests.
    internal var lastfmTags: (artist: String, title: String) -> List<Pair<String, Int>>? = { artist, title ->
        if (LastFm.key.isEmpty()) null
        else LastFm.call("track.getTopTags", params = mapOf("artist" to artist, "track" to title, "autocorrect" to 1))
            .path("toptags", "tag").list.take(15).mapNotNull { t -> t["name"].str?.let { it to (t["count"].long ?: 0).toInt() } }
    }
    internal var lastfmArtistTags: (String) -> List<Pair<String, Int>> = { LastFm.artistTags(it) }
    internal var fetch: (String, Map<String, String>) -> JsonElement = { url, h -> httpJson(url, h) }
    internal var post: (String, String) -> JsonElement = { url, body ->
        httpJson(url, mapOf("Content-Type" to "application/json"), body.toRequestBody("application/json".toMediaType()))
    }
    internal var genreOf: (Track) -> String = { t -> Music.lib.items[t.id]?.genre ?: "" }
    internal var pool: () -> List<Track> = {
        val seen = HashSet<String>()
        (Music.mySongs() + Music.lists.all().flatMap { it.tracks } + Music.mostPlayed().take(300))
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() && seen.add(key(it)) }
    }
    internal var token: () -> String = { Music.apiKeys["chartmetric"].orEmpty() }

    fun init(c: Context) {
        val f = File(c.applicationContext.filesDir, "moods.json")
        file = f
        Music.bg({ readText(f)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() } }) { s, _ ->
            if (s != null) { s.songs.forEach { (k, v) -> songs.putIfAbsent(k, v) }; version++ }
        }
    }

    fun key(t: Track) = songKey(t).let { "${it.first}|${it.second}" }

    fun of(t: Track): List<String> = songs[key(t)]?.moods ?: emptyList()

    internal fun from(t: Track): String = songs[key(t)]?.from ?: ""

    // ---- the parts that don't need the app ----
    /** The moods weighted tags mean, the strongest first, at most n (none weaker than a third of the strongest). */
    fun moodsOf(tags: List<Pair<String, Int>>, n: Int = 3): List<String> {
        val score = LinkedHashMap<String, Double>()
        for ((tag, w) in tags) {
            val t = tag.lowercase().trim()
            for ((mood, words) in MOODS) if (t in words) score.merge(mood, w.toDouble(), Double::plus)
            val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val last2 = parts.takeLast(2).joinToString(" ")
            val genre = if (t in GENRE_MOODS) t else if (last2 in GENRE_MOODS) last2 else parts.lastOrNull() ?: ""
            for (mood in GENRE_MOODS[genre] ?: emptyList())   // a genre says less than a mood tag; "british alternative rock": rock
                score.merge(mood, w * if (genre == t) 0.5 else 0.3, Double::plus)
        }
        val best = score.entries.sortedByDescending { it.value }   // stable: the first tag's mood first when they tie
        if (best.isEmpty()) return emptyList()
        return best.take(n).filter { it.value >= best[0].value / 3 }.map { it.key }
    }

    fun genreMoods(genre: String): List<String> =
        moodsOf(genre.split(Regex("[;/,]")).map { it.trim() }.filter { it.isNotEmpty() }.map { it to 100 }, 2)

    fun titleMoods(title: String): List<String> =
        Regex("[a-z']+").findAll(cleanTitle(title).lowercase()).mapNotNull { TITLE_MOODS[it.value] }.distinct().take(2).toList()

    /** The moods a request's words mean: "something to chill to" is chill. */
    fun requestMoods(text: String): List<String> = Regex("[a-z'-]+").findAll(text.lowercase()).flatMap { m ->
        val w = m.value
        moodsOf((setOf(w) + (Dj.MOODS[w] ?: emptyList())).filter { c -> MOODS.values.any { c in it } }.map { it to 1 }, 1)
    }.distinct().toList()

    // ---- the services ----
    private class Chartmetric(val refresh: String) {
        var token = ""; var until = 0.0
        fun get(path: String): JsonElement {
            if (token.isEmpty() || now() > until) {
                val res = post("https://api.chartmetric.com/api/token", JsonObject(mapOf("refreshtoken" to JsonPrimitive(refresh))).toString())
                token = res["token"].str ?: throw IllegalStateException("Chartmetric didn't take the token")
                until = now() + (res["expires_in"].long ?: 3600) - 60
            }
            repeat(3) {
                try { return fetch("https://api.chartmetric.com/api$path", mapOf("Authorization" to "Bearer $token")) }
                catch (e: HttpError) { if (e.code != 429) throw e; Thread.sleep(2000) }   // it allows a request or two a second
            }
            throw IllegalStateException("Chartmetric is busy")
        }

        /** The track's mood and activity tags, empty if Chartmetric hasn't got it. */
        fun moods(artist: String, title: String): List<Pair<String, Int>> {
            val lead = artist.split(",")[0].trim()
            val found = get("/search?q=${enc("$lead ${cleanTitle(title)}")}&type=tracks&limit=5").path("obj", "tracks").list
                .filter { t -> val names = norm(t["artist_names"].list.joinToString(" ") { it.str ?: "" }); norm(lead) in names || similar(names, norm(lead)) > 0.6 }
            val best = bestNamed(found, cleanTitle(title)) { cleanTitle(it["name"].str ?: "") } ?: return emptyList()
            val obj = get("/track/${best["id"].long}")["obj"]
            val out = ArrayList<Pair<String, Int>>()
            for (k in listOf("moods", "mood", "activities", "activity", "tags", "genres")) {
                val v = obj[k] ?: continue
                for (x in (v as? kotlinx.serialization.json.JsonArray) ?: listOf(v)) {
                    val name = (x["name"].str ?: x.str ?: "").lowercase().trim()
                    if (name.isNotEmpty()) out += name to if (k == "tags" || k == "genres") 50 else 100
                }
            }
            return out
        }
    }

    private fun musicbrainz(artist: String): List<Pair<String, Int>> {
        val url = "https://musicbrainz.org/ws/2/artist/?query=${enc("artist:\"$artist\"")}&limit=3&fmt=json"
        var res: JsonElement? = null
        for (wait in listOf(2000L, 5000L, 0L)) {
            try { res = fetch(url, mapOf("User-Agent" to APP_UA)); break }
            catch (e: HttpError) { if (e.code != 503 || wait == 0L) throw e; Thread.sleep(wait) }   // 503: asked too often
        }
        val found = res["artists"].list.sortedByDescending { it["tags"].list.size }   // namesakes have no tags
        val best = bestNamed(found, artist) { it["name"].str ?: "" } ?: return emptyList()
        return best["tags"].list.mapNotNull { t -> val n = (t["count"].long ?: 0).toInt(); t["name"].str?.takeIf { n > 0 }?.let { it to n } }
    }

    private fun log(what: String, e: Throwable) { runCatching { android.util.Log.w("omarchy-music", "moods: $what: $e") } }

    /** (moods, where from) for one song: Chartmetric, Last.fm's song tags, its artist's, then a guess. */
    private fun find(t: Track, cm: Chartmetric?, artistTags: HashMap<String, List<Pair<String, Int>>>,
                     mbTags: HashMap<String, List<Pair<String, Int>>>): Pair<List<String>, String> {
        if (cm != null) try { moodsOf(cm.moods(t.artist, t.title)).takeIf { it.isNotEmpty() }?.let { return it to "chartmetric" } }
            catch (e: Exception) { log("chartmetric", e) }   // Last.fm, then
        val lead = t.artist.split(",")[0].trim()
        try {
            val title = t.title.replace(Regex("\\s*(?:[(\\[]| - ).*$"), "").ifEmpty { t.title }   // as written, without "(Remastered)"
            val tags = lastfmTags(lead, title)
            if (tags != null) {
                Thread.sleep(LASTFM_PACE)
                moodsOf(tags).takeIf { it.isNotEmpty() }?.let { return it to "lastfm" }
                val theirs = artistTags.getOrPut(norm(lead)) { lastfmArtistTags(lead).also { Thread.sleep(LASTFM_PACE) } }
                moodsOf(theirs, 2).takeIf { it.isNotEmpty() }?.let { return it to "lastfm" }
            }
        } catch (e: Exception) { log("last.fm", e) }   // a guess, then
        genreMoods(genreOf(t)).takeIf { it.isNotEmpty() }?.let { return it to "guess" }
        val mb = mbTags.getOrPut(norm(lead)) {
            (try { musicbrainz(lead) } catch (e: Exception) { log("musicbrainz", e); emptyList() }).also { Thread.sleep(mbPace) }
        }
        return (moodsOf(mb, 2).ifEmpty { titleMoods(t.title) }) to "guess"
    }

    /** The songs of pool a scan reads: the new ones (again: and the guessed ones). */
    fun todo(songsNow: List<Track> = pool(), again: Boolean = false) =
        songsNow.filter { songs[key(it)].let { s -> s == null || (again && s.from == "guess") } }

    /** (blocking) Find the moods of the songs that have none yet; how many it read. */
    fun scan(again: Boolean = false, progress: (String) -> Unit = {}): Int {
        val todo = todo(pool(), again)
        val cm = token().takeIf { it.isNotEmpty() }?.let { Chartmetric(it) }
        val artistTags = HashMap<String, List<Pair<String, Int>>>()
        val mbTags = HashMap<String, List<Pair<String, Int>>>()
        stop = false
        for ((i, t) in todo.withIndex()) {
            if (stop) break
            if (i % 5 == 0) progress("Finding your songs' moods · $i of ${todo.size}")
            val (moods, from) = find(t, cm, artistTags, mbTags)
            songs[key(t)] = Song(moods, from, now())
            if (i % 25 == 24) { save(); progress("Finding your songs' moods · ${i + 1} of ${todo.size}") }
        }
        save()
        return todo.size
    }

    /** scan() in the background, with a message when it's done. */
    fun scanSoon(again: Boolean = false) {
        if (scanning) return
        scanning = true
        status = "Finding your songs' moods…"
        Music.bg({ scan(again) { text -> Music.scope.launch { status = text; version++ } } }) { n, err ->
            scanning = false; status = ""; version++
            Music.toast(if (err != null) "Couldn't finish the mood scan: ${err.message?.take(80)}"
                else if ((n ?: 0) > 0) "Moods found for $n song${if (n == 1) "" else "s"}" else "All your songs have moods")
        }
    }

    private fun save() {
        val f = file ?: return
        writeAtomic(f, JSON.encodeToString(Saved.serializer(), Saved(songs.toMap())))
    }

    /** The songs of pool in mood, the ones it's their strongest mood of first. */
    fun songsIn(mood: String, songsNow: List<Track> = pool()): List<Track> =
        songsNow.withIndex().filter { mood in of(it.value) }.sortedWith(compareBy({ of(it.value).indexOf(mood) }, { it.index })).map { it.value }

    /** How many songs are in each mood (the moods with none left out), most first. */
    fun counts(songsNow: List<Track> = pool()): List<Pair<String, Int>> =
        songsNow.flatMap { of(it) }.filter { it in MOODS }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    internal fun clearForTests() = songs.clear()
}
