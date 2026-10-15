package dev.wind.agent

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integration tests for ChatStore and KeyedWorkQueue persistence:
 * - Host Context/ContextWrapper and Looper fixture only, not Android physical device acceptance.
 * - Reflectively accesses ChatStore.writes static field.
 * - Holds chat A lane with a blocking latch.
 * - Verifies main thread saveNow(A) returns in <500ms and load(A) returns live pending object.
 * - Verifies independent worker thread saveNow(B) completes and writes real file while A is blocked.
 * - Releases lane A and verifies flushPendingWrites flushes latest messages to JSON disk file.
 * - Verifies delete non-coalescing barrier deletes the file and load(A) returns null without resurrection.
 */
object ChatStoreIntegrationTests {

    private fun getWritesQueue(): KeyedWorkQueue {
        val field = ChatStore::class.java.getDeclaredField("writes")
        field.isAccessible = true
        return field.get(null) as KeyedWorkQueue
    }

    fun testChatStoreKeyedWorkQueueIntegration() {
        val testFilesDir = File(System.getProperty("java.io.tmpdir"), "test-chatstore-" + System.nanoTime()).apply { mkdirs() }
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = testFilesDir
            override fun getApplicationContext(): android.content.Context = this
        }
        val store = ChatStore(context)
        val queue = getWritesQueue()

        // 1. Manual Chat objects avoiding WebModelRouting
        val chatA = Chat("chat-live-A", "Chat A", System.currentTimeMillis())
        chatA.messages.add(Message("user", "Initial message in A"))

        val chatB = Chat("chat-live-B", "Chat B", System.currentTimeMillis())
        chatB.messages.add(Message("user", "Message in B"))

        // 2. Put a blocking latch job into chatA lane
        val holdALatch = CountDownLatch(1)
        val aBlockedEntered = CountDownLatch(1)
        queue.submit("chat-live-A") {
            aBlockedEntered.countDown()
            holdALatch.await()
        }
        check(aBlockedEntered.await(5, TimeUnit.SECONDS)) { "Blocked job on lane A failed to enter" }

        // 3. Simulate main thread calling store.saveNow(chatA)
        android.os.Looper.setAsMainThread()
        val startSave = System.currentTimeMillis()
        store.saveNow(chatA)
        val elapsedMs = System.currentTimeMillis() - startSave
        check(elapsedMs < 500) { "Main thread saveNow(A) must return in < 500ms, took ${elapsedMs}ms" }

        // store.load(A) must return pending live object
        val liveA = store.load("chat-live-A")
        check(liveA != null && liveA === chatA) { "store.load(A) must return pending live object while disk is busy" }

        val fileA = File(testFilesDir, "chats/chat-live-A.json")
        check(!fileA.exists()) { "file A must NOT exist on disk yet while lane A is blocked" }

        // 4. Independent chat B on worker thread
        val workerBDone = AtomicBoolean(false)
        val workerB = Thread({
            store.saveNow(chatB)
            workerBDone.set(true)
        }, "worker-save-chat-B")
        workerB.start()
        workerB.join(5000)
        check(!workerB.isAlive && workerBDone.get()) { "Independent chat B saveNow must finish on worker thread" }

        val fileB = File(testFilesDir, "chats/chat-live-B.json")
        check(fileB.exists()) { "Independent chat B must write real file to disk while A is blocked" }
        check(!fileA.exists()) { "file A must still be blocked and not on disk" }

        // 5. Release lane A and flushPendingWrites
        chatA.messages.add(Message("assistant", "Latest message in A"))
        store.save(chatA)

        holdALatch.countDown()
        val flushed = ChatStore.flushPendingWrites(5000L)
        check(flushed) { "flushPendingWrites must succeed" }

        check(fileA.exists()) { "file A must land on disk after release and flush" }
        val contentA = fileA.readText(Charsets.UTF_8)
        check(contentA.contains("Latest message in A")) { "file A must contain latest messages" }

        // 6. Delete barrier
        store.delete("chat-live-A")
        ChatStore.flushPendingWrites(5000L)

        check(!fileA.exists()) { "file A must be deleted from disk" }
        check(store.load("chat-live-A") == null) { "store.load(A) must return null after delete barrier" }

        android.os.Looper.clearMainThread()
        testFilesDir.deleteRecursively()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running ChatStoreIntegrationTests ===")
        testChatStoreKeyedWorkQueueIntegration()
        println("PASS: testChatStoreKeyedWorkQueueIntegration")
        println("ALL_CHATSTORE_INTEGRATION_TESTS_PASS (1 suite / 6 checks)")
    }
}
