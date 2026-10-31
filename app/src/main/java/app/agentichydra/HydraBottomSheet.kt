package app.agentichydra

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keep the sheet composed until its exit animation finishes, including button dismissal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HydraBottomSheet(
    onDismiss: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    lock: Boolean = false,
    fillHeight: Boolean = false,
    swipeEnabled: Boolean = true,
    onBackRequest: (() -> Unit)? = null,
    heading: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    if (swipeEnabled) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val keyboard = LocalSoftwareKeyboardController.current
        var closing by remember { mutableStateOf(false) }
        val latestDismiss by rememberUpdatedState(onDismiss)
        val latestBack by rememberUpdatedState(onBackRequest)
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
        ModalBottomSheet(
            onDismissRequest = { (latestBack ?: latestDismiss)() },
            modifier =
                Modifier
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                    ).padding(top = 8.dp),
            sheetState = state,
            containerColor = containerColor,
            dragHandle = null,
            contentWindowInsets = { if (lock) WindowInsets(0, 0, 0, 0) else BottomSheetDefaults.windowInsets },
        ) {
            SheetBody(fillHeight, lock, heading, content, dismiss, state)
        }
    } else {
        Dialog(
            onDismissRequest = { (onBackRequest ?: onDismiss)() },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    dismissOnClickOutside = false,
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss)
                    .imePadding(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.heightIn(max = 640.dp))
                            .pointerInput(Unit) { detectTapGestures { } },
                    color = containerColor,
                    shape = BottomSheetDefaults.ExpandedShape,
                ) {
                    Column(
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(top = 8.dp),
                    ) {
                        SheetBody(fillHeight, lock, heading, content, onDismiss, sheetState = null)
                    }
                }
            }
        }
    }
}

/** After the sheet tops out, give the entrance a beat to settle before allowing size changes. */
private const val SHEET_SETTLE_MILLIS = 250L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetBody(
    fillHeight: Boolean,
    lock: Boolean,
    heading: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)?,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    dismiss: () -> Unit,
    sheetState: SheetState?,
) {
    // Size animation stays off for the whole entrance: enabling it while the sheet is still
    // sliding re-measures the content and shows up as a jitter just before it tops out.
    var animateSize by remember { mutableStateOf(false) }
    LaunchedEffect(sheetState) {
        if (sheetState == null) {
            animateSize = true
            return@LaunchedEffect
        }
        snapshotFlow { sheetState.currentValue == SheetValue.Expanded }
            .distinctUntilChanged()
            .collect { expanded ->
                if (expanded) {
                    delay(SHEET_SETTLE_MILLIS)
                    animateSize = true
                } else {
                    animateSize = false
                }
            }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .then(
                if (animateSize) {
                    Modifier.animateContentSize(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing))
                } else {
                    Modifier
                },
            ).padding(top = 16.dp),
    ) {
        if (heading == null) {
            content(dismiss)
        } else {
            heading(dismiss)
            Column(
                Modifier
                    .then(
                        if (lock) Modifier.draggable(rememberDraggableState {}, Orientation.Vertical) else Modifier,
                    ).then(
                        if (lock) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) { content(dismiss) }
        }
    }
}

/** Let child lists scroll normally, but never hand their edge overscroll to the sheet. */
@Composable
fun Modifier.sheetContentScrollOnly(): Modifier {
    val scrollBoundary =
        remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ) = Offset(0f, available.y)

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ) = Velocity(0f, available.y)
            }
        }
    // Child scrollables get first refusal. Unhandled drags on controls, whitespace, or
    // list edges stop here instead of reaching ModalBottomSheet's parent draggable.
    return nestedScroll(scrollBoundary).draggable(rememberDraggableState { }, Orientation.Vertical)
}
