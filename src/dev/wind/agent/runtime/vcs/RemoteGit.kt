package dev.wind.agent.runtime.vcs

import dev.wind.agent.CancellationToken
import dev.wind.agent.artifact.*
import dev.wind.agent.capability.TaskContext
import dev.wind.agent.secrets.SecretBroker
import dev.wind.agent.secrets.SecretDestination
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.transport.http.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** JGit owns the protocol. Credentials never cross the shell/runtime or model boundary. */
class RemoteGit(private val root: File, private val secrets: SecretBroker,
    private val httpFactory: (String,()->Unit)->HttpConnectionFactory = { repository,guard -> ScopedGitHttpFactory(repository,guard) }) {
    companion object { private val LOCK = Any() }
    fun run(args:JSONObject,task:TaskContext,token:CancellationToken,authorize:(String)->Boolean):JSONObject = synchronized(LOCK) {
        val repository=SecretDestination.repository(args.getString("repository_url"))
        val action=args.optString("action","refs")
        require(action in setOf("refs","clone","fetch","push")) { "REMOTE_GIT_ACTION_INVALID" }
        val id=args.optString("secret_id")
        val purpose=if(action=="push") "git_write" else "git_read"
        fun guard() { token.throwIfCancelled();check(authorize(repository)) { "REMOTE_GIT_NETWORK_REVOKED" };if(id.isNotEmpty()) secrets.validate(id,repository,purpose) }
        guard()
        check(root.canonicalFile==root.absoluteFile && (root.isDirectory || root.mkdirs()) && !Files.isSymbolicLink(root.toPath())) { "REMOTE_GIT_STORAGE_INVALID" }
        val key=hash((task.projectId+"\n"+repository).toByteArray())
        val directory=File(root,"repo-$key")
        val monitor=object:ProgressMonitor {
            override fun start(totalTasks:Int) { guard() }
            override fun beginTask(title:String,totalWork:Int) { guard() }
            override fun update(completed:Int) { guard() }
            override fun endTask() { guard() }
            override fun isCancelled():Boolean = token.isCancelled
            override fun showDuration(enabled:Boolean) {}
        }
        val provider=object:CredentialsProvider() {
            override fun isInteractive()=false
            override fun supports(vararg items:CredentialItem)=items.all { it is CredentialItem.Username || it is CredentialItem.Password }
            override fun get(uri:URIish,vararg items:CredentialItem):Boolean {
                guard();check(SecretDestination.permits(repository,URI(uri.toString()))) { "SECRET_SCOPE_MISMATCH" }
                if(id.isEmpty() || !supports(*items)) return false
                secrets.use(id,repository,purpose) { username,password -> items.forEach { item ->
                    when(item) { is CredentialItem.Username -> item.value=username;is CredentialItem.Password -> item.value=password.copyOf() }
                } }
                return true
            }
        }
        fun <C:org.eclipse.jgit.api.GitCommand<*>,T> configure(command:TransportCommand<C,T>):TransportCommand<C,T> {
            command.setCredentialsProvider(provider)
            command.setTimeout(60)
            command.setTransportConfigCallback { transport ->
                guard();check(transport is TransportHttp) { "REMOTE_GIT_HTTPS_REQUIRED" }
                transport.setHttpConnectionFactory(httpFactory(repository,::guard))
            };return command
        }
        try {
            if(action=="refs") {
                val command=Git.lsRemoteRepository().setRemote(repository).setHeads(true).setTags(true)
                configure(command)
                val refs=command.call().sortedBy { it.name }.map { JSONObject().put("name",it.name).put("object_id",it.objectId.name) }
                return@synchronized JSONObject().put("state","LISTED").put("refs",JSONArray(refs)).put("credential_exposed",false)
            }
            if(action=="clone") {
                check(!directory.exists()) { "REMOTE_GIT_ALREADY_CLONED: use fetch" }
                val command=Git.cloneRepository().setURI(repository).setDirectory(directory).setCloneSubmodules(false).setProgressMonitor(monitor)
                val branch=args.optString("branch")
                if(branch.isNotEmpty()) { require(Repository.isValidRefName("refs/heads/$branch")) { "REMOTE_GIT_BRANCH_INVALID" };command.setBranch("refs/heads/$branch") }
                configure(command)
                try { command.call().use { git -> secureConfig(git,repository) } }
                catch(e:Exception) { if(directory.exists()) cleanup(directory.toPath());throw e }
            }
            check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) && File(directory,".git").isDirectory) { "REMOTE_GIT_NOT_CLONED" }
            Git.open(directory).use { git ->
                secureConfig(git,repository)
                if(action=="fetch") {
                    val command=git.fetch().setRemote(repository).setProgressMonitor(monitor)
                        .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    configure(command);command.call();guard()
                }
                if(action=="push") {
                    require(id.isNotEmpty()) { "REMOTE_GIT_WRITE_CREDENTIAL_REQUIRED" }
                    val source=args.optString("source_artifact_uri")
                    if(source.isNotEmpty()) applySource(git,source,args,task,token,::guard)
                    val branch=args.optString("branch",git.repository.branch)
                    require(Repository.isValidRefName("refs/heads/$branch")) { "REMOTE_GIT_BRANCH_INVALID" }
                    val command=git.push().setRemote(repository).setRefSpecs(RefSpec("HEAD:refs/heads/$branch")).setProgressMonitor(monitor)
                    configure(command)
                    guard()
                    val updates=command.call().flatMap { it.remoteUpdates }.map { u -> JSONObject().put("ref",u.remoteName).put("status",u.status.name) }
                    val success=updates.isNotEmpty() && updates.all { it.getString("status") in setOf("OK","UP_TO_DATE") }
                    return@synchronized JSONObject().put("state",if(success) "PUSHED" else "REJECTED").put("updates",JSONArray(updates))
                        .put("commit",git.repository.resolve("HEAD").name).put("automatic_retry",false).put("credential_exposed",false)
                }
                val ref=args.optString("ref")
                if(ref.isNotEmpty()) {
                    require(ref.matches(Regex("[A-Za-z0-9_./-]{1,200}")) && !ref.contains("..")) { "REMOTE_GIT_REF_INVALID" }
                    git.checkout().setName(ref).call()
                }
                guard()
                val artifact=export(directory,repository,task,token,::guard)
                JSONObject().put("state",if(action=="clone") "CLONED" else "FETCHED").put("repository_id",key)
                    .put("artifact_uri",artifact.uri).put("sha256",artifact.sha256).put("bytes",artifact.byteSize)
                    .put("commit",git.repository.resolve("HEAD").name).put("includes_git_history",true)
                    .put("credential_exposed",false).put("automatic_retry",false)
                    .put("next_step","Use prepare_download_apply/apply_download to publish the ZIP, or save it as a ZIP then execution_import_archive. Fetch updates remote refs; choose ref explicitly to check out. For push, publish a source ZIP from execution and supply source_artifact_uri.")
            }
        } catch(e:Exception) {
            // JGit/server errors may contain URLs or echoed headers. Do not propagate their messages/causes.
            if(token.isCancelled) throw IllegalStateException("REMOTE_GIT_CANCELLED")
            val safe=e.message?.takeIf { it.matches(Regex("(?:SECRET|REMOTE_GIT)_[A-Z_]+")) }
            throw IllegalStateException(safe ?: if(action=="push") "REMOTE_GIT_PUSH_FAILED_OR_UNKNOWN: inspect remote refs before retry" else "REMOTE_GIT_OPERATION_FAILED")
        }
    }
    private fun secureConfig(git:Git,repository:String) {
        val c=git.repository.config
        c.setString("remote","origin","url",repository)
        c.setString("http",null,"followRedirects","false")
        c.setBoolean("http",null,"sslVerify",true)
        c.unset("http",null,"extraHeader");c.unset("credential",null,"helper");c.save()
    }
    private fun applySource(git:Git,uri:String,args:JSONObject,task:TaskContext,token:CancellationToken,guard:()->Unit) {
        val store=VerifiedArtifactStore(File(root.parentFile,"p7-artifacts"))
        val access=TaskArtifactAccess(File(root.parentFile,"p7-artifact-access"))
        val stageRoot=File(root,"source-stage").apply { mkdirs() }
        val stage=SafeArtifactZipStager(store,access,stageRoot.toPath()).stage(task.taskId,task.projectId,task.projectGeneration,uri) { token.isCancelled }
        try {
            val base=git.repository.workTree.toPath()
            val incoming=linkedSetOf<String>()
            Files.walk(stage).use { paths -> paths.filter { Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
                guard();val rel=stage.relativize(source).toString().replace('\\','/')
                require(rel.split('/').none { it==".git" || it==".harnessmate-package-cache" || it==".harnessmate-venv" }) { "REMOTE_GIT_SOURCE_METADATA_DENIED" }
                val target=base.resolve(rel).normalize();require(target.startsWith(base))
                var parent=target.parent
                while(parent!=base) { require(!Files.isSymbolicLink(parent)) { "REMOTE_GIT_SOURCE_PATH_UNSAFE" };parent=parent.parent }
                require(!Files.isSymbolicLink(target)) { "REMOTE_GIT_SOURCE_PATH_UNSAFE" }
                Files.createDirectories(target.parent);Files.copy(source,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);incoming.add(rel)
            } }
            if(args.optBoolean("replace_tree",false)) {
                val index=git.repository.readDirCache()
                for(i in 0 until index.entryCount) { guard();val p=index.getEntry(i).pathString;if(p !in incoming) git.rm().addFilepattern(p).call() }
            }
            guard();git.add().addFilepattern(".").call()
            if(!git.status().call().isClean) {
                val message=args.getString("commit_message");require(message.length in 1..4096) { "REMOTE_GIT_COMMIT_MESSAGE_INVALID" }
                git.commit().setMessage(message).setAuthor("Harness Mate Agent","agent@harnessmate.invalid")
                    .setCommitter("Harness Mate Agent","agent@harnessmate.invalid").call()
            }
        } finally { cleanup(stage) }
    }
    private fun export(directory:File,repository:String,task:TaskContext,token:CancellationToken,guard:()->Unit):VerifiedArtifact {
        val stage=Files.createTempFile(root.toPath(),"git-export-",".zip").toFile()
        try {
            ZipOutputStream(stage.outputStream()).use { zip -> Files.walk(directory.toPath()).use { paths ->
                paths.forEach { p ->
                    guard();require(!Files.isSymbolicLink(p)) { "REMOTE_GIT_SYMLINK_EXPORT_UNSUPPORTED" }
                    if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) {
                        val rel=directory.toPath().relativize(p).toString().replace('\\','/')
                        if(!(rel.startsWith(".git/") && rel.endsWith(".lock"))) { zip.putNextEntry(ZipEntry(rel));Files.newInputStream(p).use { input ->
                            val buffer=ByteArray(65536);while(true) { guard();val n=input.read(buffer);if(n<0)break;zip.write(buffer,0,n) }
                        };zip.closeEntry() }
                    }
                }
            } }
            guard()
            val store=VerifiedArtifactStore(File(root.parentFile,"p7-artifacts"))
            val artifact=stage.inputStream().use { ExecutionArtifactPublisher(root,store).publish(it,stage.length(),repository) { token.throwIfCancelled();guard() } }
            TaskArtifactAccess(File(root.parentFile,"p7-artifact-access")).record(task.taskId,task.projectId,task.projectGeneration,artifact)
            return artifact
        } finally { stage.delete() }
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun cleanup(directory:java.nio.file.Path) {
        val base=root.canonicalFile.toPath()
        val target=directory.toAbsolutePath().normalize()
        require(target.startsWith(base) && target!=base) { "REMOTE_GIT_CLEANUP_SCOPE_INVALID" }
        Files.walk(target).use { stream -> stream.sorted(java.util.Comparator.reverseOrder()).forEach {
            // Staged ZIP candidates are read-only before bytes are written (Android DCL).
            // Restore writability only for regular files in this verified private cleanup tree.
            if(Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS)) it.toFile().setWritable(true)
            Files.deleteIfExists(it)
        } }
    }
}

/** Per-transport factory, never global. Only the exact Git repository protocol endpoints may receive auth. */
internal class ScopedGitHttpFactory(private val repository:String,private val guard:()->Unit,
    private val delegate:HttpConnectionFactory=JDKHttpConnectionFactory()):HttpConnectionFactory {
    override fun create(url:URL):HttpConnection=create(url,Proxy.NO_PROXY)
    override fun create(url:URL,proxy:Proxy):HttpConnection {
        guard();check(SecretDestination.permits(repository,url.toURI())) { "SECRET_SCOPE_MISMATCH" }
        val connection=delegate.create(url,proxy)
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(15000)
        return object:HttpConnection by connection {
            override fun setInstanceFollowRedirects(follow:Boolean) { connection.setInstanceFollowRedirects(false) }
            override fun configure(k:Array<javax.net.ssl.KeyManager>?,t:Array<javax.net.ssl.TrustManager>?,r:java.security.SecureRandom?) {
                check(k==null && t==null) { "REMOTE_GIT_TLS_OVERRIDE_DENIED" }
            }
            override fun setHostnameVerifier(v:javax.net.ssl.HostnameVerifier) { throw IllegalStateException("REMOTE_GIT_TLS_OVERRIDE_DENIED") }
            override fun connect() { guard();connection.connect() }
            override fun getResponseCode():Int { guard();return connection.responseCode }
            override fun getOutputStream():OutputStream { guard();return object:FilterOutputStream(connection.outputStream) {
                override fun write(b:ByteArray,off:Int,len:Int) { guard();out.write(b,off,len) }
                override fun write(b:Int) { guard();out.write(b) }
            } }
            override fun getInputStream():InputStream { guard();return object:FilterInputStream(connection.inputStream) {
                override fun read(b:ByteArray,off:Int,len:Int):Int { guard();return super.read(b,off,len) }
                override fun read():Int { guard();return super.read() }
            } }
        }
    }
}
