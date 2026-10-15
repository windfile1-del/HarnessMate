package dev.wind.agent.memory

/** Deletion barriers contain only coordinates/hashes. Original transcript stays intact.
 * A rolling prefilter finds shifted/repeated evidence in edited messages in O(n), then
 * SHA256 verifies each match; no forgotten plaintext is retained for matching.
 */
object MemoryRedactions {
    const val MARKER="[已撤销的记忆来源片段]"
    fun rolling(text: String): Long { var h=0L;for(c in text)h=h*257+c.code;return h }
    fun ranges(text: String, sources: List<MemorySource>): List<IntRange> {
        val matches=mutableListOf<IntRange>()
        for((size,group) in sources.filter { it.hasSpan }.groupBy { it.end-it.start }) {
            if(size>text.length)continue
            val hashes=group.groupBy { it.rolling }.mapValues { (_,values)->values.map { it.spanDigest }.toSet() }
            var power=1L;repeat(size-1) { power*=257 }
            var h=rolling(text.substring(0,size))
            for(index in 0..text.length-size) {
                val candidates=hashes[h]
                if(candidates!=null && MemoryRules.hash(text.substring(index,index+size)) in candidates)matches.add(index until index+size)
                if(index<text.length-size)h=(h-text[index].code*power)*257+text[index+size].code
            }
        }
        val merged=mutableListOf<IntRange>()
        for(r in matches.sortedBy { it.first }) {
            val last=merged.lastOrNull()
            if(last!=null && r.first<=last.last+1)merged[merged.lastIndex]=last.first..maxOf(last.last,r.last) else merged.add(r)
        }
        return merged
    }
    fun apply(text: String, sources: List<MemorySource>): String {
        val ranges=ranges(text,sources);if(ranges.isEmpty())return text
        return buildString {var next=0;for(r in ranges) {append(text,next,r.first);append(MARKER);next=r.last+1};append(text,next,text.length)}
    }
    fun slice(text: String,sources: List<MemorySource>,start: Int,end: Int): String {
        require(start in 0..end && end<=text.length)
        val clipped=ranges(text,sources).filter { it.first<end && it.last>=start }.map { maxOf(start,it.first)..minOf(end-1,it.last) }
        return buildString {var next=start;for(r in clipped) {append(text,next,r.first);append(MARKER);next=r.last+1};append(text,next,end)}
    }
}
