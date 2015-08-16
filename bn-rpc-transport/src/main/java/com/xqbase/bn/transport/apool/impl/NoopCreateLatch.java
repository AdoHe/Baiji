package com.xqbase.bn.transport.apool.impl;

import com.xqbase.bn.common.callback.SimpleCallback;
import com.xqbase.bn.transport.apool.CreateLatch;

import java.util.Collection;
import java.util.Collections;

/**
 * A Create Latch that has no limit.
 *
 * @author Tony He
 */
public class NoopCreateLatch implements CreateLatch {

    private static final SimpleCallback NULL_CALLBACK = new SimpleCallback() {

        @Override
        public void onDone() {
        }
    };

    @Override
    public void submit(Task t) {
        t.run(NULL_CALLBACK);
    }

    @Override
    public void setPeriod(long period) {

    }

    @Override
    public void incrementPeriod() {

    }

    @Override
    public Collection<Task> cancelPendingTasks() {
        return Collections.emptyList();
    }
}