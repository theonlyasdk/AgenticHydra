# Agentic Hydra · Tidal Glass

## Foundation

Light: background #F3F7FA; text #182630; secondary text #536570; accent #006B75; accent surface #D5EEF0; composer #FFFFFF.
Dark: background #10191F; text #DFEAF0; secondary text #ABBDC8; accent #80D5DE; accent surface #173A41; composer #19262E.

Bundled Instrument Sans for controls and body. Instrument Serif for welcome/section introductions. Type: 42/46 display, 32/38 headline, 22/28 title, 16/25 body, 14/21 supporting, 12/16 label, 11/16 caption. Units are scalable sp.

Spacing: 8dp grid; 4dp only for small optical adjustments. 16dp control radii, 24dp messages, 28dp composer, circular send button. Flat tonal surfaces; 2dp composer shadow; no background blur or gradients. Material outlined icons, generally 24dp inside 48dp touch targets.

## Component states

Buttons: tonal default, Material ripple pressed, Material disabled colors. Send disabled for blank input; becomes Stop while streaming. Inputs: focused accent, blank placeholder, disabled while streaming. API key masked with reveal control. Errors: warm semantic surface and actionable message; dismissible. Loading: spinner plus Thinking label before streamed tokens. Empty: welcome, three prompt starters; history explains local storage. Attachments: named chip with remove action; limit and unsupported-file errors appear in snackbar. Sheets support keyboard, scrolling, and system dismissal.

## Navigation

Chat → history drawer → saved conversation or new chat.
Chat → provider badge → connection sheet.
User → Connections / Termux setup / approval settings.
Work → shared project picker → Task / Files / Changes / History.
Task → tool approval → execution result → next agent turn → summary.
Changes → before/after sheet. Files → folder navigation → file viewer.

All three destinations are implemented. PC pairing remains deferred.

## Self-review

Contrast: checked light and dark primary/secondary text against their actual surfaces using WCAG relative luminance. Minimum body contrast is AA. No subtle color alone signals action state; labels accompany loading/error/selection.
Targets: interactive controls use at least 48dp; send is 48dp; suggestion rows at least 56dp. Increased remove-attachment and provider chip targets to 48dp.
Hierarchy: one display heading, three simple prompt rows, one primary composer action. No competing cards. Starters fill the composer without sending: users can edit first.
Consistency: colors and type are centralized in Theme.kt, shared by the screen, drawer, and sheet.
Fitts: primary action sits near the thumb. Hick: only three starters and three persistent destinations. Recognition over recall: provider name and Chat mode stay visible. User control: Stop, editable starters, copy, new chat, and attachment removal.
Fixes applied: limited wide-screen content; scrollable welcome and sheets for larger fonts; keyboard-aware composer; visible errors for missing key, invalid endpoint, oversized attachment, and provider failure.

Remaining verification: no device/emulator interaction test was run. Actual provider streaming requires the user's own credentials and reachable server. Contrast math and a build/lint pass do not establish usability on every Android device.

## Work and User review

Work separates planning, approval, execution, and completed results using text status and expandable tool steps. The default policy asks for writes and all shell commands. The file browser and tool actions stay within selected shared storage for SAF operations. Shell access follows Termux permissions; the approval dialog explains that scope. Automatic mode requires an explicit second confirmation and resets after the task.

Task/tool failures remain visible; stopped and interrupted steps are labeled. Connection settings are frozen per task, preventing mid-task provider changes from mixing credentials or endpoints. Stop cannot promise to terminate a command already executing in Termux.

User groups connections and Termux setup above secondary preferences. No account or sign-in flow was invented. English is the only language; system light/dark theme follows Android. Settings rows use 48dp minimum targets. Both dark and light token contrast are checked by the included contrast script.

Known limits: no hosted CI run, emulator screenshots, device tests, or live provider/Termux tests are claimed. File writes record before/after content; shell edits require git diff review.

## Version 1.1 revision

User references informed the composer placement, model search, broad reasoning slider, grouped settings, and use of sheets. Marketing lines and large Work hero copy were removed. Empty Chat keeps one functional prompt and a small Hydra/code/terminal illustration. The model control sits beside attachment/send controls. The top bar contains history, brand, and new-chat actions.

Work now has a project row, four icon tabs, structured task/tool rows, and a New task action that opens a composer sheet. Model search, creation, tool approval, mode selection, file preview, and changes review use bottom sheets. Only high-impact automatic-approval confirmation uses a dialog. Settings group related entries in shared surfaces with dividers instead of many separate cards.

All Material color families and surface-container roles are explicitly mapped to Tidal Glass so secondary controls cannot inherit Material's default palette. Nonessential muted captions are removed. Functional connection errors, status labels and model IDs remain where useful.

Markdown uses native TextViews through Markwon, with explicit theme colors and bundled fonts. Code blocks use separate selectable, scrollable Compose surfaces with Copy controls. No WebView, JavaScript, or remote-image loading is used. Full-message Copy retains original Markdown including table contents.

Verification now includes Robolectric native UI tests and rendered screenshots, rather than only compilation. These complement the protocol fixture tests; they do not replace a physical-device or live-authentication test. Keyboard-aware model sheets hide secondary reasoning controls during search so the model list stays usable.

Tables render with Compose rows and horizontally scroll when necessary. Columns expand to fill available width, while longer tables keep readable minimum column widths. This replaces the initial native TextView table span after visual checks exposed a clipped last row. Native UI test classes run in separate JVMs to isolate Robolectric window registries and Compose animation clocks.
