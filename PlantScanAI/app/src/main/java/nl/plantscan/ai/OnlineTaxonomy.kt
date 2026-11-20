package nl.plantscan.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Key-free online taxonomic check through iNaturalist. This verifies species
 * names and fetches Dutch names, NOT a second image-recognition model.
 * A reachable network check is mandatory before presenting a photo result.
 */
data class OnlineTaxonCheck(
    val completed: Boolean,
    val matched: Boolean,
    val scientific: String,
    val dutch: String,
    val link: String,
    val note: String
)

object OnlineTaxonomy {
    fun check(guesses: List<SpeciesGuess>): OnlineTaxonCheck {
        require(guesses.isNotEmpty()) { "Geen soortvoorstellen beschikbaar" }
        var matched: OnlineTaxonCheck? = null
        for (guess in guesses.take(2)) {
            val query = URLEncoder.encode(guess.name, "UTF-8")
            val address = "https://api.inaturalist.org/v1/taxa/autocomplete?locale=nl&q=" + query
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PlantScanAI/0.3.0 (plant taxonomy lookup)")
            val body = try {
                val responseCode = connection.responseCode
                if (responseCode != 200) throw IllegalStateException(
                    "Online plantenregister niet bereikbaar (HTTP " + responseCode + ")"
                )
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val items = JSONObject(body).optJSONArray("results")
            for (i in 0 until (items?.length() ?: 0)) {
                val entry = items!!.optJSONObject(i) ?: continue
                val accepted = entry.optString("name").trim()
                val wanted = guess.name.trim()
                if (!accepted.equals(wanted, ignoreCase = true)) continue
                val common = entry.optString("preferred_common_name", "")
                val id = entry.optInt("id", 0)
                matched = OnlineTaxonCheck(
                    completed = true,
                    matched = true,
                    scientific = accepted,
                    dutch = common,
                    link = if (id > 0) "https://www.inaturalist.org/taxa/" + id else "",
                    note = "Soortnaam opgezocht in iNaturalist. De foto is niet online door een tweede AI herkend."
                )
                break
            }
            if (matched != null) break
        }
        return matched ?: OnlineTaxonCheck(
            completed = true,
            matched = false,
            scientific = guesses[0].name,
            dutch = "",
            link = "",
            note = "Online gezocht, maar geen exacte soortnaam gevonden. Controleer dit voorstel zelf."
        )
    }
}
