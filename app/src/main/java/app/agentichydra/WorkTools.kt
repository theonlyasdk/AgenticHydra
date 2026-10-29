package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject

object WorkTools {
    val names=listOf("list_files","list_files_recursive","read_file","write_file","run_command","search_files","find_files","file_info","read_image",
        "create_directory","append_file","replace_in_file","apply_diff","delete_file","move_file","copy_file","web_search","fetch_url","git_status","git_diff","web_search_batch","find_in_page","start_task","read_task_output","ask_user")+DeviceTools.names+ScheduleTools.names
    fun schema():JSONArray {
        fun str(description:String)=JSONObject().put("type","string").put("description",description)
        fun bool(description:String)=JSONObject().put("type","boolean").put("description",description)
        fun tool(name:String,description:String,required:List<String>,vararg properties:Pair<String,JSONObject>)=JSONObject().put("type","function")
            .put("function",JSONObject().put("name",name).put("description",description).put("parameters",JSONObject()
                .put("type","object").put("properties",JSONObject().apply{properties.forEach{put(it.first,it.second)}}).put("required",JSONArray(required))))
        val path="path" to str("Relative project path; use an empty string for the project root when listing or searching.")
        return JSONArray()
            .put(tool("list_files","List immediate children of a project directory.",listOf("path"),path))
            .put(tool("list_files_recursive","List project files recursively, up to 300 files. Prefer this over repeated list_files calls.",listOf("path"),path))
            .put(tool("read_file","Read a UTF-8 project file, maximum 256 KB.",listOf("path"),path))
            .put(tool("write_file","Create or replace a UTF-8 file. Read existing files first. Maximum 256 KB.",listOf("path","content"),path,"content" to str("Complete new file content.")))
            .put(tool("run_command","Execute a bash command in installed Termux at the project working directory. Requires Termux setup. Timeout 180 seconds; returns actual exit code and output.",listOf("command"),"command" to str("Shell command.")))
            .put(tool("search_files","Search text in project files recursively. Literal search, returns paths and line numbers; scans at most 300 files and returns at most 100 matches.",listOf("path","query"),path,"query" to str("Literal text to find."),"case_sensitive" to bool("Case-sensitive matching; default false.")))
            .put(tool("find_files","Find project files recursively by filename glob, e.g. *.kt. At most 300 files.",listOf("path","pattern"),path,"pattern" to str("Filename glob with * and ? wildcards.")))
            .put(tool("read_image","Load a project image (PNG, JPEG, WebP, or GIF up to 4 MB) and attach it to the next request so you can see it. Use before describing or editing image files.",listOf("path"),path))
            .put(tool("file_info","Read a project file or directory's type, size, MIME type, and modification time.",listOf("path"),path))
            .put(tool("create_directory","Create a project directory and any missing parent directories.",listOf("path"),path))
            .put(tool("append_file","Append UTF-8 text to a project file, creating it if missing. Maximum total 256 KB.",listOf("path","content"),path,"content" to str("Text to append.")))
            .put(tool("replace_in_file","Replace exact text in a UTF-8 file. Fails on ambiguous matches unless replace_all is true.",listOf("path","old_text","new_text"),path,"old_text" to str("Exact existing text; must not be empty."),"new_text" to str("Replacement text."),"replace_all" to bool("Replace all occurrences; default false.")))
            .put(tool("apply_diff","Apply one or more search/replace edits to a UTF-8 file in a single call. Use for multi-spot edits instead of several replace_in_file calls. Each block: ------- SEARCH, old lines, =======, new lines, +++++++ REPLACE.",listOf("path","diff"),path,"diff" to str("SEARCH/REPLACE blocks; each SEARCH must match exactly once and be short with distinct surrounding context.")))
            .put(tool("delete_file","Delete one project file, never a directory. Only use when explicitly requested by the user.",listOf("path"),path))
            .put(tool("move_file","Move or rename a UTF-8 project file. Destination must not exist. Maximum 256 KB.",listOf("source","destination"),"source" to str("Existing relative file path."),"destination" to str("New relative file path.")))
            .put(tool("copy_file","Copy a UTF-8 project file. Destination must not exist. Maximum 256 KB.",listOf("source","destination"),"source" to str("Existing relative file path."),"destination" to str("New relative file path.")))
            .put(tool("web_search","Search public sources with deduplicated titles, URLs, snippets, and source IDs. Supports domain filters, result limits, best-effort freshness, and a five-minute cache. Results may be blocked or rate-limited.",listOf("query"),"query" to str("Search query, maximum 300 characters."),*searchFilterSchema()))
            .put(tool("fetch_url","Read a public HTTPS page with source links and text pagination. Use next_offset to continue reading. No credentials, private addresses, scripts, or binary downloads; maximum download 1 MB. Parsed pages are cached five minutes.",listOf("url"),"url" to str("Public HTTPS URL."),"max_chars" to integerSchema("Text budget, default 10,000 characters.",1000,12000),"offset" to integerSchema("Text offset, default zero.",0,120000)))
            .put(tool("git_status","Run git status --short --branch in Termux for the selected project. Always asks in Ask for changes mode.",emptyList()))
            .put(tool("git_diff","Read the project's Git diff in Termux without external diff programs. Always asks in Ask for changes mode.",emptyList(),"path" to str("Optional relative path to restrict the diff."),"staged" to bool("Show staged changes; default false.")))
            .put(tool("web_search_batch","Run one to three independent searches, with at most two requests in parallel. Returns separate structured results and errors per query. Prefer over repeated sequential searches.",listOf("queries"),
                "queries" to JSONObject().put("type","array").put("minItems",1).put("maxItems",3).put("items",str("Distinct search query, maximum 300 characters.")),*searchFilterSchema()))
            .put(tool("find_in_page","Find literal text in a public HTTPS page, returning line numbers and short contextual excerpts. Reuses cached page text; efficient for locating relevant passages.",listOf("url","query"),
                "url" to str("Public HTTPS URL."),"query" to str("Text to find, 1–200 characters."),"max_matches" to integerSchema("Maximum matches, default six.",1,10)))
            .put(tool("start_task","Start an independent read-only background task that runs without blocking this conversation: web research or project inspection only. It cannot write files, run commands, use device tools, or ask questions. Returns a task ID to poll with read_task_output.",listOf("title","prompt"),
                "title" to str("Short task label, maximum 80 characters."),"prompt" to str("Complete, self-contained instruction for the task, maximum 2,000 characters. Include everything it needs because it cannot ask questions.")))
            .put(tool("read_task_output","Read the status, current progress, summary, and per-step results of a background task started with start_task. Poll instead of assuming it finished.",listOf("id"),
                "id" to str("Task ID returned by start_task.")))
            .put(QuestionTool.schema())
            .apply{DeviceTools.schema().forEach{put(it)}}
            .apply{ScheduleTools.schema().forEach{put(it)}}

    }
}
