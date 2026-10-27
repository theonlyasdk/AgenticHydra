# UI rules (Jetpack Compose, Material 3)

## Local builds and delivery
- Read `.android-env.md` when present for the local SDK, JDK, signing, and ADB setup.
- After producing an APK, check for connected ADB devices and install it when one is available.
- Preserve existing app data; obtain explicit approval before uninstalling an app or clearing its data to resolve a signing mismatch.
- Do not create a source ZIP unless the user requests one.

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
