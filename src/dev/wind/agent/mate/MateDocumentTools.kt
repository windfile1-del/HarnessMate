package dev.wind.agent.mate

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.wind.agent.CancellationToken
import dev.wind.agent.memory.MemoryRules
import org.json.JSONObject

/** Small generated text artifacts use app-owned scoped Downloads, without requiring a code project. */
object MateDocumentTools {
    fun create(c: Context,args: JSONObject,token: CancellationToken): String {
        check(Build.VERSION.SDK_INT>=29) {"MATE_DOCUMENT_SCOPED_DOWNLOADS_REQUIRES_ANDROID_10"}
        val name=args.getString("filename");val format=args.optString("format",name.substringAfterLast('.'))
        require(name.matches(Regex("[a-zA-Z0-9_-][a-zA-Z0-9_. -]{0,79}")) && name !in setOf(".","..") && format in setOf("txt","md","csv","html") && name.endsWith(".$format")) {"MATE_DOCUMENT_NAME_INVALID"}
        val body=args.getString("content");require(body.length in 1..65536 && !MemoryRules.secret(body)) {"MATE_DOCUMENT_CONTENT_INVALID_OR_SECRET"}
        val bytes=body.toByteArray(Charsets.UTF_8);token.throwIfCancelled()
        val mime=when(format){"csv"->"text/csv";"html"->"text/html";"md"->"text/markdown";else->"text/plain"}
        val values=ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/HarnessMate");put(MediaStore.MediaColumns.IS_PENDING,1)}
        val uri=c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("MATE_DOCUMENT_CREATE_FAILED")
        var committed=false
        try {
            c.contentResolver.openOutputStream(uri,"w").use {output->check(output!=null);output.write(bytes)}
            token.throwIfCancelled()
            val readback=c.contentResolver.openInputStream(uri)?.use {it.readBytes()} ?: error("MATE_DOCUMENT_READBACK_FAILED")
            check(readback.contentEquals(bytes)) {"MATE_DOCUMENT_BYTES_DIFFER"}
            check(c.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1)
            committed=true
            val actual=c.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else name} ?: name
            return JSONObject().put("status","COMPLETED").put("filename",actual).put("content_uri",uri.toString()).put("mime",mime)
                .put("bytes",bytes.size).put("sha256",MemoryRules.hash(body)).put("verification","ACTUAL_CONTENT_READBACK").toString()
        } finally {if(!committed)c.contentResolver.delete(uri,null,null)}
    }
}
