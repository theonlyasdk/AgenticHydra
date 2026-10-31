package app.agentichydra

import org.json.JSONArray

/** Read-only tools for chat mode: public web research, the clock, and user questions. */
internal object ChatTools {
    /** Serialized size of the tool schemas, counted against the context window. */
    val schemaChars: Long by lazy { schema().toString().length.toLong() }

    fun schema(): JSONArray =
        JSONArray()
            .put(ResearchToolSchemas.webSearch())
            .put(ResearchToolSchemas.webSearchBatch())
            .put(ResearchToolSchemas.fetchUrl())
            .put(ResearchToolSchemas.findInPage())
            .put(ResearchToolSchemas.getTime())
            .put(QuestionTool.schema())
            .put(QuizTool.schema())
}
