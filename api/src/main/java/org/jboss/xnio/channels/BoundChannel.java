package org.jboss.xnio.channels;

/**
 * A channel that is bound to a local address.
 *
 * @param <A> the type of address associated with this channel
 */
public interface BoundChannel<A> extends ConfigurableChannel {
    /**
     * Get the local address that this channel is bound to.
     *
     * @return the local address
     */
    A getLocalAddress();
}