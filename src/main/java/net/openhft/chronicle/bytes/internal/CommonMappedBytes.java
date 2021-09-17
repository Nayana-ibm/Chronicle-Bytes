/*
 * Copyright 2016-2020 chronicle.software
 *
 * https://chronicle.software
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

package net.openhft.chronicle.bytes.internal;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.io.ReferenceOwner;
import net.openhft.chronicle.core.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

import static net.openhft.chronicle.core.util.StringUtils.*;

/**
 * Bytes to wrap memory mapped data.
 * <p>
 * NOTE These Bytes are single Threaded as are all Bytes.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class CommonMappedBytes extends MappedBytes {

    protected final MappedFile mappedFile;
    private final boolean backingFileIsReadOnly;
    protected MappedBytesStore bytesStore;
    protected long lastActualSize = 0;

    // assume the mapped file is reserved already.
    protected CommonMappedBytes(@NotNull final MappedFile mappedFile)
            throws IllegalStateException {
        this(mappedFile, "");
    }

    protected CommonMappedBytes(@NotNull final MappedFile mappedFile, final String name)
            throws IllegalStateException {
        super(name);

        assert mappedFile != null;
        this.mappedFile = mappedFile;
        mappedFile.reserve(this);
        this.backingFileIsReadOnly = !mappedFile.file().canWrite();
        assert !mappedFile.isClosed();
        clear();
    }

    @Override
    public void clearUsedByThread() {
        super.clearUsedByThread();
        closeable.clearUsedByThread();
    }

    private final AbstractCloseable closeable = new AbstractCloseable() {
        @Override
        protected void performClose() throws IllegalStateException {
            CommonMappedBytes.this.performClose();
        }
    };

    @Override
    protected void bytesStore(BytesStore bytesStore) {
        super.bytesStore(bytesStore);
        if (bytesStore instanceof MappedBytesStore)
            this.bytesStore = (MappedBytesStore) bytesStore;
        else
            this.bytesStore = null;
    }

    public @NotNull CommonMappedBytes write(@NotNull final byte[] bytes,
                                            final int offset,
                                            final int length)
            throws IllegalStateException, BufferOverflowException {
        throwExceptionIfClosed();

        write(writePosition(), bytes, offset, length);
        uncheckedWritePosition(writePosition() + Math.min(length, bytes.length - offset));
        return this;
    }

    @NotNull
    @Override
    public CommonMappedBytes write(@NotNull final RandomDataInput bytes)
            throws IllegalStateException, BufferOverflowException {
        throwExceptionIfClosed();

        assert bytes != this : "you should not write to yourself !";
        final long remaining = bytes.readRemaining();
        write(writePosition(), bytes);
        uncheckedWritePosition(writePosition() + remaining);
        return this;
    }

    @NotNull
    public CommonMappedBytes write(final long offsetInRDO, @NotNull final RandomDataInput bytes)
            throws BufferOverflowException, IllegalStateException {
        throwExceptionIfClosed();

        try {
            write(offsetInRDO, bytes, bytes.readPosition(), bytes.readRemaining());
            return this;
        } catch (BufferUnderflowException e) {
            throw new AssertionError(e);
        }
    }

    @NotNull
    public MappedFile mappedFile() {
        return mappedFile;
    }

    @Override
    public BytesStore<Bytes<Void>, Void> copy()
            throws IllegalStateException {
        return NativeBytes.copyOf(this);
    }

    @Override
    public long capacity() {
        return mappedFile.capacity();
    }

    @Override
    public Bytes<Void> readLimitToCapacity() {
        uncheckedWritePosition(mappedFile.capacity());
        return this;
    }

    @Override
    public long realReadRemaining() {
        long limit = readLimit();
        if (limit > lastActualSize)
            limit = Math.min(realCapacity(), limit);
        return limit - readPosition();
    }

    @Override
    public long realCapacity() {

        try {
            return lastActualSize = mappedFile.actualSize();

        } catch (Exception e) {
            Jvm.warn().on(getClass(), "Unable to obtain the real size for " + mappedFile.file(), e);
            return lastActualSize = 0;
        }
    }

    @Nullable
    @Override
    public String read8bit()
            throws IORuntimeException, BufferUnderflowException, IllegalStateException, ArithmeticException {
//        throwExceptionIfClosed();

        return BytesInternal.read8bit(this);
    }

    private long checkSize(long adding) {
        if (adding < 0 || adding > MAX_CAPACITY)
            throw new IllegalArgumentException("Invalid size " + adding);
        return adding;
    }

    @Override
    public @NotNull Bytes<Void> writeSkip(long bytesToSkip)
            throws BufferOverflowException, IllegalStateException {
        // only check up to 128 bytes are real.
        writeCheckOffset(writePosition(), Math.min(128, bytesToSkip));
        // the rest can be lazily allocated.
        uncheckedWritePosition(writePosition() + bytesToSkip);
        return this;
    }

    @NotNull
    private BufferOverflowException writeBufferOverflowException(final long offset) {
        BufferOverflowException exception = new BufferOverflowException();
        exception.initCause(new IllegalArgumentException("Offset out of bound " + offset));
        return exception;
    }

    @Override
    public @NotNull MappedBytesStore bytesStore() {
//        throwExceptionIfClosed();
        return (MappedBytesStore) super.bytesStore();
    }

    @Override
    public long start() {
//        throwExceptionIfClosed();

        return 0L;
    }

    @NotNull
    @Override
    public Bytes<Void> writePosition(final long position)
            throws BufferOverflowException {
//        throwExceptionIfClosed();

        if (position > writeLimit)
            throw new BufferOverflowException();
        if (position < 0L)
            throw new BufferOverflowException();
        if (position < readPosition)
            this.readPosition = position;
        uncheckedWritePosition(position);
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> clear()
            throws IllegalStateException {
        // typically only used at the start of an operation so reject if closed.
        throwExceptionIfClosed();

        long start = 0L;
        readPosition = start;
        uncheckedWritePosition(start);
        writeLimit = mappedFile.capacity();
        return this;
    }

    @Override
    protected void performRelease() {
        super.performRelease();
        mappedFile.release(this);
    }

    @Override
    @NotNull
    public Bytes<Void> write(@NotNull final RandomDataInput bytes,
                             final long offset,
                             final long length)
            throws BufferUnderflowException, BufferOverflowException, IllegalStateException {
        throwExceptionIfClosed();

        if (bytes instanceof BytesStore)
            write((BytesStore) bytes, offset, length);
        else if (length == 8)
            writeLong(bytes.readLong(offset));
        else if (length > 0)
            BytesInternal.writeFully(bytes, offset, length, this);
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> append8bit(@NotNull CharSequence cs, int start, int end)
            throws IllegalArgumentException, BufferOverflowException, BufferUnderflowException,
            IndexOutOfBoundsException, IllegalStateException {
        throwExceptionIfClosed();

        // check the start.
        long pos = writePosition();
        writeCheckOffset(pos, 0);
        if (!(cs instanceof String) || pos + (end - start) * 3 + 5 >= safeLimit()) {
            return super.append8bit(cs, start, end);
        }
        return append8bit0((String) cs, start, end - start);
    }

    @Override
    @NotNull
    public CommonMappedBytes write8bit(@NotNull CharSequence s, int start, int length)
            throws IllegalStateException, BufferUnderflowException, BufferOverflowException, ArithmeticException, IndexOutOfBoundsException {
        throwExceptionIfClosed();

        ObjectUtils.requireNonNull(s);

        // check the start.
        long pos = writePosition();
        writeCheckOffset(pos, 0);
        if (!(s instanceof String) || pos + length * 3L + 5 >= safeLimit()) {
            super.write8bit(s, start, length);
            return this;
        }

        writeStopBit(length);
        return append8bit0((String) s, start, length);
    }

    @NotNull
    private CommonMappedBytes append8bit0(@NotNull String s, int start, int length)
            throws BufferOverflowException, IllegalStateException {

        if (Jvm.isJava9Plus()) {
            byte[] bytes = extractBytes(s);
            long address = addressForWritePosition();
            Memory memory = bytesStore().memory;
            int i = 0;
            for (; i < length - 3; i += 4) {
                int c0 = bytes[i + start] & 0xff;
                int c1 = bytes[i + start + 1] & 0xff;
                int c2 = bytes[i + start + 2] & 0xff;
                int c3 = bytes[i + start + 3] & 0xff;
                memory.writeInt(address, (c3 << 24) | (c2 << 16) | (c1 << 8) | c0);
                address += 4;
            }
            for (; i < length; i++) {
                byte c = bytes[i + start];
                memory.writeByte(address++, c);
            }
            writeSkip(length);
        } else {
            char[] chars = extractChars(s);
            long address = addressForWritePosition();
            Memory memory = bytesStore().memory;
            int i = 0;
            for (; i < length - 3; i += 4) {
                int c0 = chars[i + start] & 0xff;
                int c1 = chars[i + start + 1] & 0xff;
                int c2 = chars[i + start + 2] & 0xff;
                int c3 = chars[i + start + 3] & 0xff;
                memory.writeInt(address, (c3 << 24) | (c2 << 16) | (c1 << 8) | c0);
                address += 4;
            }
            for (; i < length; i++) {
                char c = chars[i + start];
                memory.writeByte(address++, (byte) c);
            }
            writeSkip(length);
        }
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> appendUtf8(@NotNull CharSequence cs, int start, int length)
            throws BufferOverflowException, IllegalStateException, BufferUnderflowException, IndexOutOfBoundsException {
        throwExceptionIfClosed();

        // check the start.
        long pos = writePosition();
        writeCheckOffset(pos, 0);
        if (!(cs instanceof String) || pos + length * 3L + 5 >= safeLimit()) {
            super.appendUtf8(cs, start, length);
            return this;
        }

        if (Jvm.isJava9Plus()) {
            // byte[] bytes = extractBytes((String) cs);
            final String str = (String) cs;
            long address = addressForWrite(pos);
            Memory memory = OS.memory();
            int i = 0;
            non_ascii:
            {
                for (; i < length; i++) {
                    char c = str.charAt(i + start);
                    //byte c = bytes[i + start];
                    if (c > 127) {
                        writeSkip(i);
                        break non_ascii;
                    }
                    memory.writeByte(address++, (byte) c);
                }
                writeSkip(length);
                return this;
            }
            for (; i < length; i++) {
                char c = str.charAt(i + start);
                appendUtf8(c);
            }
        } else {
            char[] chars = extractChars((String) cs);
            long address = addressForWrite(pos);
            Memory memory = OS.memory();
            int i = 0;
            non_ascii:
            {
                for (; i < length; i++) {
                    char c = chars[i + start];
                    if (c > 127) {
                        writeSkip(i);
                        break non_ascii;
                    }
                    memory.writeByte(address++, (byte) c);
                }
                writeSkip(length);
                return this;
            }
            for (; i < length; i++) {
                char c = chars[i + start];
                appendUtf8(c);
            }
        }
        return this;
    }

    @Override
    public void release(ReferenceOwner id)
            throws IllegalStateException {
        super.release(id);
        if (refCount() <= 0)
            closeable.close();
    }

    @Override
    public void releaseLast(ReferenceOwner id)
            throws IllegalStateException {
        super.releaseLast(id);
        closeable.close();
    }

    @Override
    public void close() {
        closeable.close();
    }

    void performClose() {
        try {
            if (refCount() > 0)
                release(INIT);
        } catch (IllegalStateException e) {
            Jvm.warn().on(getClass(), e);
        }
    }

    @Override
    public boolean isClosed() {
        return closeable != null && closeable.isClosed();
    }

    @Override
    public void warnAndCloseIfNotClosed() {
        closeable.warnAndCloseIfNotClosed();
    }

    @Override
    public void throwExceptionIfClosed() throws IllegalStateException {
        closeable.throwExceptionIfClosed();
    }

    @NotNull
    @Override
    public Bytes<Void> writeUtf8(CharSequence str)
            throws BufferOverflowException, IllegalStateException {
        throwExceptionIfClosed();

        if (str instanceof String) {
            writeUtf8((String) str);
            return this;
        }
        if (str == null) {
            BytesInternal.writeStopBitNeg1(this);

        } else {
            long utfLength = AppendableUtil.findUtf8Length(str);
            this.writeStopBit(utfLength);
            BytesInternal.appendUtf8(this, str, 0, str.length());
        }
        return this;
    }

    @Override
    public @NotNull Bytes<Void> writeUtf8(String str)
            throws BufferOverflowException, IllegalStateException {
        throwExceptionIfClosed();

        if (str == null) {
            BytesInternal.writeStopBitNeg1(this);
            return this;
        }

        try {
            if (Jvm.isJava9Plus()) {
                byte[] strBytes = extractBytes(str);
                byte coder = getStringCoder(str);
                long utfLength = AppendableUtil.findUtf8Length(strBytes, coder);
                writeStopBit(utfLength);
                appendUtf8(strBytes, 0, str.length(), coder);
            } else {
                char[] chars = extractChars(str);
                long utfLength = AppendableUtil.findUtf8Length(chars);
                writeStopBit(utfLength);
                appendUtf8(chars, 0, chars.length);
            }
            return this;
        } catch (IllegalArgumentException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public long readStopBit()
            throws IORuntimeException, IllegalStateException, BufferUnderflowException {
        throwExceptionIfClosed();

        long offset = readOffsetPositionMoved(1);
        byte l = bytesStore.readByte(offset);

        if (l >= 0)
            return l;
        return BytesInternal.readStopBit0(this, l);
    }

    @Override
    public char readStopBitChar()
            throws IORuntimeException, IllegalStateException, BufferUnderflowException {
        throwExceptionIfClosed();

        long offset = readOffsetPositionMoved(1);
        byte l = bytesStore.readByte(offset);

        if (l >= 0)
            return (char) l;
        return (char) BytesInternal.readStopBit0(this, l);
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(long n)
            throws BufferOverflowException, IllegalStateException {
        throwExceptionIfClosed();

        if ((n & ~0x7F) == 0) {
            writeByte((byte) (n & 0x7f));
            return this;
        }
        if ((~n & ~0x7F) == 0) {
            writeByte((byte) (0x80L | ~n));
            writeByte((byte) 0);
            return this;
        }

        if ((n & ~0x3FFF) == 0) {
            writeByte((byte) ((n & 0x7f) | 0x80));
            writeByte((byte) (n >> 7));
            return this;
        }
        BytesInternal.writeStopBit0(this, n);
        return this;
    }

    @NotNull
    @Override
    public Bytes<Void> writeStopBit(char n)
            throws BufferOverflowException, IllegalStateException {
        throwExceptionIfClosed();

        if ((n & ~0x7F) == 0) {
            writeByte((byte) (n & 0x7f));
            return this;
        }

        if ((n & ~0x3FFF) == 0) {
            writeByte((byte) ((n & 0x7f) | 0x80));
            writeByte((byte) (n >> 7));
            return this;
        }
        BytesInternal.writeStopBit0(this, n);
        return this;
    }

    @Override
    public boolean isBackingFileReadOnly() {
        return backingFileIsReadOnly;
    }

    @Override
    public boolean isDirectMemory() {
        return true;
    }

    public CommonMappedBytes disableThreadSafetyCheck(boolean disableThreadSafetyCheck) {
        closeable.disableThreadSafetyCheck(disableThreadSafetyCheck);
        return this;
    }

    @NotNull
    @Override
    public String toString() {
        if (!TRACE)
            return super.toString();
        return getClass().getSimpleName() + "{" + "\n" +
                "refCount=" + refCount() + ",\n" +
                "mappedFile=" + mappedFile.file().getAbsolutePath() + ",\n" +
                "mappedFileRefCount=" + mappedFile.refCount() + ",\n" +
                "mappedFileIsClosed=" + mappedFile.isClosed() + ",\n" +
                "mappedFileRafIsClosed=" + Jvm.getValue(mappedFile.raf(), "closed") + ",\n" +
                "mappedFileRafChannelIsClosed=" + !mappedFile.raf().getChannel().isOpen() + ",\n" +
                "isClosed=" + isClosed() +
                '}';
    }

    @Override
    public void chunkCount(long[] chunkCount) {
        mappedFile.chunkCount(chunkCount);
    }


}
