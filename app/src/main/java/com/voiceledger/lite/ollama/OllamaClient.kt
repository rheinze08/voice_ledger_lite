package com.voiceledger.lite.ollama

import com.voiceledger.lite.data.NoteEntity
import com.voiceledger.lite.data.OllamaSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class OllamaClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    suspend fun generateInsight(notes: List<NoteEntity>, settings: OllamaSettings): JournalInsight {
        require(notes.isNotEmpty()) { "At least one note is required for Gemma analysis." }
        val normalized = settings.normalized()
        val requestJson = buildJsonObject {
            put("model", normalized.model)
            put("stream", false)
            put("format", "json")
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            """
                            You are a compact semantic notebook analyst.
                            Read short personal notes and return strict JSON.
                            Never invent events that are not grounded in the notes.
                            Keep outputs concise and practical.
                            """.trimIndent(),
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", buildPrompt(notes, normalized.windowDays))
                    },
                )
            }
        }.toString()

        val responseText = withContext(Dispatchers.IO) {
            val connection = (URL("${normalized.baseUrl}/api/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = normalized.timeoutMs
                readTimeout = normalized.timeoutMs
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { stream ->
                    stream.write(requestJson.toByteArray(Charsets.UTF_8))
                }
                val bodyStream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val bodyText = BufferedReader(InputStreamReader(bodyStream)).use { reader ->
                    reader.readText()
                }
                if (connection.responseCode !in 200..299) {
                    error("Ollama request failed with ${connection.responseCode}: $bodyText")
                }
                bodyText
            } finally {
                connection.disconnect()
            }
        }

        val envelope = json.parseToJsonElement(responseText).jsonObject
        val rawMessage = envelope["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: error("Ollama response did not include a message body.")
        val normalizedJson = extractJsonObject(rawMessage)
        return json.decodeFromString<JournalInsight>(normalizedJson)
    }

    private fun buildPrompt(notes: List<NoteEntity>, windowDays: Int): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val noteBlock = notes.joinToString("\n\n") { note ->
            val bodyPreview = note.body.trim().replace("\n", " ").take(700)
            buildString {
                append("note_id=")
                append(note.id)
                append('\n')
                append("created_at=")
                append(formatter.format(Instant.ofEpochMilli(note.createdAtEpochMs)))
                append('\n')
                append("title=")
                append(note.title)
                append('\n')
                append("body=")
                append(bodyPreview)
            }
        }

        return """
            Analyze the following notes captured over the last $windowDays days.
            Return JSON with this exact shape:
            {
              "overview": "short paragraph",
              "highlights": ["bullet", "bullet"],
              "themes": [
                {
                  "label": "theme name",
                  "summary": "why the notes belong together",
                  "noteIds": [1, 2]
                }
              ]
            }

            Rules:
            - Use 3 to 5 highlights.
            - Use 2 to 4 themes.
            - `noteIds` must only contain ids from the input notes.
            - Focus on recurring topics, decisions, momentum shifts, and standout moments.
            - Do not wrap the JSON in markdown fences.

            Notes:
            $noteBlock
        """.trimIndent()
    }

    private fun extractJsonObject(raw: String): String {
        val startIndex = raw.indexOf('{')
        val endIndex = raw.lastIndexOf('}')
        if (startIndex == -1 || endIndex <= startIndex) {
            return raw.trim()
        }
        return raw.substring(startIndex, endIndex + 1)
    }
}
