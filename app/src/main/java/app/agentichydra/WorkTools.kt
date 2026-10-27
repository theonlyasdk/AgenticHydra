package app.agentichydra

import org.json.JSONArray
import org.json.JSONObject

object WorkTools {
    val names=listOf("list_files","read_file","write_file","run_command","search_files","find_files","file_info",
        "create_directory","append_file","replace_in_file","delete_file","move_file","copy_file","web_search","fetch_url","git_status","git_diff")
    fun schema():JSONArray {
        fun str(description:String)=JSONObject().put("type","string").put("description",description)
        fun bool(description:String)=JSONObject().put("type","boolean").put("description",description)
        fun tool(name:String,description:String,required:List<String>,vararg properties:Pair<String,JSONObject>)=JSONObject().put("type","function")
            .put("function",JSONObject().put("name",name).put("description",description).put("parameters",JSONObject()
                .put("type","object").put("properties",JSONObject().apply{properties.forEach{put(it.first,it.second)}}).put("required",JSONArray(required))))
        val path="path" to str("Relative project path; use an empty string for the project root when listing or searching.")
        return JSONArray()
            .put(tool("list_files","List immediate children of a project directory.",listOf("path"),path))
            .put(tool("read_file","Read a UTF-8 project file, maximum 256 KB.",listOf("path"),path))
            .put(tool("write_file","Create or replace a UTF-8 file. Read existing files first. Maximum 256 KB.",listOf("path","content"),path,"content" to str("Complete new file content.")))
            .put(tool("run_command","Execute a bash command in installed Termux at the project working directory. Requires Termux setup. Timeout 180 seconds; returns actual exit code and output.",listOf("command"),"command" to str("Shell command.")))
            .put(tool("search_files","Search text in project files recursively. Literal search, returns paths and line numbers; scans at most 300 files and returns at most 100 matches.",listOf("path","query"),path,"query" to str("Literal text to find."),"case_sensitive" to bool("Case-sensitive matching; default false.")))
            .put(tool("find_files","Find project files recursively by filename glob, e.g. *.kt. At most 300 files.",listOf("path","pattern"),path,"pattern" to str("Filename glob with * and ? wildcards.")))
            .put(tool("file_info","Read a project file or directory's type, size, MIME type, and modification time.",listOf("path"),path))
            .put(tool("create_directory","Create a project directory and any missing parent directories.",listOf("path"),path))
            .put(tool("append_file","Append UTF-8 text to a project file, creating it if missing. Maximum total 256 KB.",listOf("path","content"),path,"content" to str("Text to append.")))
            .put(tool("replace_in_file","Replace exact text in a UTF-8 file. Fails on ambiguous matches unless replace_all is true.",listOf("path","old_text","new_text"),path,"old_text" to str("Exact existing text; must not be empty."),"new_text" to str("Replacement text."),"replace_all" to bool("Replace all occurrences; default false.")))
            .put(tool("delete_file","Delete one project file, never a directory. Only use when explicitly requested by the user.",listOf("path"),path))
            .put(tool("move_file","Move or rename a UTF-8 project file. Destination must not exist. Maximum 256 KB.",listOf("source","destination"),"source" to str("Existing relative file path."),"destination" to str("New relative file path.")))
            .put(tool("copy_file","Copy a UTF-8 project file. Destination must not exist. Maximum 256 KB.",listOf("source","destination"),"source" to str("Existing relative file path."),"destination" to str("New relative file path.")))
            .put(tool("web_search","Search the public web for a query; returns result titles, URLs, and snippets. Search sites may rate-limit or block access; report failures honestly.",listOf("query"),"query" to str("Search query, maximum 300 characters.")))
            .put(tool("fetch_url","Read a public HTTPS page as text with source links. No credentials, private addresses, scripts, or downloads; maximum response 1 MB.",listOf("url"),"url" to str("Public HTTPS URL.")))
            .put(tool("git_status","Run git status --short --branch in Termux for the selected project. Always asks in Ask for changes mode.",emptyList()))
            .put(tool("git_diff","Read the project's Git diff in Termux without external diff programs. Always asks in Ask for changes mode.",emptyList(),"path" to str("Optional relative path to restrict the diff."),"staged" to bool("Show staged changes; default false.")))
    }
}
