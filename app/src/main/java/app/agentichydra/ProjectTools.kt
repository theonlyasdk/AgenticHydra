package app.agentichydra

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.ArrayDeque

data class WorkToolResult(val output:String,val changes:List<FileChange> = emptyList(),val failed:Boolean=false)

class ProjectTools(private val context:Context,private val root:DocumentFile) {
    fun resolve(path:String,create:Boolean=false):DocumentFile {
        val parts=ToolPolicy.parts(path)
        var current=root
        parts.forEachIndexed { index,name ->
            require(current.isDirectory){"A parent path is not a directory."}
            current=current.findFile(name)?:if(create) {
                (if(index==parts.lastIndex)current.createFile("text/plain",name)else current.createDirectory(name))?:error("Could not create $name")
            }else error("File not found: $path")
        }
        return current
    }
    fun read(path:String):String {
        val file=resolve(path)
        require(file.isFile){"Choose a file, not a directory."}
        require(file.length()<=262144){"File exceeds 256 KB."}
        val text=context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer=CharArray(262145);var length=0
            while(length<buffer.size){val count=reader.read(buffer,length,buffer.size-length);if(count<0)break;length+=count}
            String(buffer,0,length)
        }?:error("Could not read file.")
        require(text.toByteArray(Charsets.UTF_8).size<=262144){"File exceeds 256 KB."}
        require(!text.contains('\u0000')){"This tool supports UTF-8 text and code files."}
        return text
    }
    private fun write(path:String,text:String):FileChange {
        require(ToolPolicy.parts(path).isNotEmpty()){"Choose a file path, not the project root."}
        require(text.toByteArray(Charsets.UTF_8).size<=262144&&!text.contains('\u0000')){"Use a UTF-8 file smaller than 256 KB."}
        val existing=runCatching{resolve(path)}.getOrNull()
        val before=if(existing==null)"" else read(path)
        val file=resolve(path,true)
        context.contentResolver.openOutputStream(file.uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use{it.write(text)}?:error("Could not write file.")
        return FileChange(path,before,text)
    }
    private fun walk(path:String):List<Pair<String,DocumentFile>> {
        val start=resolve(path);require(start.isDirectory){"Choose a directory."}
        val queue=ArrayDeque<Triple<String,DocumentFile,Int>>()
        queue.add(Triple(path.trim('/'),start,0))
        val files=mutableListOf<Pair<String,DocumentFile>>()
        var visited=0
        while(queue.isNotEmpty()&&visited++<600&&files.size<300) {
            if(Thread.currentThread().isInterrupted)throw InterruptedIOException()
            val (prefix,dir,depth)=queue.removeFirst()
            dir.listFiles().sortedBy{it.name}.forEach { child ->
                val name=child.name?:return@forEach
                val full=if(prefix.isEmpty())name else "$prefix/$name"
                if(child.isDirectory&&depth<12&&name !in setOf(".git","node_modules",".gradle","build"))queue.add(Triple(full,child,depth+1))
                else if(child.isFile&&files.size<300)files.add(full to child)
            }
        }
        return files
    }
    fun execute(name:String,args:JSONObject):WorkToolResult {
        val path=args.optString("path","")
        return when(name) {
            "list_files"->{val file=resolve(path);require(file.isDirectory){"Choose a directory."};WorkToolResult(file.listFiles().take(150).joinToString("\n"){(if(it.isDirectory)"dir  "else "file ")+(it.name?:"?")}.ifBlank{"Directory is empty."})}
            "read_file"->WorkToolResult(read(args.getString("path")))
            "write_file"->{val change=write(args.getString("path"),args.getString("content"));WorkToolResult("Wrote ${change.path} (${change.after.length} characters).",listOf(change))}
            "append_file"->{val before=if(runCatching{resolve(path)}.isSuccess)read(path)else "";val change=write(path,before+args.getString("content"));WorkToolResult("Appended to $path.",listOf(change))}
            "replace_in_file"->{
                val before=read(path);val old=args.getString("old_text");val replacement=args.getString("new_text")
                require(old.isNotEmpty()){"old_text must not be empty."}
                val matches=Regex(Regex.escape(old)).findAll(before).count()
                require(matches>0){"Exact text was not found; read the file again."}
                val all=args.optBoolean("replace_all",false)
                require(all||matches==1){"Text matches more than once. Supply more context or set replace_all."}
                val change=write(path,if(all)before.replace(old,replacement)else before.replaceFirst(old,replacement))
                WorkToolResult("Replaced ${if(all)matches else 1} occurrence(s) in $path.",listOf(change))
            }
            "delete_file"->{val before=read(path);require(resolve(path).delete()){"Could not delete the file."};WorkToolResult("Deleted $path.",listOf(FileChange(path,before,"")))}
            "copy_file","move_file"->{
                val source=args.getString("source");val destination=args.getString("destination")
                require(ToolPolicy.parts(destination).isNotEmpty()){"Choose a destination file."}
                require(runCatching{resolve(destination)}.isFailure){"Destination already exists."}
                val content=read(source);val change=write(destination,content)
                if(name=="move_file") {
                    if(!resolve(source).delete())WorkToolResult("Tool error: copied to $destination, but could not remove $source. Both files remain.",listOf(change))
                    else WorkToolResult("Moved $source to $destination.",listOf(change,FileChange(source,content,"")))
                }else WorkToolResult("Copied $source to $destination.",listOf(change))
            }
            "create_directory"->{
                val parts=ToolPolicy.parts(path);require(parts.isNotEmpty()){"Choose a directory name."}
                var current=root
                parts.forEach{part->current=current.findFile(part)?:current.createDirectory(part)?:error("Could not create $part");require(current.isDirectory){"A path already exists as a file."}}
                WorkToolResult("Directory ready: $path")
            }
            "file_info"->{val file=resolve(path);WorkToolResult(JSONObject().put("path",path).put("directory",file.isDirectory).put("bytes",file.length()).put("mime",file.type).put("modifiedAt",file.lastModified()).toString(2))}
            "find_files"->{
                val pattern=args.getString("pattern");require(pattern.isNotBlank()&&pattern.length<=200){"Use a filename glob of 1–200 characters."}
                val regex=Regex(pattern.map{when(it){'*'->".*";'?'->".";else->Regex.escape(it.toString())}}.joinToString(""),RegexOption.IGNORE_CASE)
                val results=walk(path).map{it.first}.filter{regex.matches(it.substringAfterLast('/'))}
                WorkToolResult(results.joinToString("\n").ifBlank{"No matching files in the scanned files."}+"\n(Scan limit: 300 files; skips build/cache directories.)")
            }
            "search_files"->{
                val query=args.getString("query");require(query.isNotEmpty()&&query.length<=500){"Use a query of 1–500 characters."}
                val results=mutableListOf<String>()
                for((file,_)in walk(path)) {
                    if(results.size>=100)break
                    val text=runCatching{read(file)}.getOrNull()?:continue
                    text.lineSequence().forEachIndexed { index,line ->if(results.size<100&&line.contains(query,!args.optBoolean("case_sensitive",false)))results.add("$file:${index+1}: ${line.take(300)}")}
                }
                WorkToolResult(results.joinToString("\n").ifBlank{"No matches in scanned UTF-8 files."}+"\n(Scan limit: 300 files / 100 matches; skips build/cache directories.)")
            }
            else->error("Unknown project tool: $name")
        }
    }
}
