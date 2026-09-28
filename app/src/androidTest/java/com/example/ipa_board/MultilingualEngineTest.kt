package com.example.ipa_board

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MultilingualEngineTest {
    private fun query(type: Class<out EngineService>, raw: String): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) { response = it.data; latch.countDown(); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(Message.obtain(null, 1).apply {
                    replyTo = reply
                    data = Bundle().apply { putLong("revision", 71); putString("raw", raw) }
                })
            }
            override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
        }
        assertTrue(context.bindService(Intent(context, type), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Engine timed out: $raw", latch.await(180, TimeUnit.SECONDS))
            assertNotNull("Engine crashed", response)
            assertFalse(response.toString(), response!!.containsKey("error"))
            assertEquals(71L, response!!.getLong("revision"))
            return response!!.getStringArrayList("text").orEmpty()
        } finally { context.unbindService(connection) }
    }
    @Test fun rimeProvidesChineseAndEnglishFromRealDictionaries() {
        assertTrue(query(RimeService::class.java, "nihao").contains("你好"))
        val chinese = query(RimeService::class.java, "hanyu")
        assertTrue(chinese.toString(), chinese.contains("汉语"))
        assertTrue(chinese.toString(), chinese.contains("漢語"))
        assertTrue(query(RimeService::class.java, "hello").contains("hello"))
    }
    @Test fun mozcProvidesJapaneseFromRomaji() {
        val result = query(MozcService::class.java, "nihongo")
        assertTrue(result.toString(), result.contains("日本語"))
        assertTrue(query(MozcService::class.java, "konnichiha").contains("こんにちは"))
    }
}
