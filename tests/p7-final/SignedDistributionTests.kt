package dev.wind.agent

import android.content.ContextWrapper
import dev.wind.agent.runtime.linux.*
import java.io.File
import java.util.zip.ZipFile

object SignedDistributionTests {
    @JvmStatic fun main(args:Array<String>) {
        val workspace=File(args[0]).canonicalFile;val scratch=File(args[1]).canonicalFile.apply { mkdirs() }
        var checks=0
        fun verify(ok:Boolean,message:String) { checks++;check(ok) { message } }
        val release=RuntimeToolCatalog.RELEASE
        val roots=File(workspace,"assets/publisher-trust.json").readBytes()
        val families=listOf("linux-jvm","linux-android","linux-lsp")
        for(family in families) ZipFile(File(workspace,"extension-packs/${RuntimeToolCatalog.filename(family)}")).use { zip ->
            verify(zip.entries().asSequence().map { it.name }.toList()==listOf("manifest.json","tools.tar.gz","publisher.json"),"Strict signed pack format")
            val manifest=zip.getInputStream(zip.getEntry("manifest.json")).use { it.readBytes() }
            verify(manifest.contentEquals(File(workspace,"assets/embedded/$family/manifest.json").readBytes()),"Pinned manifest unchanged")
            val envelope=zip.getInputStream(zip.getEntry("publisher.json")).use { it.readBytes() }
            verify(PublisherTrust.verify(envelope,manifest,family,release,roots,System.currentTimeMillis()).startsWith("harnessmate-rsa-"),"Actual Node signature verified by production JCA")
        }
        val ctx=object:ContextWrapper(null) { override fun getFilesDir():File=scratch }
        System.setProperty("harnessmate.fixture.assets",File(workspace,"assets").path)
        verify(!LinuxToolPackStore.hasPayload(ctx,"linux-jvm"),"No preexisting trusted cache")
        File(workspace,"extension-packs/${RuntimeToolCatalog.filename("linux-jvm")}").inputStream().use {
            verify(LinuxToolPackStore.importPack(ctx,it)=="linux-jvm","Actual 214MB signed package imports through production gate")
        }
        verify(LinuxToolPackStore.hasPayload(ctx,"linux-jvm"),"Signed receipt enables cached payload")
        LinuxToolPackStore.openPayload(ctx,"linux-jvm").use { verify(it.read()>=0,"Activation reader rechecks signature") }
        val receipts=File(scratch,"tool-pack-imports").listFiles()!!.filter { it.name.endsWith(".publisher.json") }
        verify(receipts.size==1,"One persisted publisher receipt")
        val receipt=receipts.single();val saved=receipt.readBytes()
        try {
            receipt.writeText("{}")
            verify(!LinuxToolPackStore.hasPayload(ctx,"linux-jvm"),"Invalid cached receipt is not ready")
            verify(runCatching { LinuxToolPackStore.openPayload(ctx,"linux-jvm") }.isFailure,"Invalid cached receipt cannot activate")
            receipt.delete()
            verify(!LinuxToolPackStore.hasPayload(ctx,"linux-jvm"),"Legacy unsigned cached payload is not trusted")
        } finally { receipt.writeBytes(saved) }
        println("SignedDistributionTests PASS ($checks checks); all real signatures + real payload import/activation receipt gate")
    }
}
