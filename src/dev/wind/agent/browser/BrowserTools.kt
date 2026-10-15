package dev.wind.agent.browser

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.Tools
import dev.wind.agent.Message
import dev.wind.agent.Prefs
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.webprovider.WebUploadStager
import java.io.File
import org.json.JSONObject

/** Trusted task/chat identities are supplied by AgentEngine, never by page or model arguments. */
class BrowserTools(private val context:Context,private val owner:String,private val chat:String,private val projectId:String,private val projectGeneration:Long,private val authorize:(String)->Boolean) {
    fun run(name:String,arguments:JSONObject,token:CancellationToken,observer:Tools.Observer?):String {
        val args=JSONObject(arguments.toString())
        BrowserProtocol.validate(name,args)
        check(Prefs(context).browserEnabled()){ "BROWSER_DISABLED_IN_SETTINGS" }
        require(owner.isNotBlank() && chat.isNotBlank()){ "BROWSER_TASK_BINDING_REQUIRED" }
        if(Prefs(context).mode()==Prefs.MODE_PLAN && BrowserProtocol.isMutating(name,args))return JSONObject().put("status","NOT_SUBMITTED").put("submitted",false).put("error","PLAN_MODE_BROWSER_MUTATION_DENIED").toString()
        val manager=BrowserSessions.get(context)
        if(name=="browser_session" && args.optString("mode","list")=="open")return manager.open(chat,owner,args,token,authorize).toString()
        if(name!="browser_upload")return manager.run(chat,owner,name,args,token,observer).toString()
        val uri=args.getString("artifact_uri")
        val root=context.noBackupFilesDir.canonicalFile
        TaskArtifactAccess(File(root,"p7-artifact-access")).requireAccess(owner,projectId,projectGeneration,uri)
        val filename=args.optString("filename","upload.bin")
        require(filename.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) && filename !in setOf(".","..")){ "BROWSER_UPLOAD_NAME_INVALID" }
        val mime=args.optString("mime","application/octet-stream")
        require(mime.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))){ "BROWSER_UPLOAD_MIME_INVALID" }
        val temp=File.createTempFile("browser-upload-",".tmp",context.cacheDir)
        val stager=WebUploadStager(context)
        var session:BrowserSessions.Session?=null
        try {
            VerifiedArtifactStore(File(root,"p7-artifacts")).openVerified(uri).use{input->temp.outputStream().use{output->val buffer=ByteArray(32768);var size=0L;while(true){token.throwIfCancelled();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=24_000_000){ "BROWSER_UPLOAD_TOO_LARGE" };output.write(buffer,0,n)}}}
            val staged=stager.stage(listOf(Message.Attachment(filename,temp.path,mime,temp.length(),"file")),token)
            session=manager.stageUpload(chat,owner,args,staged.map{it.uri}.toTypedArray(),token)
            val result=manager.run(chat,owner,name,args,token,observer)
            val selected=manager.finishUpload(session,token)
            if(selected)manager.retainUpload(session,stager,token) else {
                stager.close()
                if(result.optBoolean("submitted"))session.receipts.finish(args.getString("request_id"),"UNKNOWN",null).also{r->result.put("status",r.getString("status")).put("submitted",JSONObject.NULL)}
            }
            result.put("file_selection",if(selected)"CHOOSER_CONSUMED" else "NOT_SELECTED").put("upload_completion","NOT_VERIFIED")
            return result.toString()
        }catch(e:Throwable){session?.let{manager.abortUpload(it)};stager.close();throw e}finally{temp.delete()}
    }
}
