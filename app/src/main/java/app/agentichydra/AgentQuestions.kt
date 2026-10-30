package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class QuestionType(
    val wire: String,
) {
    SINGLE("single_choice"),
    MULTIPLE("multiple_choice"),
    TEXT("text"),
    YES_NO("yes_no"),
}

data class AgentQuestion(
    val id: String,
    val title: String,
    val prompt: String,
    val type: QuestionType,
    val options: List<String>,
    val required: Boolean = true,
    val allowOther: Boolean = false,
)

data class AgentQuestionRequest(
    val questions: List<AgentQuestion>,
    val id: String = UUID.randomUUID().toString(),
)

internal object QuestionTool {
    fun parse(args: JSONObject): AgentQuestionRequest {
        val input = args.getJSONArray("questions")
        require(input.length() in 1..8) { "Ask between one and eight questions." }
        val questions =
            (0 until input.length()).map { index ->
                val q = input.getJSONObject(index)
                val id = q.getString("id")
                val title = q.getString("title")
                val prompt = q.getString("question")
                require(
                    id.matches(
                        Regex("[A-Za-z0-9_-]{1,60}"),
                    ) && title.isNotBlank() && title.length <= 120 && prompt.isNotBlank() && prompt.length <= 1200,
                ) {
                    "Use a short unique ID, a title up to 120 characters, and a question up to 1,200 characters."
                }
                val type =
                    QuestionType.entries.firstOrNull { it.wire == q.getString("type") }
                        ?: error("Unknown question type.")
                val options =
                    if (type == QuestionType.YES_NO) {
                        listOf("Yes", "No")
                    } else {
                        q
                            .optJSONArray("options")
                            ?.let { array ->
                                (0 until array.length()).map { array.getString(it) }
                            }.orEmpty()
                    }
                if (type in setOf(QuestionType.SINGLE, QuestionType.MULTIPLE)) {
                    require(
                        options.size in 2..8 && options.distinct().size == options.size &&
                            options.all {
                                it.isNotBlank() && it.length <= 200
                            },
                    ) { "Choice questions need two to eight distinct options, up to 200 characters each." }
                }
                AgentQuestion(
                    id,
                    title,
                    prompt,
                    type,
                    options,
                    q.optBoolean("required", true),
                    q.optBoolean("allow_other", false) && type in setOf(QuestionType.SINGLE, QuestionType.MULTIPLE),
                )
            }
        require(questions.map { it.id }.distinct().size == questions.size) { "Question IDs must be unique." }
        require(
            questions.sumOf {
                it.options.sumOf(String::length)
            } <= 4000,
        ) { "Keep the combined choice labels under 4,000 characters." }
        return AgentQuestionRequest(questions)
    }

    fun valid(
        question: AgentQuestion,
        answer: JSONObject?,
    ): Boolean {
        val selected =
            answer
                ?.optJSONArray("selected")
                ?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                }.orEmpty()
        val text = answer?.optString("text").orEmpty().trim()
        if (text.length > 1000 || selected.distinct().size != selected.size || selected.any { it !in question.options }) {
            return false
        }
        if (question.type == QuestionType.TEXT && selected.isNotEmpty()) return false
        val other = answer?.optBoolean("other", false) == true
        if (other && (!question.allowOther || text.isBlank())) return false
        if (question.type != QuestionType.TEXT && !other && text.isNotEmpty()) return false
        if (question.type in setOf(QuestionType.SINGLE, QuestionType.YES_NO) && selected.size + (if (other) 1 else 0) > 1) {
            return false
        }
        val answered =
            when (question.type) {
                QuestionType.TEXT -> text.isNotEmpty()
                else -> selected.isNotEmpty() || other
            }
        return answered || !question.required
    }

    fun result(
        request: AgentQuestionRequest,
        drafts: String,
    ): String {
        val answers = JSONObject(drafts)
        require(
            request.questions.all { valid(it, answers.optJSONObject(it.id)) },
        ) { "Complete the required questions." }
        val result =
            JSONObject()
                .put("status", "answered")
                .put(
                    "answers",
                    JSONArray().apply {
                        request.questions.forEach { q ->
                            val answer = answers.optJSONObject(q.id) ?: JSONObject()
                            put(
                                JSONObject()
                                    .put("id", q.id)
                                    .put("type", q.type.wire)
                                    .put("other", answer.optBoolean("other", false))
                                    .put("selected", answer.optJSONArray("selected") ?: JSONArray())
                                    .put("text", answer.optString("text").trim())
                                    .put(
                                        "skipped",
                                        !q.required && answer.optString("text").isBlank() &&
                                            (answer.optJSONArray("selected")?.length() ?: 0) == 0,
                                    ),
                            )
                        }
                    },
                ).toString()
        require(result.length <= 15000) { "Answers are too long. Please shorten the text answers." }
        return result
    }

    fun schema(): JSONObject =
        toolSchema(
            "ask_user",
            "Pause and ask the user up to eight questions in a bottom-sheet wizard. Use single_choice, multiple_choice, text, or yes_no. Never use this to grant Android permissions or approve tool actions.",
            listOf("questions"),
            "questions" to
                JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 8).put(
                    "items",
                    JSONObject()
                        .put("type", "object")
                        .put("required", JSONArray(listOf("id", "title", "question", "type")))
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", stringSchema("Unique short question ID."))
                                .put("title", stringSchema("Short heading, maximum 120 characters."))
                                .put("question", stringSchema("Question to show, maximum 1,200 characters."))
                                .put(
                                    "type",
                                    stringSchema(
                                        "Answer format.",
                                    ).put("enum", JSONArray(QuestionType.entries.map { it.wire })),
                                ).put(
                                    "options",
                                    JSONObject()
                                        .put(
                                            "type",
                                            "array",
                                        ).put("items", stringSchema("An option, up to 200 characters."))
                                        .put("maxItems", 8),
                                ).put("required", booleanSchema("Whether an answer is required; default true."))
                                .put(
                                    "allow_other",
                                    booleanSchema("Allow a custom answer alongside choice options; default false."),
                                ),
                        ),
                ),
        )
}

internal fun stringSchema(description: String) = JSONObject().put("type", "string").put("description", description)

internal fun booleanSchema(description: String) = JSONObject().put("type", "boolean").put("description", description)

internal fun integerSchema(
    description: String,
    min: Int,
    max: Int,
) = JSONObject()
    .put("type", "integer")
    .put("description", description)
    .put("minimum", min)
    .put("maximum", max)

internal fun toolSchema(
    name: String,
    description: String,
    required: List<String>,
    vararg properties: Pair<String, JSONObject>,
) = JSONObject()
    .put("type", "function")
    .put(
        "function",
        JSONObject().put("name", name).put("description", description).put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().apply { properties.forEach { put(it.first, it.second) } })
                .put("required", JSONArray(required)),
        ),
    )
