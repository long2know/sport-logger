package com.long2know.sportlogger;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceBindingOwnerTest {
    @Test
    public void oldActivityUnbindCannotConsumeRecreatedActivityBinding() {
        ServiceBindingOwner oldActivity = new ServiceBindingOwner();
        ServiceBindingOwner recreatedActivity = new ServiceBindingOwner();
        AtomicInteger oldUnbinds = new AtomicInteger();
        AtomicInteger recreatedUnbinds = new AtomicInteger();
        oldActivity.setBound(true);
        recreatedActivity.setBound(true);

        oldActivity.unbindIfBound(oldUnbinds::incrementAndGet);

        assertFalse(oldActivity.isBound());
        assertTrue(recreatedActivity.isBound());
        assertTrue(oldUnbinds.get() == 1);
        assertTrue(recreatedUnbinds.get() == 0);

        recreatedActivity.unbindIfBound(recreatedUnbinds::incrementAndGet);
        assertFalse(recreatedActivity.isBound());
        assertTrue(recreatedUnbinds.get() == 1);
    }

    @Test
    public void repeatedActivityDestroyUnbindsExactlyOnce() {
        ServiceBindingOwner activity = new ServiceBindingOwner();
        AtomicInteger unbinds = new AtomicInteger();
        activity.setBound(true);

        activity.unbindIfBound(unbinds::incrementAndGet);
        activity.unbindIfBound(unbinds::incrementAndGet);

        assertFalse(activity.isBound());
        assertTrue(unbinds.get() == 1);
    }
}
