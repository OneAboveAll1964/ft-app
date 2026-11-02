package app.ft.carlife

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import app.ft.core.Prefs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CarBluetoothTest {
    @Test fun cancellingScopeClosesBothBlockingAcceptsWithoutStop() = fixture { f ->
        f.bt.start()
        await(f.accepting)
        f.job.cancel()
        await(f.serversClosed)
        runBlocking { withTimeout(5000) { f.job.join() } }
        f.servers.forEach { verify(it, times(1)).close() }
    }

    @Test fun cancellingScopeAlsoClosesConnectedSocketAndUnblocksRead() = fixture(connect = true) { f ->
        f.bt.start()
        await(f.reading)
        f.job.cancel()
        await(f.clientClosed)
        runBlocking { withTimeout(5000) { f.job.join() } }
        verify(f.client, times(1)).close()
        assertFalse(f.bt.open)
    }

    @Test fun callbackFailureClosesConnectedSocket() = fixture(connect = true) { f ->
        val failed = CountDownLatch(1)
        f.bt.onCar = { if (it != null) { failed.countDown(); throw IllegalStateException("callback failed") } }
        f.bt.start()
        await(failed)
        await(f.clientClosed)
        verify(f.client, times(1)).close()
    }

    @Test fun frameCallbackFailureClosesLinkAndOffersBluetoothAgain() = fixture(connect = true) { f ->
        val input = mock(InputStream::class.java)
        `when`(f.client.inputStream).thenReturn(input)
        `when`(input.read(any(ByteArray::class.java))).thenReturn(1)
        f.bt.onFrame = { throw IllegalStateException("bad frame") }
        f.bt.start()
        await(f.clientClosed)
        await(f.listeningAgain)
        assertTrue("callback failure must not cancel the listener's scope", f.job.isActive)
        verify(f.client, times(1)).close()
    }

    private class Fixture(val job: Job, val bt: CarBluetooth, val servers: List<BluetoothServerSocket>,
                          val accepting: CountDownLatch, val serversClosed: CountDownLatch,
                          val client: BluetoothSocket, val clientClosed: CountDownLatch, val reading: CountDownLatch,
                          val listeningAgain: CountDownLatch)

    private fun fixture(connect: Boolean = false, test: (Fixture) -> Unit) {
        val context = mock(Context::class.java)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(manager)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
        val servers = List(2) { mock(BluetoothServerSocket::class.java) }
        val closed = List(2) { CountDownLatch(1) }
        val accepting = CountDownLatch(2)
        val serversClosed = CountDownLatch(2)
        val client = mock(BluetoothSocket::class.java)
        val clientClosed = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val input = mock(InputStream::class.java)
        `when`(client.inputStream).thenReturn(input)
        `when`(client.isConnected).thenAnswer { clientClosed.count != 0L }
        doAnswer { clientClosed.countDown(); null }.`when`(client).close()
        `when`(input.read(any(ByteArray::class.java))).thenAnswer { reading.countDown(); await(clientClosed); -1 }
        servers.forEachIndexed { i, server ->
            doAnswer { closed[i].countDown(); serversClosed.countDown(); null }.`when`(server).close()
            `when`(server.accept()).thenAnswer {
                accepting.countDown()
                if (connect && i == 0) client else {
                    await(closed[i]); throw IOException("closed")
                }
            }
        }
        val next = AtomicInteger()
        val listeningAgain = CountDownLatch(1)
        `when`(adapter.listenUsingRfcommWithServiceRecord(anyString(), any(UUID::class.java))).thenAnswer {
            val index = next.getAndIncrement()
            if (index >= 2) listeningAgain.countDown()
            servers[index % 2]
        }
        val bt = CarBluetooth(context, mock(Prefs::class.java), scope)
        try { test(Fixture(job, bt, servers, accepting, serversClosed, client, clientClosed, reading, listeningAgain)) }
        finally { bt.stop(); job.cancel(); closed.forEach { it.countDown() }; clientClosed.countDown() }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Bluetooth operation did not finish", latch.await(5, TimeUnit.SECONDS))
    }
}
