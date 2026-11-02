package app.ft.carlife

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CarLifeWriterTest {
    @Test fun interruptingAQueuedSenderFreesCapacityWithoutLeavingItsPacketBehind() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = java.util.concurrent.LinkedBlockingQueue<Int>()
        val writer = CarLifeWriter<Int>("test-writer", capacity = 2) {
            if (it == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            written.put(it)
            true
        }
        val result = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val producer = Thread { result.set(writer.send(2, asynchronous = false)) }
        try {
            assertTrue(writer.send(1, asynchronous = true))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            producer.start()
            awaitWaiting(producer)
            producer.interrupt()
            producer.join(5000)
            assertEquals(false, result.get())
            assertTrue(writer.send(3, asynchronous = true))
            assertFalse(writer.send(4, asynchronous = true))
            release.countDown()
            assertEquals(1, written.poll(5, TimeUnit.SECONDS))
            assertEquals(3, written.poll(5, TimeUnit.SECONDS))
            assertTrue(written.isEmpty())
        } finally {
            release.countDown()
            producer.interrupt()
            writer.close()
        }
    }

    @Test fun backgroundSendsCannotOvertakePacketsQueuedByTheMainThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val writer = CarLifeWriter<Int>("test-writer") {
            if (it == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            written += it
            true
        }
        val producer = Executors.newSingleThreadExecutor()
        try {
            assertTrue(writer.send(1, asynchronous = true))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.send(2, asynchronous = true))
            val last = producer.submit<Boolean> { writer.send(3, asynchronous = false) }
            assertFalse(last.isDone)
            release.countDown()
            assertTrue(last.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), written)
        } finally {
            release.countDown()
            writer.close()
            producer.shutdownNow()
        }
    }

    @Test fun fullQueuesRejectMainThreadSendsAndBlockBackgroundProducers() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = CarLifeWriter<Int>("test-writer", capacity = 1) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            it != 2
        }
        val producer = Executors.newSingleThreadExecutor()
        try {
            assertTrue(writer.send(1, asynchronous = true))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(writer.send(2, asynchronous = true))
            val result = producer.submit<Boolean> { writer.send(2, asynchronous = false) }
            assertFalse(result.isDone)
            release.countDown()
            assertFalse("return the actual write failure", result.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            writer.close()
            producer.shutdownNow()
        }
    }

    @Test fun closingUnblocksProducersAndDiscardsQueuedPackets() {
        val entered = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = CarLifeWriter<Int>("test-writer") {
            if (it == 1) { entered.countDown(); release.await() } else queued.countDown()
            true
        }
        val producer = Executors.newSingleThreadExecutor()
        try {
            assertTrue(writer.send(1, asynchronous = true))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val result = producer.submit<Boolean> { writer.send(2, asynchronous = false) }
            writer.close()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertEquals(1L, queued.count)
            assertFalse(writer.send(3, asynchronous = false))
            assertFalse(writer.send(3, asynchronous = true))
        } finally {
            release.countDown()
            writer.close()
            producer.shutdownNow()
        }
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals("sender did not reach the blocked write", Thread.State.WAITING, thread.state)
    }
}
