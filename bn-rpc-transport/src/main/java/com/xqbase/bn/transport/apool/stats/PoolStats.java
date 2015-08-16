package com.xqbase.bn.transport.apool.stats;

/**
 * PoolStats Interface.
 *
 * @author Tony He
 */
public interface PoolStats {

    /**
     * Get the name of this pool.
     *
     * @return name of pool.
     */
    String getPoolName();

    /**
     * Get the total number of pool objects created
     * between the starting of the Pool and the call
     * to getStats().
     * Does not include create errors.
     *
     * @return the total number of pool objects created.
     */
    int getTotalCreated();

    /**
     * Get the total number of pool objects destroyed
     * between the starting of the Pool and the call
     * to getStats(). Includes lifecycle validation
     * failures, disposes and time-out objects, but
     * does not include destroy errors.
     *
     * @return The total number of pool objects destroyed.
     */
    int getTotalDestroyed();

    /**
     * Get the total number of lifecycle create errors between
     * the starting of the pool and the call to getStats().
     *
     * @return The total number of create errors.
     */
    int getTotalCreatedErrors();

    /**
     * Get the total number of lifecycle destroy errors between
     * the starting of the pool and the call to getStats().
     *
     * @return The total number of destroy errors.
     */
    int getTotalDestroyErrors();

    /**
     * Get the total number of pool objects destroyed because of
     * dispose or failed lifecycle validation between the starting
     * of the pool and the call to getStats().
     *
     * @return The total number of bad destroyed pool objects.
     */
    int getTotalBadDestroyed();

    /**
     * Get the total number of timed out pool objects between
     * the starting of the Pool and the call to getStats().
     *
     * @return The total number of timed out pool objects.
     */
    int getTotalTimeout();

    /**
     * Get the number of pool objects checked out at the time
     * of the call to getStats().
     *
     * @return The number of checked out pool objects.
     */
    int getCheckedOut();

    /**
     * Get the configured maximum pool size.
     *
     * @return The maximum pool size.
     */
    int getMaxPoolSize();

    /**
     * Get the configured minimum pool size.
     *
     * @return The minimum pool size.
     */
    int getMinPoolSize();

    /**
     * Get the pool size at the time of the call to getStats().
     *
     * @return The pool size.
     */
    int getPoolSize();

    /**
     * Get the number of objects that are idle (not checked out)
     * at the time of the call to getStats().
     *
     * @return The number of idle objects.
     */
    int getIdleCount();
}
