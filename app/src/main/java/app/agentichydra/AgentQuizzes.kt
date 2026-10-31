package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One multiple-choice question with the answer indexes the model marked correct. */
data class QuizQuestion(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val correct: List<Int>,
    val explanation: String = "",
)

data class AgentQuiz(
    val title: String,
    val questions: List<QuizQuestion>,
    val showAnswersImmediately: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
)

/** The user's completed run through a quiz, kept with the reply so the report can reopen. */
data class QuizAttempt(
    val quiz: AgentQuiz,
    val selected: Map<String, List<Int>>,
    val completedAt: Long = System.currentTimeMillis(),
) {
    val correctCount: Int get() = quiz.questions.count { score(it) }

    val total: Int get() = quiz.questions.size

    fun score(question: QuizQuestion): Boolean = selected[question.id].orEmpty().sorted() == question.correct.sorted()

    fun json() =
        JSONObject()
            .put("completedAt", completedAt)
            .put(
                "quiz",
                JSONObject()
                    .put("id", quiz.id)
                    .put("title", quiz.title)
                    .put("showAnswersImmediately", quiz.showAnswersImmediately)
                    .put(
                        "questions",
                        JSONArray().apply {
                            quiz.questions.forEach { question ->
                                put(
                                    JSONObject()
                                        .put("id", question.id)
                                        .put("prompt", question.prompt)
                                        .put("options", JSONArray(question.options))
                                        .put("correct", JSONArray(question.correct))
                                        .put("explanation", question.explanation),
                                )
                            }
                        },
                    ),
            ).put("selected", JSONObject(selected))

    companion object {
        fun fromJson(data: JSONObject): QuizAttempt? {
            val quiz = data.optJSONObject("quiz") ?: return null
            val questions = quiz.optJSONArray("questions") ?: return null
            val parsed =
                (0 until questions.length()).mapNotNull { index ->
                    val q = questions.optJSONObject(index) ?: return@mapNotNull null
                    val options = q.optJSONArray("options") ?: return@mapNotNull null
                    val correct = q.optJSONArray("correct") ?: return@mapNotNull null
                    QuizQuestion(
                        q.optString("id"),
                        q.optString("prompt"),
                        (0 until options.length()).map { options.optString(it) },
                        (0 until correct.length()).map { correct.optInt(it) },
                        q.optString("explanation"),
                    )
                }
            if (parsed.isEmpty()) return null
            val stored = data.optJSONObject("selected") ?: JSONObject()
            val selected =
                parsed.associate { question ->
                    val values = stored.optJSONArray(question.id) ?: JSONArray()
                    question.id to (0 until values.length()).map { values.optInt(it) }
                }
            return QuizAttempt(
                AgentQuiz(
                    quiz.optString("title"),
                    parsed,
                    quiz.optBoolean("showAnswersImmediately", false),
                    quiz.optString("id").ifBlank { UUID.randomUUID().toString() },
                ),
                selected,
                data.optLong("completedAt", 0),
            )
        }
    }
}

internal object QuizTool {
    const val NAME = "quiz_user"
    private const val MAX_QUESTIONS = 10

    fun parse(args: JSONObject): AgentQuiz {
        val title = args.optString("title").trim()
        require(title.isNotEmpty() && title.length <= 120) { "Give the quiz a title up to 120 characters." }
        val input = args.getJSONArray("questions")
        require(input.length() in 1..MAX_QUESTIONS) { "Include between one and ten questions." }
        val questions =
            (0 until input.length()).map { index ->
                val q = input.getJSONObject(index)
                val id = q.getString("id")
                val prompt = q.getString("question").trim()
                require(
                    id.matches(Regex("[A-Za-z0-9_-]{1,60}")) && prompt.isNotEmpty() && prompt.length <= 400,
                ) { "Use a short unique ID and a question up to 400 characters." }
                val optionsArray = q.getJSONArray("options")
                val options = (0 until optionsArray.length()).map { optionsArray.getString(it).trim() }
                require(
                    options.size in 2..6 && options.distinct().size == options.size &&
                        options.all { it.isNotEmpty() && it.length <= 200 },
                ) { "Each question needs two to six distinct options, up to 200 characters each." }
                val correctArray = q.getJSONArray("correct")
                val correct = (0 until correctArray.length()).map { correctArray.getInt(it) }
                require(
                    correct.isNotEmpty() && correct.distinct().size == correct.size && correct.all { it in options.indices },
                ) { "Mark one or more valid zero-based option indexes as correct." }
                val explanation = q.optString("explanation").trim()
                require(explanation.length <= 4000) { "Keep each explanation under 4,000 characters." }
                QuizQuestion(id, prompt, options, correct.sorted(), explanation)
            }
        require(questions.map { it.id }.distinct().size == questions.size) { "Question IDs must be unique." }
        require(
            questions.sumOf { it.options.sumOf(String::length) } <= 6000,
        ) { "Keep the combined options under 6,000 characters." }
        return AgentQuiz(title, questions, args.optBoolean("show_answers_immediately", false))
    }

    /** Validates a finished attempt and returns the compact result the model reads next. */
    fun answer(
        quiz: AgentQuiz,
        attempt: QuizAttempt,
    ): String {
        require(attempt.quiz.id == quiz.id) { "Quiz answers did not match this quiz." }
        quiz.questions.forEach { question ->
            val chosen = attempt.selected[question.id].orEmpty()
            require(
                chosen.isNotEmpty() && chosen.distinct().size == chosen.size && chosen.all { it in question.options.indices },
            ) { "Answer every question with valid options." }
        }
        val result =
            JSONObject()
                .put("status", "completed")
                .put("title", quiz.title)
                .put("score", "${attempt.correctCount}/${attempt.total}")
                .put(
                    "answers",
                    JSONArray().apply {
                        quiz.questions.forEach { question ->
                            val chosen = attempt.selected[question.id].orEmpty().sorted()
                            put(
                                JSONObject()
                                    .put("id", question.id)
                                    .put("correct", chosen == question.correct)
                                    .put("selected", JSONArray(chosen.map { question.options[it] }))
                                    .put("expected", JSONArray(question.correct.map { question.options[it] })),
                            )
                        }
                    },
                ).toString()
        require(result.length <= 20000) { "Quiz answers are too long." }
        return result
    }

    fun cancelled(): String =
        JSONObject()
            .put("status", "cancelled")
            .put("instruction", "The user dismissed the quiz. Do not repeat it without new instructions.")
            .toString()

    fun schema(): JSONObject =
        toolSchema(
            NAME,
            "Run an interactive multiple-choice quiz in a bottom-sheet wizard, then open a results report with the score and per-question explanations. Mark answers correct by zero-based option index. Only use this when the user asks for a quiz or a knowledge check.",
            listOf("title", "questions"),
            "title" to stringSchema("Short quiz title, maximum 120 characters."),
            "show_answers_immediately" to
                booleanSchema(
                    "Reveal whether each answer was right and show its explanation as soon as the user answers; default false shows them only in the report at the end.",
                ),
            "questions" to
                JSONObject().put("type", "array").put("minItems", 1).put("maxItems", MAX_QUESTIONS).put(
                    "items",
                    JSONObject()
                        .put("type", "object")
                        .put("required", JSONArray(listOf("id", "question", "options", "correct")))
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", stringSchema("Unique short question ID."))
                                .put("question", stringSchema("Question text, maximum 400 characters."))
                                .put(
                                    "options",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("minItems", 2)
                                        .put("maxItems", 6)
                                        .put("items", stringSchema("One option, up to 200 characters.")),
                                ).put(
                                    "correct",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("minItems", 1)
                                        .put("description", "Zero-based indexes of every correct option.")
                                        .put("items", JSONObject().put("type", "integer").put("minimum", 0)),
                                ).put(
                                    "explanation",
                                    stringSchema("Markdown explanation shown after the answer or in the report."),
                                ),
                        ),
                ),
        )
}
