package org.openvoyah.controls

import android.test.AndroidTestCase
import org.json.JSONArray
import org.json.JSONObject
import org.openvoyah.controls.data.voice.Interpretation
import org.openvoyah.controls.data.voice.OpenRouterVoiceInterpreter
import org.openvoyah.controls.domain.voice.NaturalAction

class OpenRouterVoiceInterpreterTest : AndroidTestCase() {
    private val parser = OpenRouterVoiceInterpreter { null }

    fun testOnlyTheExplicitActionSchemaIsAccepted() {
        NaturalAction.entries.forEach { action ->
            assertEquals(Interpretation.Command(action), parser.decode(response("""{"action":"${action.wireName}"}""")))
        }
        assertEquals(Interpretation.Unknown, parser.decode(response("""{"action":"none"}""")))
        for (content in listOf(
            """{"action":"headlights_off"}""",
            """{"action":"volume_up","amount":100}""",
            """{"action":"restart_music","package":"com.example.app"}""",
            """{"action":"volume_down","action":"volume_up"}""",
            """{"action":"volume_up"} extra""",
            "volume_up",
        )) {
            assertEquals(Interpretation.Unavailable, parser.decode(response(content)))
        }
        assertEquals(Interpretation.Unavailable, parser.decode(response("""{"action":"volume_up"}""", "length")))
        assertEquals(Interpretation.Unavailable, parser.decode(response("""{"action":"volume_up"}""", toolCall = true)))
    }

    private fun response(content: String, finish: String = "stop", toolCall: Boolean = false): String {
        val message = JSONObject().put("content", content)
        if (toolCall) message.put("tool_calls", JSONArray().put(JSONObject().put("id", "tool")))
        return JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", message).put("finish_reason", finish))).toString()
    }
}
