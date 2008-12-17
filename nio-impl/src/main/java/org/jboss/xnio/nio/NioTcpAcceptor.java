/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.xnio.nio;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import org.jboss.xnio.AbstractFutureConnection;
import org.jboss.xnio.CloseableTcpAcceptor;
import org.jboss.xnio.FailedFutureConnection;
import org.jboss.xnio.FinishedFutureConnection;
import org.jboss.xnio.FutureConnection;
import org.jboss.xnio.IoHandler;
import org.jboss.xnio.IoUtils;
import org.jboss.xnio.TcpChannelDestination;
import org.jboss.xnio.channels.TcpChannel;
import org.jboss.xnio.log.Logger;

/**
 *
 */
public final class NioTcpAcceptor implements CloseableTcpAcceptor {
    private static final Logger log = Logger.getLogger("org.jboss.xnio.nio.tcp.acceptor");

    private final NioXnio nioXnio;
    private final Executor executor;

    private final Object lock = new Object();

    private boolean closed;

    private final Boolean keepAlive;
    private final Boolean oobInline;
    private final Integer receiveBufferSize;
    private final Boolean reuseAddress;
    private final Boolean tcpNoDelay;
    private final boolean manageConnections;

    private NioTcpAcceptor(NioTcpAcceptorConfig config) {
        nioXnio = config.getXnio();
        executor = config.getExecutor();
        if (nioXnio == null) {
            throw new NullPointerException("nioXnio is null");
        }
        if (executor == null) {
            throw new NullPointerException("executor is null");
        }
        keepAlive = config.getKeepAlive();
        oobInline = config.getOobInline();
        receiveBufferSize = config.getReceiveBuffer();
        reuseAddress = config.getReuseAddresses();
        tcpNoDelay = config.getNoDelay();
        manageConnections = config.isManageConnections();
    }

    static NioTcpAcceptor create(NioTcpAcceptorConfig config) {
        return new NioTcpAcceptor(config);
    }

    public FutureConnection<SocketAddress, TcpChannel> acceptTo(final SocketAddress dest, final IoHandler<? super TcpChannel> handler) {
        try {
            synchronized (lock) {
                if (closed) {
                    throw new ClosedChannelException();
                }
                final ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.configureBlocking(false);
                final ServerSocket serverSocket = serverSocketChannel.socket();
                if (receiveBufferSize != null) serverSocket.setReceiveBufferSize(receiveBufferSize.intValue());
                if (reuseAddress != null) serverSocket.setReuseAddress(reuseAddress.booleanValue());
                serverSocket.bind(dest, 1);
                final SocketChannel socketChannel = serverSocketChannel.accept();
                // unlikely, but...
                if (socketChannel != null) {
                    return new FinishedFutureConnection<SocketAddress, TcpChannel>(new NioTcpChannel(nioXnio, socketChannel, handler, executor, manageConnections));
                }
                final Handler nioHandler = new Handler(serverSocketChannel, handler);
                final NioHandle handle = nioXnio.addConnectHandler(serverSocketChannel, nioHandler, true);
                nioHandler.handle = handle;
                handle.resume(SelectionKey.OP_ACCEPT);
                return nioHandler.future;
            }
        } catch (IOException e) {
            return new FailedFutureConnection<SocketAddress, TcpChannel>(e, dest);
        }
    }

    public TcpChannelDestination createChannelDestination(final SocketAddress dest) {
        return new TcpChannelDestination() {
            public FutureConnection<SocketAddress, TcpChannel> accept(final IoHandler<? super TcpChannel> handler) {
                return acceptTo(dest, handler);
            }
        };
    }

    public void close() throws IOException {
        synchronized (lock) {
            if (! closed) {
                log.trace("Closing %s", this);
                closed = true;
            }
        }
    }

    private final class Handler implements Runnable {
        private final FutureImpl future;
        private final ServerSocketChannel serverSocketChannel;
        private final IoHandler<? super TcpChannel> handler;
        private volatile NioHandle handle;

        public Handler(final ServerSocketChannel serverSocketChannel, final IoHandler<? super TcpChannel> handler) {
            this.serverSocketChannel = serverSocketChannel;
            this.handler = handler;
            future = new FutureImpl(executor, serverSocketChannel.socket().getLocalSocketAddress());
        }

        public void run() {
            try {
                boolean ok = false;
                final SocketChannel socketChannel = serverSocketChannel.accept();
                if (socketChannel == null) {
                    handle.resume(SelectionKey.OP_ACCEPT);
                    return;
                }
                try {
                    IoUtils.safeClose(serverSocketChannel);
                    socketChannel.configureBlocking(false);
                    final Socket socket = socketChannel.socket();
                    if (keepAlive != null) socket.setKeepAlive(keepAlive.booleanValue());
                    if (oobInline != null) socket.setOOBInline(oobInline.booleanValue());
                    if (tcpNoDelay != null) socket.setTcpNoDelay(tcpNoDelay.booleanValue());
                    final NioTcpChannel channel = new NioTcpChannel(nioXnio, socketChannel, handler, executor, manageConnections);
                    ok = HandlerUtils.<TcpChannel>handleOpened(handler, channel);
                    if (ok) {
                        nioXnio.addManaged(channel);
                        log.trace("TCP server accepted connection");
                    }
                    future.setResult(channel);
                } finally {
                    if (! ok) {
                        log.trace("TCP server failed to accept connection");
                        // do NOT call close handler, since open handler was either not called or it failed
                        IoUtils.safeClose(serverSocketChannel);
                        IoUtils.safeClose(socketChannel);
                    }
                }
            } catch (ClosedChannelException e) {
                IoUtils.safeClose(serverSocketChannel);
                log.trace("Channel closed: %s", e.getMessage());
                future.setException(e);
            } catch (IOException e) {
                IoUtils.safeClose(serverSocketChannel);
                log.trace(e, "I/O error on TCP server");
                future.setException(e);
            }
        }

        private final class FutureImpl extends AbstractFutureConnection<SocketAddress, TcpChannel> {
            private final Executor executor;
            private final SocketAddress localAddress;

            public FutureImpl(final Executor executor, final SocketAddress address) {
                this.executor = executor;
                localAddress = address;
            }

            protected boolean setException(final IOException exception) {
                return super.setException(exception);
            }

            protected boolean setResult(final TcpChannel result) {
                return super.setResult(result);
            }

            protected boolean finishCancel() {
                return super.finishCancel();
            }

            protected void runNotifier(final Notifier<TcpChannel> streamChannelNotifier) {
                executor.execute(new Runnable() {
                    public void run() {
                        try {
                            streamChannelNotifier.notify(FutureImpl.this);
                        } catch (Throwable t) {
                            log.error(t, "Completion handler \"%s\" failed", streamChannelNotifier);
                        }
                    }
                });
            }

            public SocketAddress getLocalAddress() {
                return localAddress;
            }

            public FutureConnection<SocketAddress, TcpChannel> cancel() {
                IoUtils.safeClose(serverSocketChannel);
                finishCancel();
                return this;
            }
        }
    }

    public String toString() {
        return String.format("TCP acceptor (NIO) <%s>", Integer.toString(hashCode(), 16));
    }
}