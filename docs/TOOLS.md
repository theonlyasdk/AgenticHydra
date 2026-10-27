# Work tools

Hydra exposes 17 tools to compatible models. Availability depends on project selection, permissions, approval policy, and—for shell and Git—Termux setup. Tool requests and results appear inline in the Work conversation.

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
| `fetch_url` | Fetch text from a public HTTPS page | Network |
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

The default policy asks for approval. Inspect the arguments before allowing an action, especially writes and shell commands. Work retains tool results in the task context for follow-ups. Cancellation stops subsequent steps; a command already handed to Termux may continue until stopped there.
