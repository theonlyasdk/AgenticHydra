package app.agentichydra

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Walks the user through a model-authored quiz. The report opens only after the last question,
 * whether or not each answer was revealed along the way.
 */
@Composable
fun AgentQuizSheet(
    quiz: AgentQuiz,
    onFinish: (QuizAttempt) -> Unit,
    onCancel: () -> Unit,
) {
    var index by rememberSaveable(quiz.id) { mutableIntStateOf(0) }
    var selections by rememberSaveable(quiz.id) { mutableStateOf("{}") }
    var finished by remember(quiz.id) { mutableStateOf<QuizAttempt?>(null) }
    var error by remember(quiz.id) { mutableStateOf<String?>(null) }
    HydraBottomSheet(onDismiss = { finished?.let(onFinish) ?: onCancel() }) { dismiss ->
        val question = quiz.questions[index]
        val chosen = remember(selections, question.id) { selection(selections, question.id) }

        fun submit() {
            val attempt =
                QuizAttempt(
                    quiz,
                    quiz.questions.associate { it.id to selection(selections, it.id) },
                )
            runCatching { QuizTool.answer(quiz, attempt) }
                .onSuccess {
                    finished = attempt
                    error = null
                    dismiss()
                }.onFailure { error = it.message }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Quiz, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        quiz.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${index + 1} of ${quiz.questions.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "End quiz") }
            }
            LinearProgressIndicator(
                progress = { (index + 1f) / quiz.questions.size },
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedContent(
                targetState = index,
                modifier = Modifier.weight(1f, fill = false),
                label = "Quiz step",
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    val enter =
                        slideInHorizontally(spring(stiffness = 650f)) { it / 8 * direction } +
                            fadeIn(spring(stiffness = 650f))
                    val exit =
                        slideOutHorizontally(spring(stiffness = 650f)) { -it / 8 * direction } +
                            fadeOut(spring(stiffness = 650f))
                    (enter togetherWith exit).using(null)
                },
            ) { page ->
                val current = quiz.questions[page]
                val currentChosen = selection(selections, current.id)
                QuizStep(
                    question = current,
                    chosen = currentChosen,
                    revealed = quiz.showAnswersImmediately && currentChosen.isNotEmpty(),
                    onChoose = { option ->
                        selections = JSONObject(selections).put(current.id, JSONArray(listOf(option))).toString()
                    },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index > 0) {
                    OutlinedButton(
                        onClick = { index-- },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Back") }
                }
                Button(
                    onClick = { if (index < quiz.questions.lastIndex) index++ else submit() },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(if (index == quiz.questions.lastIndex) "See results" else "Next")
                }
            }
        }
    }
}

private fun selection(
    selections: String,
    questionId: String,
): List<Int> {
    val array = JSONObject(selections).optJSONArray(questionId) ?: return emptyList()
    return (0 until array.length()).map { array.optInt(it) }
}

@Composable
private fun QuizStep(
    question: QuizQuestion,
    chosen: List<Int>,
    revealed: Boolean,
    onChoose: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(question.prompt, style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            question.options.forEachIndexed { position, option ->
                val selected = position in chosen
                val correct = position in question.correct
                val container =
                    when {
                        revealed && correct -> cs.successContainer
                        revealed && selected -> cs.errorContainer
                        selected -> cs.primaryContainer
                        else -> cs.surfaceContainerHigh
                    }
                val foreground =
                    when {
                        revealed && correct -> cs.onSuccessContainer
                        revealed && selected -> cs.onErrorContainer
                        selected -> cs.onPrimaryContainer
                        else -> cs.onSurface
                    }
                QuizOptionRow(
                    label = option,
                    container = container,
                    foreground = foreground,
                    selected = selected,
                    revealed = revealed,
                    correct = correct,
                    shape = groupedRowShape(position, question.options.size),
                    onClick = { if (!revealed) onChoose(position) },
                )
            }
        }
        if (revealed) {
            val right = question.correct.containsAll(chosen) && chosen.containsAll(question.correct)
            Text(
                if (right) "Correct" else "Not quite",
                style = MaterialTheme.typography.titleSmall,
                color = if (right) cs.success else cs.error,
            )
            if (question.explanation.isNotBlank()) {
                Surface(
                    color = cs.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Explanation",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                        )
                        MarkdownBody(question.explanation)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizOptionRow(
    label: String,
    container: Color,
    foreground: Color,
    selected: Boolean,
    revealed: Boolean,
    correct: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !revealed,
        color = container,
        shape = shape,
        modifier =
            Modifier.fillMaxWidth().semantics {
                role = Role.RadioButton
            },
    ) {
        ListItem(
            headlineContent = { Text(label, color = foreground) },
            leadingContent = {
                when {
                    revealed && correct ->
                        Icon(
                            Icons.Outlined.CheckCircle,
                            "Correct answer",
                            tint = foreground,
                        )
                    revealed && selected ->
                        Icon(
                            Icons.Outlined.Cancel,
                            "Your answer, incorrect",
                            tint = foreground,
                        )
                    else -> RadioButton(selected = selected, onClick = null)
                }
            },
            colors =
                ListItemDefaults.colors(
                    containerColor = container,
                    headlineColor = foreground,
                ),
        )
    }
}

@Preview(name = "Quiz light")
@Preview(name = "Quiz dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Quiz large text", fontScale = 1.5f)
@Composable
private fun AgentQuizPreview() {
    HydraTheme {
        AgentQuizSheet(
            AgentQuiz(
                "Kotlin essentials",
                listOf(
                    QuizQuestion(
                        "nullability",
                        "Which operator safely accesses a nullable property?",
                        listOf("!!", "?.", "?:, applied to a value", "as?"),
                        listOf(1),
                        "The safe-call operator `?.` returns null instead of throwing when the receiver is null.",
                    ),
                    QuizQuestion(
                        "coroutines",
                        "What does `suspend` change about a function?",
                        listOf("It blocks the calling thread", "It may pause and resume without blocking", "It runs on a new thread"),
                        listOf(1),
                        "A suspending function can pause at suspension points and resume later without blocking a thread.",
                    ),
                ),
                showAnswersImmediately = true,
            ),
            {},
            {},
        )
    }
}
