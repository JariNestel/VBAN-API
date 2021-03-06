package de.comroid.vban.packet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import de.comroid.util.model.ByteArray;
import de.comroid.vban.Util;
import de.comroid.vban.VBAN;
import de.comroid.vban.VBAN.AudioFormat;
import de.comroid.vban.VBAN.BitsPerSecond;
import de.comroid.vban.VBAN.Codec;
import de.comroid.vban.VBAN.CommandFormat;
import de.comroid.vban.VBAN.Format;
import de.comroid.vban.VBAN.Protocol;
import de.comroid.vban.VBAN.SampleRate;
import de.comroid.vban.exception.InvalidPacketAttributeException;
import de.comroid.vban.model.DataRateValue;
import de.comroid.vban.model.FormatValue;
import de.comroid.vban.model.UnfinishedByteArray;

import org.intellij.lang.annotations.MagicConstant;

import static de.comroid.vban.Util.checkRange;
import static de.comroid.vban.Util.intToByteArray;
import static de.comroid.vban.Util.stringToBytesASCII;
import static de.comroid.vban.Util.trimArray;
import static de.comroid.vban.packet.VBANPacketHead.Factory.builder;

public class VBANPacketHead<T> implements ByteArray {
    public final static int SIZE = 28;

    private final UnfinishedByteArray unfinishedByteArray;

    private VBANPacketHead(byte[] bytes) {
        unfinishedByteArray = new UnfinishedByteArray(SIZE, true);
        unfinishedByteArray.append(bytes);
    }

    public VBANPacketHead(int protocol,
                          int sampleRateIndex,
                          int samples,
                          int channel,
                          int format,
                          int codec,
                          String streamName,
                          int frameCounter) {
        checkRange(samples, 0, 255);
        checkRange(channel, 0, 255);

        unfinishedByteArray = new UnfinishedByteArray(SIZE, true);

        unfinishedByteArray.append("VBAN".getBytes());
        unfinishedByteArray.append((byte) (protocol | sampleRateIndex));
        unfinishedByteArray.append((byte) samples, (byte) channel);
        unfinishedByteArray.append((byte) (format | codec));
        unfinishedByteArray.append(trimArray(stringToBytesASCII(streamName), 16));
        unfinishedByteArray.append(intToByteArray(frameCounter, 4));
    }

    @Override
    public byte[] getBytes() {
        return unfinishedByteArray.getBytes();
    }

    /**
     * Creates a Factory instance with the default properties for the specified Protocol.
     *
     * @param forProtocol The protocol to use standards for.
     * @param <T>         Type-variable for the VBAN-Stream.
     *
     * @return A new Factory instance.
     * @throws UnsupportedOperationException If the Protocol is one of {@code [AUDIO, SERIAL, SERVICE]}.
     */
    public static <T> Factory<T> defaultFactory(Protocol<T> forProtocol) throws UnsupportedOperationException {
        return builder(forProtocol).build();
    }

    public static VBANPacketHead.Decoded decode(byte[] headBytes) throws InvalidPacketAttributeException {
        return new VBANPacketHead.Decoded(headBytes);
    }

    public static class Decoded extends VBANPacketHead {
        private final Protocol<?> protocol;
        private final DataRateValue<?> dataRateValue;
        private final int samples;
        private final int channel;
        private final FormatValue<?> format;
        @MagicConstant(valuesFromClass = VBAN.Codec.class) private final int codec;
        private final String streamName;
        private final int frame;

        private Decoded(byte[] bytes) throws InvalidPacketAttributeException {
            super(bytes);

            if (bytes.length > SIZE)
                throw new IllegalArgumentException("Bytearray is too large, must be exactly " + SIZE + " bytes long!");

            if (bytes[0] != 'V' || bytes[1] != 'B' || bytes[2] != 'A' || bytes[3] != 'N')
                throw new InvalidPacketAttributeException("Invalid packet head: First bytes must be 'VBAN' [rcv='"
                        + new String(Util.subArray(bytes, 0, 4), StandardCharsets.US_ASCII) + "']");

            int protocolInt = bytes[4] & 0b11100000;
            protocol = VBAN.Protocol.byValue(protocolInt);

            // throw exception if protocol is SERVICE
            if (protocol.getValue() == 0x60)
                throw new IllegalStateException("Service Subprotocol is not supported!");

            int dataRateInt = bytes[4] & 0b00011111;
            switch (protocol.getValue()) {
                case 0x00: // AUDIO
                    dataRateValue = SampleRate.byValue(dataRateInt);
                    break;
                case 0x20: // SERIAL
                case 0x40: // TEXT
                    dataRateValue = BitsPerSecond.byValue(dataRateInt);
                    break;
                case 0x60: // SERVICE
                default:
                    // to avoid compiler warning, set to null.
                    // service protocol is not supported
                    dataRateValue = null;
                    break;
            }

            // +1 to avoid indexed counting
            samples = bytes[5] + 1;
            channel = bytes[6] + 1;

            int formatInt = bytes[7] & 0b00011111;
            switch (protocol.getValue()) {
                case 0x00: // AUDIO
                    format = AudioFormat.byValue(formatInt);
                    break;
                case 0x20: // SERIAL
                    format = Format.byValue(formatInt);
                    break;
                case 0x40: // TEXT
                    format = CommandFormat.byValue(formatInt);
                    break;
                case 0x60: // SERVICE
                default:
                    // to avoid compiler warning, set to null.
                    // service protocol is not supported
                    format = null;
                    break;
            }

            // reserved bit
            int reservedBit = bytes[7] & 0b11101111;

            int codecInt = bytes[7] & 0b11110000;
            switch (codecInt) {
                case Codec.PCM:
                case Codec.VBCA:
                case Codec.VBCV:
                case Codec.USER:
                    //noinspection MagicConstant
                    codec = codecInt;
                    break;
                default:
                    throw new InvalidPacketAttributeException("Invalid Codec selector: " + Integer.toHexString(codecInt));
            }

            byte[] nameBytes = new byte[16];
            System.arraycopy(bytes, 8, nameBytes, 0, 16);
            streamName = Util.bytesToString(nameBytes, StandardCharsets.US_ASCII);

            byte[] frameBytes = new byte[4];
            System.arraycopy(bytes, 24, frameBytes, 0, 4);
            frame = ByteBuffer.wrap(frameBytes).asIntBuffer().get();
        }

        public Protocol<?> getProtocol() {
            return protocol;
        }

        public DataRateValue<?> getDataRateValue() {
            return dataRateValue;
        }

        public int getSamples() {
            return samples;
        }

        public int getChannel() {
            return channel;
        }

        public FormatValue<?> getFormat() {
            return format;
        }

        @MagicConstant(valuesFromClass = VBAN.Codec.class)
        public int getCodec() {
            return codec;
        }

        public String getStreamName() {
            return streamName;
        }
    }

    public static class Factory<T> implements de.comroid.util.model.Factory<VBANPacketHead<T>> {
        private final int protocol;
        private final int sampleRate;
        private final int samples;
        private final int channel;
        private final int format;
        private final int codec;
        private final String streamName;
        private int counter;

        private Factory(Protocol<T> protocol,
                        DataRateValue<? super T> sampleRate,
                        int samples,
                        int channel,
                        FormatValue<? super T> format,
                        int codec,
                        String streamName) {
            this.protocol = protocol.getValue();
            this.sampleRate = sampleRate.getValue();
            this.samples = samples;
            this.channel = channel;
            this.format = format.getValue();
            this.codec = codec;
            this.streamName = streamName;

            counter = 0;
        }

        @Override
        public synchronized VBANPacketHead<T> create() {
            return new VBANPacketHead<>(protocol, sampleRate, samples, channel, format, codec, streamName, counter++);
        }

        @Override
        public synchronized int counter() {
            return counter;
        }

        /**
         * Creates a new Builder with the default properties pre-set for the specified protocol.
         *
         * @param protocol The protocol to create the Builder for.
         * @param <T>      Type-Variable for the stream type.
         *
         * @return A new builder for the given protocol.
         * @throws UnsupportedOperationException If the protocol is {@link Protocol#SERVICE}.
         */
        public static <T> Builder<T> builder(Protocol<T> protocol) throws UnsupportedOperationException {
            return new Builder<>(protocol);
        }

        public static class Builder<T> implements de.comroid.util.model.Builder<Factory<T>> {
            private final Protocol<T> protocol;
            private DataRateValue<? super T> sampleRate;
            private int samples;
            private int channel;
            private FormatValue<? super T> format;
            @MagicConstant(valuesFromClass = Codec.class)
            private int codec = Codec.PCM;
            private String streamName = null;

            /*
            Suppress ConstantConditions because, while the MIDI branch breaks away due to Serial communication not
            being implemented yet, the IF in the Text communication branch will always be 'false'
             */
            @SuppressWarnings({"unchecked", "ConstantConditions"})
            private Builder(Protocol<T> protocol) throws UnsupportedOperationException {
                this.protocol = protocol;

                switch (protocol.getValue()) {
                    case 0x00:
                        sampleRate = (DataRateValue<T>) SampleRate.Hz48000;
                        samples = 255;
                        channel = 2;
                        format = (FormatValue<T>) AudioFormat.INT16;
                        streamName = "Stream1";
                        return;
                    case 0x20:
                        sampleRate = (DataRateValue<T>) BitsPerSecond.Bps256000;
                        samples = 0;
                        channel = 0;
                        format = (FormatValue<T>) Format.BYTE8;
                        streamName = "MIDI1";
                        return;
                    case 0x40:
                        sampleRate = (DataRateValue<T>) BitsPerSecond.Bps256000;
                        samples = 0;
                        channel = 0;
                        format = (FormatValue<T>) CommandFormat.ASCII;
                        // if because we are in a shared branch
                        if (streamName == null) streamName = "Command1";
                        return;
                    case 0x60:
                        break;
                    default:
                        throw new AssertionError("Unknown Protocol: " + protocol);
                }

                throw new UnsupportedOperationException("Unsupported Protocol: " + protocol);
            }

            public Protocol<T> getProtocol() {
                return protocol;
            }

            public DataRateValue<? super T> getSampleRate() {
                return sampleRate;
            }

            public Builder<T> setSRValue(DataRateValue<? super T> sampleRate) {
                this.sampleRate = sampleRate;
                return this;
            }

            public int getSamples() {
                return samples;
            }

            public Builder<T> setSamples(byte samples) {
                this.samples = samples - 1;
                return this;
            }

            public int getChannel() {
                return channel;
            }

            public Builder<T> setChannel(byte channel) {
                this.channel = channel - 1;
                return this;
            }

            public FormatValue<? super T> getFormat() {
                return format;
            }

            public Builder<T> setFormatValue(FormatValue<? super T> format) {
                this.format = format;
                return this;
            }

            public int getCodec() {
                return codec;
            }

            public Builder<T> setCodec(int codec) {
                this.codec = codec;
                return this;
            }

            public String getStreamName() {
                return streamName;
            }

            public Builder<T> setStreamName(String streamName) {
                this.streamName = streamName;
                return this;
            }

            @Override
            public Factory<T> build() {
                assert protocol != null : "No protocol defined!";

                return new Factory<>(protocol, sampleRate, samples, channel, format, codec, streamName);
            }
        }
    }
}
