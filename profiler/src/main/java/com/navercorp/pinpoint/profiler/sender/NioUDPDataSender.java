package com.navercorp.pinpoint.profiler.sender;

import com.navercorp.pinpoint.rpc.PinpointSocketException;
import com.navercorp.pinpoint.rpc.buffer.ByteBufferFactory;
import com.navercorp.pinpoint.rpc.buffer.ByteBufferFactoryLocator;
import com.navercorp.pinpoint.rpc.buffer.ByteBufferType;
import com.navercorp.pinpoint.thrift.io.ByteBufferOutputStream;
import com.navercorp.pinpoint.thrift.io.HeaderTBaseSerializer2;
import com.navercorp.pinpoint.thrift.io.HeaderTBaseSerializerFactory2;
import com.navercorp.pinpoint.thrift.io.NetworkAvailabilityCheckPacket;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;

/**
 * @Author Taejin Koo
 */
public class NioUDPDataSender extends AbstractDataSender implements DataSender {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    protected final boolean isDebug = logger.isDebugEnabled();

    public static final int SOCKET_TIMEOUT = 1000 * 5;
    public static final int SEND_BUFFER_SIZE = 1024 * 64 * 16;
    public static final int UDP_MAX_PACKET_LENGTH = 65507;

    private final DatagramChannel datagramChannel;
    private final HeaderTBaseSerializer2 serializer;
    private final ByteBufferOutputStream byteBufferOutputStream;

    private final AsyncQueueingExecutor<Object> executor;

    private volatile boolean closed = false;

    public NioUDPDataSender(String host, int port, String threadName, int queueSize) {
        this(host, port, threadName, queueSize, SOCKET_TIMEOUT, SEND_BUFFER_SIZE);
    }

    public NioUDPDataSender(String host, int port, String threadName, int queueSize, int timeout, int sendBufferSize) {
        if (host == null ) {
            throw new NullPointerException("host must not be null");
        }
        if (threadName == null) {
            throw new NullPointerException("threadName must not be null");
        }
        if (queueSize <= 0) {
            throw new IllegalArgumentException("queueSize");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout");
        }
        if (sendBufferSize <= 0) {
            throw new IllegalArgumentException("sendBufferSize");
        }

        // TODO If fail to create socket, stop agent start
        logger.info("NioUDPDataSender initialized. host={}, port={}", host, port);
        this.datagramChannel = createChannel(host, port, timeout, sendBufferSize);

        HeaderTBaseSerializerFactory2 serializerFactory = new HeaderTBaseSerializerFactory2();
        this.serializer = serializerFactory.createSerializer();

        ByteBufferFactory bufferFactory = ByteBufferFactoryLocator.getFactory(ByteBufferType.DIRECT);
        ByteBuffer byteBuffer = bufferFactory.getBuffer(UDP_MAX_PACKET_LENGTH);
        this.byteBufferOutputStream = new ByteBufferOutputStream(byteBuffer);

        this.executor = createAsyncQueueingExecutor(queueSize, threadName);
    }

    private DatagramChannel createChannel(String host, int port, int timeout, int sendBufferSize) {
        DatagramChannel datagramChannel = null;
        DatagramSocket socket = null;
        try {
            datagramChannel = DatagramChannel.open();
            socket = datagramChannel.socket();
            socket.setSoTimeout(timeout);
            socket.setSendBufferSize(sendBufferSize);

            if (logger.isWarnEnabled()) {
                final int checkSendBufferSize = socket.getSendBufferSize();
                if (sendBufferSize != checkSendBufferSize) {
                    logger.warn("DatagramChannel.setSendBufferSize() error. {}!={}", sendBufferSize, checkSendBufferSize);
                }
            }

            InetSocketAddress serverAddress = new InetSocketAddress(host, port);
            datagramChannel.connect(serverAddress);

            return datagramChannel;
        } catch (IOException e) {
            if (socket != null) {
                socket.close();
            }

            if (datagramChannel != null) {
                try {
                    datagramChannel.close();
                } catch (IOException ignored) {
                }
            }

            throw new IllegalStateException("DatagramChannel create fail. Cause" + e.getMessage(), e);
        }
    }

    @Override
    public boolean send(TBase<?, ?> data) {
        return executor.execute(data);
    }

    @Override
    public void stop() {
        try {
            closed = true;
            executor.stop();
        } finally {
            try {
                byteBufferOutputStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public boolean isNetworkAvailable() {
        final NetworkAvailabilityCheckPacket dto = new NetworkAvailabilityCheckPacket();
        try {
            sendPacket(dto);

            if (logger.isInfoEnabled()) {
                logger.info("Data sent. {}", dto);
            }

            final byte[] receiveData = new byte[NetworkAvailabilityCheckPacket.DATA_OK.length];
            datagramChannel.read(ByteBuffer.wrap(receiveData));

            if (logger.isInfoEnabled()) {
                logger.info("Data received. {}", Arrays.toString(receiveData));
            }

            return Arrays.equals(NetworkAvailabilityCheckPacket.DATA_OK , receiveData);
        } catch (IOException e) {
            logger.warn("packet send error {}", dto, e);
            return false;
        }
    }

    protected void sendPacket(Object message) {
        if (closed) {
            throw new PinpointSocketException("NioUDPDataSender already closed.");
        }

        if (message instanceof TBase) {
            byteBufferOutputStream.clear();

            final TBase dto = (TBase) message;
            // do not copy bytes because it's single threaded

            try {
                serializer.serialize(dto,  byteBufferOutputStream);
            } catch (TException e) {
                throw new PinpointSocketException("Serialize " + dto + " failed. Error:" +  e.getMessage(), e);
            }
            ByteBuffer byteBuffer = byteBufferOutputStream.getByteBuffer();
            int bufferSize = byteBuffer.remaining();
            try {
                datagramChannel.write(byteBuffer);
            } catch (IOException e) {
                final Thread currentThread = Thread.currentThread();
                if (currentThread.isInterrupted()) {
                    logger.warn("{} thread interrupted.", currentThread.getName());
                    throw new PinpointSocketException(currentThread.getName() + " thread interrupted.", e);
                } else {
                    throw new PinpointSocketException("packet send error. size:" + bufferSize + ", " +  dto, e);
                }
            }
        } else {
            logger.warn("sendPacket fail. invalid type:{}", message != null ? message.getClass() : null);
            return;
        }
    }

}
