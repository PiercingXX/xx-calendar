package com.piercingxx.calendar.widget

import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * S1 regression: `onDisabled` fires per provider type, not per process, so
 * WidgetRefresher must keep the shared ContentObserver attached while either
 * MonthWidget or ScheduleWidget still has a pinned instance. These tests walk
 * the exact removal sequence that used to strand the survivor.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefresherTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() = WidgetRefresher.resetForTests()

    private fun instanceObservers() =
        shadowOf(context.contentResolver)
            .getContentObservers(CalendarContract.Instances.CONTENT_URI)

    private fun eventObservers() =
        shadowOf(context.contentResolver)
            .getContentObservers(CalendarContract.Events.CONTENT_URI)

    private fun assertAttached() {
        assertEquals(1, instanceObservers().size)
        assertEquals(1, eventObservers().size)
    }

    private fun assertDetached() {
        assertEquals(0, instanceObservers().size)
        assertEquals(0, eventObservers().size)
    }

    @Test
    fun `first registration attaches one observer per watched uri`() {
        WidgetRefresher.register(context, MonthWidget::class.java)

        assertAttached()
    }

    @Test
    fun `removing one provider type keeps observation alive for the survivor`() {
        WidgetRefresher.register(context, MonthWidget::class.java)
        WidgetRefresher.register(context, ScheduleWidget::class.java)

        // The old bug: Schedule's last instance removed while Month stays.
        WidgetRefresher.unregister(context, ScheduleWidget::class.java)

        assertAttached()

        WidgetRefresher.unregister(context, MonthWidget::class.java)
        assertDetached()
    }

    @Test
    fun `repeat registrations do not stack duplicate observers`() {
        WidgetRefresher.register(context, MonthWidget::class.java)
        WidgetRefresher.register(context, MonthWidget::class.java)
        WidgetRefresher.register(context, ScheduleWidget::class.java)

        assertAttached()

        WidgetRefresher.unregister(context, ScheduleWidget::class.java)
        WidgetRefresher.unregister(context, MonthWidget::class.java)
        assertDetached()

        // A stale drop-out for an already-removed provider stays a no-op.
        WidgetRefresher.unregister(context, MonthWidget::class.java)
        assertDetached()
    }

    @Test
    fun `unregister without prior registration is a silent no-op`() {
        WidgetRefresher.unregister(context, MonthWidget::class.java)

        assertDetached()
    }

    @Test
    fun `observation re-attaches after every provider dropped out`() {
        WidgetRefresher.register(context, MonthWidget::class.java)
        WidgetRefresher.unregister(context, MonthWidget::class.java)
        assertDetached()

        WidgetRefresher.register(context, ScheduleWidget::class.java)
        assertAttached()
    }
}
