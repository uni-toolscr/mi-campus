package cr.micampus.app.data.knowledge

import android.content.Context

/** Lazily loads the bundled corpus so startup does not parse Markdown unless knowledge is used. */
class AssetKnowledgeRepository private constructor(private val readCorpus: () -> String) {
    private val corpus: KnowledgeCorpus by lazy { KnowledgeMarkdownParser.parse(readCorpus()) }
    private val knowledgeIndex: KnowledgeIndex by lazy { KnowledgeIndex(corpus.chunks) }

    fun chunks(): List<KnowledgeChunk> = corpus.chunks

    fun index(): KnowledgeIndex = knowledgeIndex

    companion object {
        const val UNA_CORPUS_ASSET = "knowledge/una_costa_rica_llm_knowledge_base.md"

        fun fromContext(context: Context): AssetKnowledgeRepository {
            val appContext = context.applicationContext
            return AssetKnowledgeRepository {
                appContext.assets.open(UNA_CORPUS_ASSET).bufferedReader().use { it.readText() }
            }
        }
    }
}
