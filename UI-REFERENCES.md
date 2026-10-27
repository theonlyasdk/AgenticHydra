# Model sheet revision — 26 October 2026

## History and sheet interaction update

- Models uses exclusive credential-required, loading, empty, and catalog states. Missing required credentials show a key icon and Add a provider API key with Connect instead of Done; the no-models placeholder is suppressed. Search appears only for a usable nonempty catalog. Done is disabled for an empty catalog unless a custom ID has been selected or is entered in the manual field; Done can commit that draft directly. Explicit manual selection is remembered per provider and endpoint.
- The visible gaps from provider chips to Search and from Search to the first model row are both 24dp at normal font scale, accounting for Material's internal chip and SearchBar spacing. The handle remains null. Chat edge fades are drawn as dedicated overlays above scrolling content at the Hydra-bar and composer boundaries.

- Timestamp dividers fill the chat width symmetrically. Model-change markers are centered and show the previous model → new model with provider icons; new markers persist both sides, while earlier markers infer available previous-model metadata from history. Response speed, elapsed time, and clock time share one line with ellipsis and a full accessibility label.
- Chat fades into the Hydra bar and composer at the viewport edges, with 16dp vertical edge padding. All sheets stay below status-bar/cutout safe insets plus an 8dp gap and have 16dp internal header breathing room, while keeping drag handles removed.
- Appearance offers persisted System, Light, and Dark modes plus device colors. Theme choices apply immediately, including system-bar icon contrast. Appearance and tool approvals share the grouped M3 tonal rows with 4dp gaps and outer rounded group corners.

- Every modal sheet passes a null drag handle, removing both the handle and its layout space. Models keeps the native sheet drag behavior on its header while its full-width body consumes edge overscroll and unhandled vertical drags, so list scrolling cannot dismiss the sheet. Done, Back, and outside-tap dismissal remain available.

- Chat follows the measured bottom anchor only while the reader stays at the end. A gesture toward older messages releases it immediately; returning to the end or using Jump to latest resumes following. No per-token scroll-to-message-start calls remain.
- New chat messages persist wall-clock timestamps, selected-model metadata, and completed-response metrics. User turns have timestamp dividers; model switches have timeline markers. Responses expose Copy, Share, elapsed time, completion time, and tokens/second (marked approximately when estimated). Legacy chats retain their content without invented historic timestamps.
- Connections has no unchecked-save action. A manual model-ID field supports verification on servers without discovery. Thinking and connection status share a brighter white-peaked shimmer.
- All sheet heights use a single 180ms Material ease-out transition. Inner phase transitions do not animate their size again.

- Connections now offers Check & save in the same sheet. Checking replaces the form with the actual Material 3 Expressive morphing loading indicator, a themed shimmer label, and spring-driven upward message transitions every three seconds. Vertical fades soften the label edges. Success and failure stay in the sheet, and checking can be cancelled.
- All modal sheets use the shared 180ms ease-out height animation, including approvals, files, reviews, appearance, privacy, and Termux setup. Models has a title icon and fades the model list beneath Search as well as the manual-ID action.

- Conversation history uses single-line marquee titles fading into the row's semantic surface at the scrolling edges. Long press or the overflow action opens a Material menu for Rename, Export (Markdown through the system file picker), and Delete. Destructive actions ask for confirmation. The supplied ChatGPT screenshot guides menu hierarchy; only Hydra's supported actions are shown.
- Work history has a per-record delete action; deleting a record keeps project files intact.
- A persistent Material navigation bar lets selection changes animate between Chat, Work, and User.
- Model providers use compact default M3 chip heights with accessible touch targets. An 8dp header spacer accounts for the components' internal insets so the visible gap above the chips matches their gap from Search. Selected models use primaryContainer plus a checkmark. The model list fades into the manual-ID action.
- Manual entry slides into place while the sheet eases to its new height. Button dismissal waits for the sheet's slide-down before removing it. Models has Done as its sole dismissal button.
- Local SDK/JDK paths live only in the ignored `.android-env.md`, excluded from source packages.

Sheet dismissal follows [Android's bottom-sheet guidance](https://developer.android.com/develop/ui/compose/components/bottom-sheets): hide the sheet before removing it from composition.

## Original 1.2 references

References checked before implementation:

- [Material 3 segmented control guidance](https://m3.material.io/components/segmented-buttons/guidelines)
- [Material 3 lists](https://m3.material.io/components/lists/overview)
- [Android Compose sliders: custom thumb/track and discrete selection](https://developer.android.com/develop/ui/compose/components/slider)
- [Android Material component guidance](https://developer.android.com/design/ui/mobile/guides/components/material-overview)

The reasoning control uses a real M3 Slider with labeled TextButtons. Pointer input supports horizontal swipes anywhere across the control, while the Slider preserves keyboard/accessibility adjustment. Spring motion moves the accent-filled thumb between labels. The duplicate header, value badge, border, and divider were removed. Default is retained as a real option because it omits the provider override.

Model lists and settings use separated tonal rows, rounded at group boundaries. The selected model is indicated by a checkmark; no separate selected card is created. Enter model ID spans the full available width. Existing typography sizes remain unchanged. New components use theme shapes and semantic colors. Light/dark/large-font previews are provided for the stateless reasoning UiState.

Brand assets are bundled Android vectors converted from the following upstream SVG artwork:

- [Simple Icons](https://github.com/simple-icons/simple-icons): NVIDIA, Gemini, Ollama, Meta, Anthropic and Alibaba Cloud; CC0 license included in licenses/.
- [Lobe Icons](https://github.com/lobehub/lobe-icons): OpenAI; MIT license included in licenses/.

Brand marks identify the model owner when recognized, then its provider. Unknown/custom brands use Hydra's own identity rather than inventing a brand icon. Marks are monochrome and tinted by MaterialTheme; no external icon service receives runtime requests. Trademarks remain those of their respective owners.

Tidal Glass remains the default. Dynamic Material colors can be enabled in User → Appearance on Android 12+, with Tidal Glass as the fallback. This revision does not claim new live-provider or physical-device Termux validation.
