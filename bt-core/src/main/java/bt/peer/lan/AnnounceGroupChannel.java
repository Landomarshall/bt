/*
 * Copyright (c) 2016—2017 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.peer.lan;

import bt.net.InternetProtocolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repairable datagram channel.
 * If the invocation of {@link #send(ByteBuffer)} or {@link #receive(ByteBuffer)} results in an exception,
 * then the caller can call {@link #closeQuietly()} and retry the original operation, which will result in the creation of a new channel.
 */
class AnnounceGroupChannel {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnnounceGroupChannel.class);

    private final AnnounceGroup group;

    private final Selector selector;
    private DatagramChannel channel;

    private final AtomicBoolean shutdown;

    public AnnounceGroupChannel(AnnounceGroup group,
                                Selector selector) {
        this.group = group;
        this.selector = selector;
        this.shutdown = new AtomicBoolean(false);
    }

    public AnnounceGroup getGroup() {
        return group;
    }

    public synchronized void send(ByteBuffer buffer) throws IOException {
        getChannel().write(buffer);
    }

    public synchronized SocketAddress receive(ByteBuffer buffer) throws IOException {
        return getChannel().receive(buffer);
    }

    private synchronized DatagramChannel getChannel() throws IOException {
        if (channel == null || !channel.isOpen()) {
            if (shutdown.get()) {
                throw new IllegalStateException("Channel has been shut down");
            }
            ProtocolFamily protocolFamily = InternetProtocolUtils.getProtocolFamily(group.getAddress().getAddress());
            DatagramChannel _channel = selector.provider().openDatagramChannel(protocolFamily);
            _channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            // bind to any-local before setting TTL
            if (protocolFamily == StandardProtocolFamily.INET) {
                _channel.bind(new InetSocketAddress(Inet4Address.getByName("0.0.0.0"), 0));
            } else {
                _channel.bind(new InetSocketAddress(Inet6Address.getByName("[::]"), 0));
            }
            _channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, group.getTimeToLive());
            _channel.connect(group.getAddress()); // not a real connect, just for convenience
            channel = _channel;
        }
        return channel;
    }

    public synchronized void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            closeQuietly();
        }
    }

    public synchronized void closeQuietly() {
        if (channel != null) {
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException e) {
                LOGGER.error("Failed to close channel", e);
            } finally {
                channel = null;
            }
        }
    }
}
