package app.agentichydra

import org.json.JSONArray

object WorkTools {
    /** Serialized size of the tool schemas, counted against the context window. */
    val schemaChars: Long get() = schema().toString().length.toLong()

    private val baseNames =
        buildList {
            addAll(
                listOf(
                    "list_files",
                    "list_files_recursive",
                    "read_file",
                    "write_file",
                    "run_command",
                    "search_files",
                    "find_files",
                    "file_info",
                    "read_image",
                    "create_directory",
                    "append_file",
                    "replace_in_file",
                    "apply_diff",
                    "delete_file",
                    "move_file",
                    "copy_file",
                    "web_search",
                    "fetch_url",
                    "download_file",
                    "git_status",
                    "git_diff",
                    "web_search_batch",
                    "find_in_page",
                    "start_task",
                    "read_task_output",
                    "ask_user",
                    "quiz_user",
                ),
            )
            addAll(DeviceTools.names)
            addAll(ScheduleTools.names)
        }

    val names: List<String>
        get() = baseNames + (app.agentichydra.mcp.McpToolRouter.registry?.allTools()?.map { it.namespaced() } ?: emptyList())

    fun baseSchema(): JSONArray {
        val path =
            "path" to stringSchema("Relative project path; use an empty string for the project root when listing or searching.")
        return JSONArray()
            .put(toolSchema("list_files", "List immediate children of a project directory.", listOf("path"), path))
            .put(
                toolSchema(
                    "list_files_recursive",
                    "List project files recursively, up to 300 files. Prefer this over repeated list_files calls.",
                    listOf("path"),
                    path,
                ),
            ).put(toolSchema("read_file", "Read a UTF-8 project file, maximum 256 KB.", listOf("path"), path))
            .put(
                toolSchema(
                    "write_file",
                    "Create or replace a UTF-8 file. Read existing files first. Maximum 256 KB.",
                    listOf("path", "content"),
                    path,
                    "content" to stringSchema("Complete new file content."),
                ),
            ).put(
                toolSchema(
                    "run_command",
                    "Execute a bash command in installed Termux at the project working directory. Requires Termux setup. Timeout 180 seconds; returns actual exit code and output.",
                    listOf("command"),
                    "command" to stringSchema("Shell command."),
                ),
            ).put(
                toolSchema(
                    "search_files",
                    "Search text in project files recursively. Literal search, returns paths and line numbers; scans at most 300 files and returns at most 100 matches.",
                    listOf("path", "query"),
                    path,
                    "query" to stringSchema("Literal text to find."),
                    "case_sensitive" to booleanSchema("Case-sensitive matching; default false."),
                ),
            ).put(
                toolSchema(
                    "find_files",
                    "Find project files recursively by filename glob, e.g. *.kt. At most 300 files.",
                    listOf("path", "pattern"),
                    path,
                    "pattern" to stringSchema("Filename glob with * and ? wildcards."),
                ),
            ).put(
                toolSchema(
                    "read_image",
                    "Load a project image (PNG, JPEG, WebP, or GIF up to 4 MB) and attach it to the next request so you can see it. Use before describing or editing image files.",
                    listOf("path"),
                    path,
                ),
            ).put(
                toolSchema(
                    "file_info",
                    "Read a project file or directory's type, size, MIME type, and modification time.",
                    listOf("path"),
                    path,
                ),
            ).put(
                toolSchema(
                    "create_directory",
                    "Create a project directory and any missing parent directories.",
                    listOf("path"),
                    path,
                ),
            ).put(
                toolSchema(
                    "append_file",
                    "Append UTF-8 text to a project file, creating it if missing. Maximum total 256 KB.",
                    listOf("path", "content"),
                    path,
                    "content" to stringSchema("Text to append."),
                ),
            ).put(
                toolSchema(
                    "replace_in_file",
                    "Replace exact text in a UTF-8 file. Fails on ambiguous matches unless replace_all is true.",
                    listOf("path", "old_text", "new_text"),
                    path,
                    "old_text" to stringSchema("Exact existing text; must not be empty."),
                    "new_text" to stringSchema("Replacement text."),
                    "replace_all" to booleanSchema("Replace all occurrences; default false."),
                ),
            ).put(
                toolSchema(
                    "apply_diff",
                    "Apply one or more search/replace edits to a UTF-8 file in a single call. Use for multi-spot edits instead of several replace_in_file calls. Each block: ------- SEARCH, old lines, =======, new lines, +++++++ REPLACE.",
                    listOf("path", "diff"),
                    path,
                    "diff" to
                        stringSchema(
                            "SEARCH/REPLACE blocks; each SEARCH must match exactly once and be short with distinct surrounding context.",
                        ),
                ),
            ).put(
                toolSchema(
                    "delete_file",
                    "Delete one project file, never a directory. Only use when explicitly requested by the user.",
                    listOf("path"),
                    path,
                ),
            ).put(
                toolSchema(
                    "move_file",
                    "Move or rename a UTF-8 project file. Destination must not exist. Maximum 256 KB.",
                    listOf("source", "destination"),
                    "source" to stringSchema("Existing relative file path."),
                    "destination" to stringSchema("New relative file path."),
                ),
            ).put(
                toolSchema(
                    "copy_file",
                    "Copy a UTF-8 project file. Destination must not exist. Maximum 256 KB.",
                    listOf("source", "destination"),
                    "source" to stringSchema("Existing relative file path."),
                    "destination" to stringSchema("New relative file path."),
                ),
            ).put(ResearchToolSchemas.webSearch())
            .put(ResearchToolSchemas.fetchUrl())
            .put(
                toolSchema(
                    "download_file",
                    "Download a public http or https file straight into the selected project with live progress. Use this instead of curl or wget. The destination must not already exist.",
                    listOf("url"),
                    "url" to stringSchema("Direct http or https file URL."),
                    "path" to
                        stringSchema(
                            "Relative destination file path, or a folder ending in /. Defaults to downloads/<file name>.",
                        ),
                ),
            ).put(
                toolSchema(
                    "git_status",
                    "Run git status --short --branch in Termux for the selected project. Always asks in Ask for changes mode.",
                    emptyList(),
                ),
            ).put(
                toolSchema(
                    "git_diff",
                    "Read the project's Git diff in Termux without external diff programs. Always asks in Ask for changes mode.",
                    emptyList(),
                    "path" to stringSchema("Optional relative path to restrict the diff."),
                    "staged" to booleanSchema("Show staged changes; default false."),
                ),
            ).put(ResearchToolSchemas.webSearchBatch())
            .put(ResearchToolSchemas.findInPage())
            .put(
                toolSchema(
                    "start_task",
                    "Start an independent read-only background task that runs without blocking this conversation: web research or project inspection only. It cannot write files, run commands, use device tools, or ask questions. Returns a task ID to poll with read_task_output.",
                    listOf("title", "prompt"),
                    "title" to stringSchema("Short task label, maximum 80 characters."),
                    "prompt" to
                        stringSchema(
                            "Complete, self-contained instruction for the task, maximum 2,000 characters. Include everything it needs because it cannot ask questions.",
                        ),
                ),
            ).put(
                toolSchema(
                    "read_task_output",
                    "Read the status, current progress, summary, and per-step results of a background task started with start_task. Poll instead of assuming it finished.",
                    listOf("id"),
                    "id" to stringSchema("Task ID returned by start_task."),
                ),
            ).put(QuestionTool.schema())
            .put(QuizTool.schema())
            .apply { DeviceTools.schema().forEach { put(it) } }
            .apply { ScheduleTools.schema().forEach { put(it) } }
    }

    fun schema(): JSONArray {
        val arr = baseSchema()
        app.agentichydra.mcp.McpToolRouter.registry?.allTools()?.forEach { arr.put(it.toOpenAiSchema()) }
        return arr
    }
}
