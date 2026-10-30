This is a single self-contained fix; keep it small and focused.
In both Chat and Work Chat, display the "Hydra" title only for the final message when all tasks are complete, hiding it during thinking and intermediary output, with an Appearance menu toggle to switch between showing it on the final message (default) and hiding it completely across both tabs.

Integrity mode: development

## Requirements

### R1. Restrict "Hydra" title display to final summary message
In both Chat and Work Chat tabs, the "Hydra" title must not be shown when thinking happens or when intermediary clarifying lines are output. The "Hydra" title must only be displayed on the final summary message after all tasks have finished.

### R2. Appearance menu toggle
Provide a setting in the Appearance menu to toggle the "Hydra" title display behavior across both Chat and Work Chat tabs:
- Default state: "Hydra" title is displayed only on the final summary message.
- Alternative state: "Hydra" title is completely hidden on all messages.

## Acceptance Criteria

### Display Behavior
- [ ] In the Chat tab, the "Hydra" title is hidden for thinking and intermediary clarifying lines while tasks are executing.
- [ ] In the Work Chat tab, the "Hydra" title is hidden for thinking and intermediary clarifying lines while tasks are executing.
- [ ] In both Chat and Work Chat tabs, the "Hydra" title appears on the final message once all tasks are complete.

### Appearance Settings
- [ ] An option exists in the Appearance menu to toggle the "Hydra" title display behavior.
- [ ] When the toggle is in the default state, the "Hydra" title shows only on the final summary message.
- [ ] When toggled to the alternative state, the "Hydra" title does not appear on any messages in either Chat or Work Chat.
- [ ] The setting's state persists across app restarts and applies consistently to both tabs.

### Verification
- [ ] `./gradlew assembleDebug` succeeds with zero errors and no new warnings introduced.
- [ ] Automated unit or Compose tests verify the toggle state logic and the message display condition.
