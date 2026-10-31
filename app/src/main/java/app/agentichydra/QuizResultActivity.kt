package app.agentichydra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

/** A read-only report for a finished quiz; opened from the sheet and from "View quiz results". */
class QuizResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isAppDarkTheme(this)) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val attempt =
            intent
                .getStringExtra(EXTRA_ATTEMPT)
                ?.let { raw -> runCatching { QuizAttempt.fromJson(JSONObject(raw)) }.getOrNull() }
        setContent { HydraTheme { QuizResultScreen(attempt) { finish() } } }
    }

    companion object {
        private const val EXTRA_ATTEMPT = "quiz_attempt"

        fun open(
            context: Context,
            attempt: QuizAttempt,
        ) {
            val intent =
                Intent(context, QuizResultActivity::class.java)
                    .putExtra(EXTRA_ATTEMPT, attempt.json().toString())
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuizResultScreen(
    attempt: QuizAttempt?,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("Quiz results") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        if (attempt == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                HydraEmptyState(
                    Icons.Outlined.Quiz,
                    "No quiz to show",
                    "Finish a quiz in a conversation and its report appears here.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "score") { QuizScoreCard(attempt) }
            itemsIndexed(attempt.quiz.questions, key = { _, question -> question.id }) { index, question ->
                QuizQuestionResult(
                    index = index,
                    question = question,
                    chosen = attempt.selected[question.id].orEmpty(),
                    correct = attempt.score(question),
                )
            }
        }
    }
}

@Composable
private fun QuizScoreCard(attempt: QuizAttempt) {
    val cs = MaterialTheme.colorScheme
    val percent = if (attempt.total == 0) 0 else (attempt.correctCount * 100f / attempt.total).roundToInt()
    Surface(
        color = cs.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                attempt.quiz.title,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${attempt.correctCount} / ${attempt.total}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "$percent% correct",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                QuizScoreChip(
                    Icons.Outlined.CheckCircle,
                    "${attempt.correctCount} correct",
                    cs.success,
                )
                if (attempt.correctCount < attempt.total) {
                    QuizScoreChip(
                        Icons.Outlined.Cancel,
                        "${attempt.total - attempt.correctCount} missed",
                        cs.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizScoreChip(
    icon: ImageVector,
    label: String,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuizQuestionResult(
    index: Int,
    question: QuizQuestion,
    chosen: List<Int>,
    correct: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurfaceVariant,
                )
                Text(question.prompt, style = MaterialTheme.typography.titleMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                question.options.forEachIndexed { position, option ->
                    val picked = position in chosen
                    val right = position in question.correct
                    val (container, foreground) =
                        when {
                            right -> cs.successContainer to cs.onSuccessContainer
                            picked -> cs.errorContainer to cs.onErrorContainer
                            else -> cs.surfaceContainerHigh to cs.onSurface
                        }
                    Surface(
                        color = container,
                        shape = groupedRowShape(position, question.options.size),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                when {
                                    right -> Icons.Outlined.CheckCircle
                                    picked -> Icons.Outlined.Cancel
                                    else -> Icons.Outlined.RadioButtonUnchecked
                                },
                                if (right) "Correct answer" else if (picked) "Your answer, incorrect" else null,
                                Modifier.size(20.dp),
                                tint = if (right || picked) foreground else cs.onSurfaceVariant,
                            )
                            Text(
                                option,
                                style = MaterialTheme.typography.bodyMedium,
                                color = foreground,
                                modifier = Modifier.weight(1f),
                            )
                            if (picked) {
                                Text(
                                    "Your answer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = foreground,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                if (correct) "Correct" else "Missed",
                style = MaterialTheme.typography.labelMedium,
                color = if (correct) cs.success else cs.error,
            )
            if (question.explanation.isNotBlank()) {
                HorizontalDivider(color = cs.outlineVariant)
                Text("Explanation", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                MarkdownBody(question.explanation)
            }
        }
    }
}

@Preview(name = "Quiz results light")
@Preview(name = "Quiz results dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Quiz results large text", fontScale = 1.5f)
@Composable
private fun QuizResultPreview() {
    val quiz =
        AgentQuiz(
            "Kotlin essentials",
            listOf(
                QuizQuestion(
                    "nullability",
                    "Which operator safely accesses a nullable property?",
                    listOf("!!", "?.", "as?", "lateinit"),
                    listOf(1),
                    "The safe-call operator `?.` returns null instead of throwing when the receiver is null.",
                ),
                QuizQuestion(
                    "coroutines",
                    "What does `suspend` change about a function?",
                    listOf("It blocks the calling thread", "It may pause and resume without blocking"),
                    listOf(1),
                    "A suspending function can pause at suspension points and resume later without blocking a thread.",
                ),
            ),
        )
    HydraTheme {
        QuizResultScreen(
            QuizAttempt(quiz, mapOf("nullability" to listOf(1), "coroutines" to listOf(0))),
            {},
        )
    }
}
