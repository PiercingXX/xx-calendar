package com.piercingxx.calendar.alarm

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §8.6 notification presentation mapping: `headsUp` picks the channel,
 * `lockScreenTitle` picks lock-screen visibility. Robolectric for the
 * NotificationCompat constants, matching ReceiverSmokeTest's convention.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderPresentationTest {

    @Test
    fun `quiet default uses the default-importance reminders channel`() {
        assertEquals(ReminderReceiver.CHANNEL_ID, ReminderReceiver.channelIdFor(headsUp = false))
    }

    @Test
    fun `headsUp selects the dedicated high-importance channel`() {
        assertEquals(ReminderReceiver.CHANNEL_ID_HEADS_UP, ReminderReceiver.channelIdFor(headsUp = true))
    }

    @Test
    fun `channel ids are the v2 generation carrying the bundled sound`() {
        // The literal ids matter: a channel's sound is frozen at creation, so
        // shipping the bundled chime required bumping "reminders" /
        // "reminders_heads_up" to their _v2 successors.
        assertEquals("reminders_v2", ReminderReceiver.CHANNEL_ID)
        assertEquals("reminders_heads_up_v2", ReminderReceiver.CHANNEL_ID_HEADS_UP)
    }

    @Test
    fun `lock screen is private by default and public when titles are shown`() {
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            ReminderReceiver.lockscreenVisibilityFor(showTitle = false),
        )
        assertEquals(
            NotificationCompat.VISIBILITY_PUBLIC,
            ReminderReceiver.lockscreenVisibilityFor(showTitle = true),
        )
    }
}
