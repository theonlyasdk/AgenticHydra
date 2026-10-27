# Work tools

Hydra exposes 48 tools to compatible models. Availability depends on project selection, permissions, approval policy, and—for shell and Git—Termux setup. Tool requests and results appear inline in the Work conversation.

| Tool | Purpose | Runtime |
| :-- | :-- | :-- |
| `list_files` | List entries in a project directory | Android folder access |
| `read_file` | Read a UTF-8 project file | Android folder access |
| `write_file` | Create or overwrite a project file | Android folder access |
| `search_files` | Search text across project files | Android folder access |
| `find_files` | Find project paths by name | Android folder access |
| `file_info` | Inspect a file's metadata | Android folder access |
| `create_directory` | Create a project directory | Android folder access |
| `append_file` | Append text to a file | Android folder access |
| `replace_in_file` | Replace exact text, with ambiguity checks | Android folder access |
| `delete_file` | Delete a single file | Android folder access |
| `move_file` | Move a file to an unused destination | Android folder access |
| `copy_file` | Copy a file to an unused destination | Android folder access |
| `web_search` | Search public results with DuckDuckGo and a Bing fallback | Network |
| `fetch_url` | Fetch paginated text from a public HTTPS page | Network |
| `web_search_batch` | Run up to three searches with two in parallel | Network |
| `find_in_page` | Find text and contextual excerpts in a cached page | Network |
| `ask_user` | Pause for a question wizard and resume with structured answers | User interaction |
| `run_command` | Run a shell command in the selected working directory | Termux |
| `git_status` | Inspect repository status | Termux + Git |
| `git_diff` | Inspect repository changes | Termux + Git |

## File boundaries

Native project tools use the directory granted through Android's Storage Access Framework. Paths must be relative to that project; absolute paths and traversal are rejected. Individual text operations are limited to 256 KiB. Directory listings return at most 150 entries. Text search scans at most 300 files and returns at most 100 matches, skipping common generated directories such as `.git`, `node_modules`, `.gradle`, and `build`.

Deletion does not recursively remove directories. Copy and move do not overwrite existing destinations. Exact replacement rejects ambiguous matches unless replacement of all matches is explicitly requested.

These native file boundaries do not restrict the shell. Termux commands run with Termux's own access to files and the network.

## Network boundaries

`fetch_url` accepts public HTTPS destinations on port 443, rejects private network destinations, validates redirects, and limits responses to 1 MiB. It fetches text; it does not execute JavaScript or act as an authenticated browser. Public search services can rate-limit requests or change their response format.

## Approval and cancellation

The default policy allows reads and asks before changes and shell commands. **Full access** permits tools without prompts and persists until changed. Inspect arguments before allowing an action. Tap a compact activity row to open its arguments and results in a bottom sheet. Work retains tool results in the task context for follow-ups. Cancellation stops subsequent steps; a command already handed to Termux may continue until stopped there.

## Search controls

`web_search` and `web_search_batch` accept `max_results` (1–10), `include_domains`, `exclude_domains` (up to four each), and `time_range` (`all`, `day`, `week`, `month`, `year`). Domains must be plain host names. The provider date filter is best effort; verify publication dates in original sources.

Results include source IDs, titles, canonical URLs, snippets, retrieval times, and a cache flag. Duplicate URLs and common tracking parameters are removed. Searches cache for five minutes. Batch search preserves an error for each failed query and limits concurrency to two requests. Result budgets preserve valid JSON rather than cutting a result in half.

`fetch_url` returns `next_offset` when more text is available. Pass that offset to read another section, or use `find_in_page` for literal matches and short excerpts. Parsed page text is cached for five minutes; downloads remain limited to 1 MiB and public HTTPS destinations.

The remaining 21 tools access Android device features. Their complete names, permission rules, and interaction limits are in [Device tools and questions](DEVICE_TOOLS.md).

## Time and scheduling

Seven additional tools manage time: `get_time`, `wait_for`, `wait_until`, `schedule_task`, `list_scheduled_tasks`, `update_scheduled_task`, and `cancel_scheduled_task`.

Short waits pause the current turn for up to five minutes, release the UI thread, and end immediately when the task is stopped. They do not survive process termination. Longer follow-ups use persistent Android WorkManager jobs and resume the saved conversation with its project and captured model configuration. API keys remain in the existing encrypted store; an endpoint change blocks the schedule until it is updated.

`schedule_task` requires a title, prompt, and either `delay_seconds` or `run_at` (an ISO 8601 time with an explicit offset). Optional `repeat_interval_seconds` must be at least 900 seconds; `max_runs` caps repetitions at 100 and defaults to 10 for recurring work. Repeats use fixed elapsed intervals, skip missed occurrences, and never queue a catch-up burst. Single runs default to one execution. No more than 24 schedules can be active.

Android may delay jobs for network availability, battery optimization, or OS quotas. An active task or staged attachments defer a scheduled run. Current tool approvals still apply, and device actions/questions may require opening the conversation. Each scheduled run is limited to 15 minutes. Interrupted runs are marked for review and are not automatically replayed; future unstarted jobs persist across app restarts. Force-stopping the app prevents Android jobs until the app is opened again.

Use the clock button in **Work** to inspect or cancel schedules. Cancelling future work preserves project files. Termux commands already handed off may continue after cancellation.
