package app.agentichydra

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch

/** Keep the sheet composed until its exit animation finishes, including button dismissal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HydraBottomSheet(
    onDismiss: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var closing by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            keyboard?.hide()
            scope.launch {
                try {
                    state.hide()
                    if (!state.isVisible) latestDismiss()
                } finally {
                    closing = false
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = { latestDismiss() },
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 8.dp),
        sheetState = state, containerColor = containerColor, dragHandle = null) {
        Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)).padding(top = 16.dp)) { content(dismiss) }
    }
}

/** Let child lists scroll normally, but never hand their edge overscroll to the sheet. */
@Composable fun Modifier.sheetContentScrollOnly(): Modifier {
    val scrollBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    // Child scrollables get first refusal. Unhandled drags on controls, whitespace, or
    // list edges stop here instead of reaching ModalBottomSheet's parent draggable.
    return nestedScroll(scrollBoundary).draggable(rememberDraggableState { }, Orientation.Vertical)
}
