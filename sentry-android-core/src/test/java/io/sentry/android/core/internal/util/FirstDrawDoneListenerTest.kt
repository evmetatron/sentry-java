package io.sentry.android.core.internal.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.android.core.BuildInfoProvider
import io.sentry.test.getProperty
import org.junit.runner.RunWith
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Shadows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FirstDrawDoneListenerTest {

    private class Fixture {
        val application: Context = ApplicationProvider.getApplicationContext()
        val buildInfo = mock<BuildInfoProvider>()
        lateinit var onDrawListeners: ArrayList<ViewTreeObserver.OnDrawListener>

        fun getSut(apiVersion: Int = 26): View {
            whenever(buildInfo.sdkInfoVersion).thenReturn(apiVersion)
            val view = View(application)

            // Adding a listener forces ViewTreeObserver.mOnDrawListeners to be initialized and non-null.
            val dummyListener = ViewTreeObserver.OnDrawListener {}
            view.viewTreeObserver.addOnDrawListener(dummyListener)
            view.viewTreeObserver.removeOnDrawListener(dummyListener)

            // Obtain mOnDrawListeners field through reflection
            onDrawListeners = view.viewTreeObserver.getProperty("mOnDrawListeners")
            assertTrue(onDrawListeners.isEmpty())

            return view
        }
    }

    private val fixture = Fixture()

    @Test
    fun `registerForNextDraw adds listener on attach state changed on sdk 25-`() {
        val view = fixture.getSut(25)

        // OnDrawListener is not registered, it is delayed for later
        FirstDrawDoneListener.registerForNextDraw(view, {}, fixture.buildInfo)
        assertTrue(fixture.onDrawListeners.isEmpty())

        // Register listener after the view is attached to a window
        val listenerInfo = Class.forName("android.view.View\$ListenerInfo")
        val mListenerInfo: Any = view.getProperty("mListenerInfo")
        val mOnAttachStateChangeListeners: CopyOnWriteArrayList<View.OnAttachStateChangeListener> =
            mListenerInfo.getProperty(listenerInfo, "mOnAttachStateChangeListeners")
        assertFalse(mOnAttachStateChangeListeners.isEmpty())

        // Dispatch onViewAttachedToWindow()
        for (listener in mOnAttachStateChangeListeners) {
            listener.onViewAttachedToWindow(view)
        }

        assertFalse(fixture.onDrawListeners.isEmpty())
        assertIs<FirstDrawDoneListener>(fixture.onDrawListeners[0])

        // mOnAttachStateChangeListeners is automatically removed
        assertTrue(mOnAttachStateChangeListeners.isEmpty())
    }

    @Test
    fun `registerForNextDraw adds listener on sdk 26+`() {
        val view = fixture.getSut()

        // Immediately register an OnDrawListener to ViewTreeObserver
        FirstDrawDoneListener.registerForNextDraw(view, {}, fixture.buildInfo)
        assertFalse(fixture.onDrawListeners.isEmpty())
        assertIs<FirstDrawDoneListener>(fixture.onDrawListeners[0])
    }

    @Test
    fun `registerForNextDraw posts callback to front of queue`() {
        val view = fixture.getSut()
        val handler = Handler(Looper.getMainLooper())
        val drawDoneCallback = mock<Runnable>()
        val otherCallback = mock<Runnable>()
        val inOrder = inOrder(drawDoneCallback, otherCallback)
        FirstDrawDoneListener.registerForNextDraw(view, drawDoneCallback, fixture.buildInfo)
        handler.post(otherCallback) // 3rd in queue
        handler.postAtFrontOfQueue(otherCallback) // 2nd in queue
        view.viewTreeObserver.dispatchOnDraw() // 1st in queue
        verify(drawDoneCallback, never()).run()
        verify(otherCallback, never()).run()

        // Execute all posted tasks
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        inOrder.verify(drawDoneCallback).run()
        inOrder.verify(otherCallback, times(2)).run()
        inOrder.verifyNoMoreInteractions()
    }

    @Test
    fun `registerForNextDraw unregister itself after onDraw`() {
        val view = fixture.getSut()
        FirstDrawDoneListener.registerForNextDraw(view, {}, fixture.buildInfo)
        assertFalse(fixture.onDrawListeners.isEmpty())

        // Does not remove OnDrawListener before onDraw, even if OnGlobalLayout is triggered
        view.viewTreeObserver.dispatchOnGlobalLayout()
        assertFalse(fixture.onDrawListeners.isEmpty())

        // Removes OnDrawListener in the next OnGlobalLayout after onDraw
        view.viewTreeObserver.dispatchOnDraw()
        view.viewTreeObserver.dispatchOnGlobalLayout()
        assertTrue(fixture.onDrawListeners.isEmpty())
    }

    @Test
    fun `registerForNextDraw calls the given callback on the main thread after onDraw`() {
        val view = fixture.getSut()
        val r: Runnable = mock()
        FirstDrawDoneListener.registerForNextDraw(view, r, fixture.buildInfo)
        view.viewTreeObserver.dispatchOnDraw()

        // Execute all tasks posted to main looper
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        verify(r).run()
    }
}
