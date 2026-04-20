package io.github.kongweiguang.voice.agent.audio;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 线程安全的固定大小字节缓冲区，只保留最近写入的字节。
 *
 * @author kongweiguang
 */
public class CircularByteBuffer {
    /**
     * 固定容量的底层字节数组。
     */
    private final byte[] buffer;
    /**
     * 虚拟线程场景下避免使用 synchronized，显式锁只保护短时间内存拷贝。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 下一次写入数据的位置。
     */
    private int writeIndex;

    /**
     * 当前已保存的有效字节数。
     */
    private int size;

    /**
     * 创建固定容量字节缓冲区。
     */
    public CircularByteBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.buffer = new byte[capacity];
    }

    /**
     * 写入字节并在容量不足时丢弃最旧数据。
     */
    public void write(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        lock.lock();
        try {
            if (data.length >= buffer.length) {
                System.arraycopy(data, data.length - buffer.length, buffer, 0, buffer.length);
                writeIndex = 0;
                size = buffer.length;
                return;
            }
            for (byte datum : data) {
                buffer[writeIndex] = datum;
                writeIndex = (writeIndex + 1) % buffer.length;
                if (size < buffer.length) {
                    size++;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按时间顺序返回最新字节，但不消费缓冲区内容。
     */
    public byte[] readLatest(int length) {
        lock.lock();
        try {
            int actual = Math.min(Math.max(length, 0), size);
            byte[] out = new byte[actual];
            int start = (writeIndex - actual + buffer.length) % buffer.length;
            for (int i = 0; i < actual; i++) {
                out[i] = buffer[(start + i) % buffer.length];
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前缓冲区全部有效内容的快照。
     */
    public byte[] snapshot() {
        lock.lock();
        try {
            return readLatest(size);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空缓冲区并重置写入位置。
     */
    public void clear() {
        lock.lock();
        try {
            Arrays.fill(buffer, (byte) 0);
            writeIndex = 0;
            size = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前有效字节数。
     */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回固定缓冲区容量。
     */
    public int capacity() {
        return buffer.length;
    }
}
