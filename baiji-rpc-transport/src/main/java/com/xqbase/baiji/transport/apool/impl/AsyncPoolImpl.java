package com.xqbase.baiji.transport.apool.impl;

import com.xqbase.baiji.common.callback.Callback;
import com.xqbase.baiji.common.callback.SimpleCallback;
import com.xqbase.baiji.transport.apool.AsyncPool;
import com.xqbase.baiji.transport.apool.CreateLatch;
import com.xqbase.baiji.transport.apool.LifeCycle;
import com.xqbase.baiji.transport.apool.exceptions.SizeLimitExceededException;
import com.xqbase.baiji.transport.apool.stats.AsyncPoolStats;
import com.xqbase.baiji.transport.apool.stats.PoolStats;
import com.xqbase.baiji.transport.apool.util.Cancellable;
import com.xqbase.baiji.transport.apool.util.LinkedDeque;
import com.xqbase.baiji.transport.apool.util.None;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * The Async Pool Implementation.
 *
 * @author Tony He
 */
public class AsyncPoolImpl<T> implements AsyncPool<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncPoolImpl.class);

    private final String poolName;
    private final int maxSize;
    private final int minSize;
    private final int maxWaiters;
    private final long idleTimeout;
    private final ScheduledExecutorService timeoutExecutor;
    private volatile ScheduledFuture<?> objectTimeoutFuture;
    private final ExecutorService callbackExecutor;
    private final LifeCycle<T> lifeCycle;
    private final CreateLatch createLatch;
    private final Strategy strategy;

    private enum State { NOT_YET_STARTED, RUNNING, SHUTTING_DOWN, STOPPED }
    public enum Strategy { LRU, MRU }

    private int poolSize = 0;
    // The lock mutex
    private final Object lock = new Object();

    private final Deque<TimedObject<T>> idle = new LinkedList<>();
    private final LinkedDeque<Callback<T>> waiters = new LinkedDeque<>();

    private State state = State.NOT_YET_STARTED;
    private Callback<None> shutdownCallback = null;

    private int totalCreated = 0;
    private int totalDestroyed = 0;
    private int totalCreateErrors = 0;
    private int totalDestroyErrors = 0;
    private int totalBadDestroyed = 0;
    private int totalTimeout = 0;
    private int checkedOut = 0;

    // Construct an Async Pool with {@code Integer.MAX_VALUE} maxWaiters.
    public AsyncPoolImpl(String poolName,
                         int maxSize,
                         long idleTimeout,
                         LifeCycle<T> lifeCycle,
                         ScheduledExecutorService timeoutExecutor) {
        this(poolName, maxSize, idleTimeout, lifeCycle, timeoutExecutor, timeoutExecutor, Integer.MAX_VALUE);
    }

    // Construct an Async Pool with 0 minSize and Strategy.LRU
    public AsyncPoolImpl(String poolName,
                         int maxSize,
                         long idleTimeout,
                         LifeCycle<T> lifeCycle,
                         ScheduledExecutorService timeoutExecutor,
                         ExecutorService callbackExecutor,
                         int maxWaiters) {
        this(poolName, maxSize, idleTimeout, lifeCycle, timeoutExecutor, callbackExecutor,
                maxWaiters, 0, Strategy.LRU);
    }

    public AsyncPoolImpl(String poolName,
                         int maxSize,
                         long idleTimeout,
                         LifeCycle<T> lifeCycle,
                         ScheduledExecutorService timeoutExecutor,
                         ExecutorService callbackExecutor,
                         int maxWaiters,
                         int minSize,
                         Strategy strategy) {
        this(poolName, maxSize, idleTimeout, lifeCycle, timeoutExecutor, callbackExecutor, maxWaiters, minSize,
                new NoopCreateLatch(), strategy);
    }

    public AsyncPoolImpl(String poolName,
                         int maxSize,
                         long idleTimeout,
                         LifeCycle<T> lifeCycle,
                         ScheduledExecutorService timeoutExecutor,
                         ExecutorService callbackExecutor,
                         int maxWaiters,
                         int minSize,
                         CreateLatch createLatch,
                         Strategy strategy) {
        this.poolName = poolName;
        this.maxSize = maxSize;
        this.minSize = minSize;
        this.maxWaiters = maxWaiters;
        this.idleTimeout = idleTimeout;
        this.timeoutExecutor = timeoutExecutor;
        this.callbackExecutor = callbackExecutor;
        this.lifeCycle = lifeCycle;
        this.createLatch = createLatch;
        this.strategy = strategy;
    }

    @Override
    public String getName() {
        return poolName;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (state != State.NOT_YET_STARTED) {
                throw new IllegalStateException(poolName + " is " + state);
            }
            state = State.RUNNING;
            if (idleTimeout > 0) {
                long freq = Math.min(idleTimeout, 1000);
                objectTimeoutFuture = timeoutExecutor.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        timeoutObjects();
                    }
                }, freq, freq, TimeUnit.MILLISECONDS);
            }
        }

        // make the minimum required number of objects now
        for (int i = 0; i < minSize; i++) {
            if (shouldCreate()) {
                create();
            }
        }
    }

    @Override
    public Cancellable get(Callback<T> callback) {
        boolean create = false;
        boolean reject = false;
        final LinkedDeque.Node<Callback<T>> node;
        TimeTrackingCallback<T> timeTrackingCallback = new TimeTrackingCallback<>(callback);
        for (;;) {
            TimedObject<T> obj = null;
            final State innerState;
            synchronized (lock) {
                innerState = state;
                if (innerState == State.RUNNING) {
                    if (strategy == Strategy.LRU) {
                        obj = idle.pollFirst();
                    } else {
                        obj = idle.pollLast();
                    }

                    if (obj == null) {
                        if (waiters.size() < maxWaiters) {
                            // the object is null and the waiters queue is not full
                            node = waiters.addLastNode(timeTrackingCallback);
                            create = shouldCreate();
                        } else {
                            reject = true;
                            node = null;
                        }
                        break;
                    }
                }
            }
            if (innerState != State.RUNNING) {
                timeTrackingCallback.onError(new IllegalStateException(poolName + " is " + innerState));
                return null;
            }
            T rawObj = obj.getObj();
            if (lifeCycle.validateGet(rawObj)) {
                synchronized (lock) {
                    checkedOut ++;
                }
                timeTrackingCallback.onSuccess(rawObj);
                return null;
            }

            // The raw object is invalidate
            destroy(rawObj, true);
        }
        if (reject) {
            timeTrackingCallback.onError(new SizeLimitExceededException("APool " + poolSize + " exceeded max waiter size: " + maxWaiters));
        }

        if (create) {
            create();
        }
        return new Cancellable() {
            @Override
            public boolean cancel() {
                return waiters.removeNode(node) != null;
            }
        };
    }

    @Override
    public void put(T obj) {
        synchronized (lock) {
            checkedOut --;
        }
        if (!lifeCycle.validatePut(obj)) {
            destroy(obj, true);
            return;
        }
        createLatch.setPeriod(0);
        add(obj);
    }

    @Override
    public void dispose(T obj) {
        synchronized (lock) {
            checkedOut --;
        }
        destroy(obj, true);
    }

    @Override
    public void shutdown(Callback<None> callback) {
        final State innerState;

        synchronized (lock) {
            innerState = state;

            if (innerState == State.RUNNING) {
                state = State.SHUTTING_DOWN;
                shutdownCallback = callback;
            }
        }

        if (innerState != State.RUNNING) {
            callback.onError(new IllegalStateException(poolName + " is in State: " + innerState));
            return;
        }

        shutdownIfNeeded();
    }

    @Override
    public Collection<Callback<T>> cancelWaiters() {
        synchronized (lock) {
            List<Callback<T>> cancelWaiters = new ArrayList<>(waiters.size());
            for (Callback<T> waiter; (waiter = waiters.poll()) != null;) {
                cancelWaiters.add(waiter);
            }
            return cancelWaiters;
        }
    }

    private void shutdownIfNeeded() {
        Callback<None> done = checkShutdownComplete();
        if (done != null) {
            finishShutdown(done);
        }
    }

    /**
     * Check whether the shutdown process is complete.
     *
     * @return null if incomplete.
     */
    private Callback<None> checkShutdownComplete() {
        Callback<None> done = null;
        final State innerState;
        final int waitersSize;
        final int idleSize;
        final int innerPoolSize;

        synchronized (lock) {
            innerState = state;
            waitersSize = waiters.size();
            idleSize = idle.size();
            innerPoolSize = poolSize;

            if (innerState == State.SHUTTING_DOWN && waitersSize == 0 && idleSize == innerPoolSize) {
                state = State.STOPPED;
                done = shutdownCallback;
                shutdownCallback = null;
            }
        }

        return done;
    }

    private void finishShutdown(Callback<None> finish) {
        Future<?> future = objectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
        }
        finish.onSuccess(None.none());
    }

    @Override
    public PoolStats getStats() {
        // get a copy of stats
        synchronized (lock) {
            AsyncPoolStats stats = new AsyncPoolStats(
                    poolName,
                    maxSize,
                    minSize,
                    poolSize,
                    totalCreated,
                    totalCreateErrors,
                    totalDestroyed,
                    totalDestroyErrors,
                    totalBadDestroyed,
                    totalTimeout,
                    checkedOut,
                    idle.size()
            );
            return stats;
        }
    }

    /**
     * Whether another object creation should be initiated.
     *
     * @return true if another object create should be initiated.
     */
    public boolean shouldCreate() {
        boolean result = false;
        synchronized (lock) {
            if (state == State.RUNNING) {
                if (poolSize > maxSize) {

                } else if (waiters.size() > 0 || poolSize < minSize) {
                    poolSize ++;
                    result = true;
                }
            }
        }

        return result;
    }

    /**
     * The real object creation method.
     * PLEASE do not call this method while hold lock.
     */
    public void create() {
        createLatch.submit(new CreateLatch.Task() {
            @Override
            public void run(final SimpleCallback callback) {
                lifeCycle.create(new Callback<T>() {
                    @Override
                    public void onError(final Throwable e) {
                        createLatch.incrementPeriod();
                        // We drain all waiters and cancel all pending creates if a create fails.
                        // When a create fails, rate-limiting logical will be applied. In this case
                        // we may be initiating creations at a low rate than incoming requests. While
                        // creations are suppressed, it is better to deny all waiters and let them
                        // see the real cause.
                        final Collection<Callback<T>> waitersDenied;
                        final Collection<CreateLatch.Task> pendingTasks = createLatch.cancelPendingTasks();
                        boolean create;
                        synchronized (lock) {
                            totalCreateErrors ++;
                            create = objectDestroyed(1 + pendingTasks.size());
                            if (!waiters.isEmpty()) {
                                waitersDenied = waiters;
                            } else {
                                waitersDenied = Collections.emptyList();
                            }
                        }

                        // We should better handle the actual callback task
                        // in a separate thread.
                        callbackExecutor.submit(new Runnable() {
                            @Override
                            public void run() {
                                for (Callback<T> waiter : waitersDenied) {
                                    waiter.onError(e);
                                }
                            }
                        });

                        if (create) {
                            create();
                        }
                        callback.onDone();
                    }

                    @Override
                    public void onSuccess(T result) {
                        synchronized (lock) {
                            totalCreated ++;
                        }
                        add(result);
                        callback.onDone();
                    }
                });
            }
        });
    }

    /**
     * Destroy the pool object.
     *
     * @param obj the pool object to be destroyed.
     * @param bad whether the being destroyed pool object is bad or not.
     */
    private void destroy(T obj, boolean bad) {
        if (bad) {
            createLatch.incrementPeriod();
            synchronized (lock) {
                totalBadDestroyed ++;
            }
        }
        lifeCycle.destroy(obj, bad, new Callback<T>() {
            @Override
            public void onError(Throwable e) {
                boolean create;
                synchronized (lock) {
                    totalDestroyErrors++;
                    create = objectDestroyed();
                }
                if (create) {
                    create();
                }
            }

            @Override
            public void onSuccess(T result) {
                boolean create;
                synchronized (lock) {
                    totalDestroyed++;
                    create = objectDestroyed();
                }
                if (create) {
                    create();
                }
            }
        });
    }

    private boolean objectDestroyed() {
        return objectDestroyed(1);
    }

    /**
     * This method is safe to call while holding the lock.
     *
     * @param num number of objects have been destroyed.
     * @return true if another pool object creation should be initiated.
     */
    private boolean objectDestroyed(int num) {
        boolean create;
        synchronized (lock) {
            if (poolSize - num > 0) {
                poolSize -= num;
            } else {
                poolSize = 0;
            }
            create = shouldCreate();
        }

        return create;
    }

    /**
     * Add the newly created object to the idle pool
     * or directly transfer to the waiter.
     *
     * @param obj the newly created pool object.
     */
    private void add(T obj) {
        Callback<T> waiter;

        synchronized (lock) {
            // If we have waiters, the idle list must be empty.
            waiter = waiters.poll();
            if (waiter == null) {
                idle.offerLast(new TimedObject<T>(obj));
            } else {
                checkedOut ++;
            }
        }

        if (waiter != null) {
            waiter.onSuccess(obj);
        }
    }

    private void timeoutObjects() {
        Collection<T> timeoutIdle = reap(idle, idleTimeout);
        if (timeoutIdle.size() > 0) {
            LOGGER.debug(poolName + " disposing " + timeoutIdle.size() + " objects due to timeout");
            for (T t : timeoutIdle) {
                destroy(t, false);
            }
        }
    }

    /**
     * Get the queue of timeout objects.
     *
     * @param queue the original queue.
     * @param timeout the timeout.
     * @return queue of timeout objects.
     */
    private <U> Collection<U> reap(Queue<TimedObject<U>> queue, long timeout) {
        List<U> timeoutQueue = new ArrayList<>();
        long now = System.currentTimeMillis();
        long target = now - timeout;

        synchronized (lock) {
            int exceed = poolSize - minSize;
            for (TimedObject<U> p; (p = queue.peek()) != null && p.getTime() < target && exceed > 0; exceed--) {
                timeoutQueue.add(queue.poll().getObj());
                totalTimeout ++;
            }
        }

        return timeoutQueue;
    }

    private static class TimedObject<T> {

        private final T obj;
        private final long time;

        private TimedObject(T obj) {
            this.obj = obj;
            this.time = System.currentTimeMillis();
        }

        public T getObj() {
            return obj;
        }

        public long getTime() {
            return time;
        }
    }

    private class TimeTrackingCallback<T> implements Callback<T> {

        private final long startTime;
        private final Callback<T> callback;

        private TimeTrackingCallback(Callback<T> callback) {
            this.startTime = System.currentTimeMillis();
            this.callback = callback;
        }

        @Override
        public void onError(Throwable e) {
            callback.onError(e);
        }

        @Override
        public void onSuccess(T result) {
            callback.onSuccess(result);
        }
    }
}
