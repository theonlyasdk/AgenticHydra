# UI rules (Jetpack Compose, Material 3)

## Communication

- Write the way a calm, friendly person would talk in a chat. Plain words, normal sentences.
- Never use em dashes or en dashes. Use a period or a comma instead.
- Don't open with an acknowledgment like "Understood", "Got it", "Sure", or "Done".
- Don't use report language like "currently installed", "already includes", "as requested", or "per your instructions".
- Don't use slashes between words, arrows, or semicolons.
- Don't use internal jargon or labels from your own work. Describe things the way I would.
- Keep it short by saying less, not by compressing. Skip details I didn't ask about.
- Say what happened or answer the question, then stop. No recap, no summary of your edits, no offer of more help.
- If I tell you how to work (for example, not to run the app), just follow it. Don't repeat it back to me.
- Ask a question only when you're truly blocked. Otherwise pick the sensible option and mention it in a few words.

## Reply Style

- Write replies in plain, natural sentences, like a person talking to a colleague.
- Short is fine, but never compressed. Don't drop words to save space.
- Don't use slashes to join words (write "and" or "or", not "this/that").
- Don't use colons, semicolons, or arrows to chain fragments together.
- Don't use shorthand, abbreviations, or telegraphic phrasing.
- Don't summarize your own edits by listing the rules or contents you wrote. Just say what you did, for example: "I added a Communication section to AGENTS.md."
- Don't describe the file's contents back to me. I can read it.
- Each reply should read as one or two complete, ordinary sentences.

## Work Habits

- Don't narrate your steps while working. Only speak when you're finished or blocked.
- Don't re-read or re-verify files you just edited unless something failed.
- Don't explain a fix I already understood. If I report a bug, fix it and say so in one sentence.
- If a task is ambiguous, pick the most likely meaning and go. Don't list options.
- Never invent details about the project. If you don't know something, check the code or say you don't know.
- Don't mention tools, commands, or file paths you used unless I need them.
- When something fails, tell me what went wrong in plain words, not the full log.

## Execution

- Do the task I gave. Treat the request as the whole job, not a starting point.
- Read the relevant code before changing anything. Find where the thing lives, then edit it.
- Never guess about the codebase. Don't invent classes, methods, resource names, or dependencies. Search for them. If they don't exist, say so.
- Follow the patterns already in the project: naming, structure, formatting, how similar things are done. Don't introduce new libraries or new approaches.
- Make the smallest change that fully solves the problem. Fix the cause, not the symptom.
- Don't refactor, rename, reformat, or "improve" code outside the task, even if it looks bad.
- If a request has two reasonable readings, take the narrower one.
- Don't add fallbacks, null checks, try/catch blocks, or config options for cases that can't happen.
- Don't leave TODOs, placeholder code, or half-finished work. Finish it or say what blocked you.
- After editing, make sure the project still builds. If it fails, fix it before replying.
- If something fails, find the root cause. Don't retry the same thing or stack workarounds on top.
- If you hit a real blocker (missing info, a destructive action, a conflict with an earlier instruction), stop and ask one specific question. Otherwise keep going.
- Never undo or overwrite my changes that you didn't make. If the working tree has edits you don't recognize, leave them alone.
- Don't delete files, reset git state, or run destructive commands unless I asked.

## Local builds and delivery
- Read `.android-env.md` when present for the local SDK, JDK, signing, and ADB setup.
- Deployment mode: before installing an APK, ask the user whether to use wireless ADB or USB. Remember the choice for later installs in the same session and reuse it (fall back to the other mode only if deployment fails).
- After producing an APK, check for connected ADB devices and install it when one is available.
- Preserve existing app data; obtain explicit approval before uninstalling an app or clearing its data to resolve a signing mismatch.
- Do not create a source ZIP unless the user requests one.

## Code style
- Keep code neat and formatted; match the surrounding style and remove dead code or unused imports.
- Use clear, concise variable and function names. Avoid long, ambiguous, or cryptic abbreviations.
- Prefer small single-purpose helpers and early returns over deep nesting.
- Build after edits and fix any warnings you introduced.

## Theme and tokens
- NEVER hardcode colors, text styles, shapes, or fonts in composables.
  Use only MaterialTheme.colorScheme / typography / shapes.
- Use semantic roles correctly: primary/onPrimary for key actions,
  primaryContainer for emphasis, surfaceContainerLow/High/Highest for
  elevated layers (not shadows), secondary/tertiary for accents,
  outlineVariant for dividers, error only for errors.
- Support dark theme and dynamic color (Android 12+) with a fallback
  static scheme. Never assume light mode.
- Do not use alpha hacks on colors to fake roles; pick the right role.

## Layout
- Use a 4dp grid. Screen padding 16dp (24dp on expanded width). Spacing
  between groups 24dp, within groups 8-12dp.
- Prefer Scaffold + proper slots (topBar, bottomBar, FAB, snackbar).
- Handle insets with WindowInsets / enableEdgeToEdge. No fixed status bar padding.
- Use adaptive layouts: check WindowSizeClass, don't stretch phone UI on tablets.
- No Card inside Card. Use surface tone differences and spacing, not borders everywhere.

## Components
- Use real M3 components (ListItem, SegmentedButton, FilterChip, ModalBottomSheet,
  NavigationBar, SearchBar, Slider, Switch, TopAppBar with scroll behavior).
  Do not rebuild them from Box + Text.
- One primary action per screen. Others are tonal, outlined, or text buttons.
- Touch targets at least 48dp. Every icon has a contentDescription (or null if decorative).
- Use Material Symbols / icons consistently, with one style (Rounded or Outlined).
- Every popup menu, overflow menu, and dropdown uses the shared rounded popup
  `HydraOverflowMenu` with `HydraMenuItem` rows from `HydraComponents.kt`
  (large shape, `surfaceContainerHigh`, optional header). Never build a raw
  `DropdownMenu`, `Popup`, or one-off menu surface in a screen; add options or
  parameters to that shared component instead so every popup looks identical.
- Every options/overflow entry point uses the horizontal three-dot icon
  (`Icons.Outlined.MoreHoriz`). Never use the vertical `MoreVert` icon; the
  horizontal dots are mandatory across the whole app.

## Shape and motion
- Hard limit: bottom sheets have no drag handle or reserved handle space. In Models, only header drags may move/dismiss the sheet; body/list gestures must stay within the content.
- All sheets keep safe top clearance using WindowInsets.safeDrawing, an 8dp gap below the safe area, and 16dp content padding above the header. This is normal spacing, not reserved handle space.
- Bottom sheet height changes use the user's requested snappy 180ms ease-out transition; avoid stacking a second size animation inside the sheet.
- Use MaterialTheme.shapes only. Larger radii for containers, smaller for chips.
- Animate state changes with animateContentSize, AnimatedVisibility, and spring
  specs. No linear or tween-only motion on interactive elements.
- Add press/selection feedback via the component defaults, not custom ripples.

## Quality bar
- Every screen needs: loading, empty, error, and content states.
- Add @Preview for light, dark, and large font scale (fontScale = 1.5f).
- Text must never truncate unexpectedly at 1.5x font scale.
- Keep composables stateless: state hoisting, a UiState data class, previews with fake data.
- After changes, run ./gradlew assembleDebug and fix all warnings you introduced.

## Don'ts
- No gradients/glassmorphism unless asked. No emoji as icons. No centered-everything layouts.
- No placeholder "Lorem ipsum" or blue-purple default look. Respect the app's seed color.
- Never use the default device font. Always use the app's custom font.
