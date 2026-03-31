/*
 * Copyright 2013 - 2022 Anton Tananaev (anton@traccar.org)
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
package com.tracktr.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import com.tracktr.BaseProtocolDecoder;
import com.tracktr.model.Device;
import com.tracktr.session.DeviceSession;
import com.tracktr.NetworkMessage;
import com.tracktr.Protocol;
import com.tracktr.config.Keys;
import com.tracktr.helper.BitUtil;
import com.tracktr.helper.Checksum;
import com.tracktr.helper.UnitsConverter;
import com.tracktr.model.CellTower;
import com.tracktr.model.Network;
import com.tracktr.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class TeltonikaProtocolDecoder extends BaseProtocolDecoder {

    private static final int IMAGE_PACKET_MAX = 2048;

    private static final Map<Integer, Map<Set<String>, BiConsumer<Position, ByteBuf>>> PARAMETERS = new HashMap<>();

    private final boolean connectionless;
    private boolean extended;
    private final Map<Long, ByteBuf> photos = new HashMap<>();

    public void setExtended(boolean extended) {
        this.extended = extended;
    }

    public TeltonikaProtocolDecoder(Protocol protocol, boolean connectionless) {
        super(protocol);
        this.connectionless = connectionless;
    }

    @Override
    protected void init() {
        this.extended = getConfig().getBoolean(Keys.PROTOCOL_EXTENDED.withPrefix(getProtocolName()));
    }

    private void parseIdentification(Channel channel, SocketAddress remoteAddress, ByteBuf buf) {

        int length = buf.readUnsignedShort();
        String imei = buf.toString(buf.readerIndex(), length, StandardCharsets.US_ASCII);
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);

        if (channel != null) {
            ByteBuf response = Unpooled.buffer(1);
            if (deviceSession != null) {
                response.writeByte(1);
            } else {
                response.writeByte(0);
            }
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    public static final int CODEC_GH3000 = 0x07;
    public static final int CODEC_8 = 0x08;
    public static final int CODEC_8_EXT = 0x8E;
    public static final int CODEC_12 = 0x0C;
    public static final int CODEC_13 = 0x0D;
    public static final int CODEC_16 = 0x10;

    private void sendImageRequest(Channel channel, SocketAddress remoteAddress, long id, int offset, int size) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            response.writeInt(0);
            response.writeShort(0);
            response.writeShort(19); // length
            response.writeByte(CODEC_12);
            response.writeByte(1); // nod
            response.writeByte(0x0D); // camera
            response.writeInt(11); // payload length
            response.writeByte(2); // command
            response.writeInt((int) id);
            response.writeInt(offset);
            response.writeShort(size);
            response.writeByte(1); // nod
            response.writeShort(0);
            response.writeShort(Checksum.crc16(
                    Checksum.CRC16_IBM, response.nioBuffer(8, response.readableBytes() - 10)));
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    private boolean isPrintable(ByteBuf buf, int length) {
        boolean printable = true;
        for (int i = 0; i < length; i++) {
            byte b = buf.getByte(buf.readerIndex() + i);
            if (b < 32 && b != '\r' && b != '\n') {
                printable = false;
                break;
            }
        }
        return printable;
    }

    private void decodeSerial(
            Channel channel, SocketAddress remoteAddress, DeviceSession deviceSession, Position position, ByteBuf buf) {

        getLastLocation(position, null);

        int type = buf.readUnsignedByte();
        if (type == 0x0D) {

            buf.readInt(); // length
            int subtype = buf.readUnsignedByte();
            if (subtype == 0x01) {

                long photoId = buf.readUnsignedInt();
                ByteBuf photo = Unpooled.buffer(buf.readInt());
                photos.put(photoId, photo);
                sendImageRequest(
                        channel, remoteAddress, photoId,
                        0, Math.min(IMAGE_PACKET_MAX, photo.capacity()));

            } else if (subtype == 0x02) {

                long photoId = buf.readUnsignedInt();
                buf.readInt(); // offset
                ByteBuf photo = photos.get(photoId);
                photo.writeBytes(buf, buf.readUnsignedShort());
                if (photo.writableBytes() > 0) {
                    sendImageRequest(
                            channel, remoteAddress, photoId,
                            photo.writerIndex(), Math.min(IMAGE_PACKET_MAX, photo.writableBytes()));
                } else {
                    photos.remove(photoId);
                    try {
                        position.set(Position.KEY_IMAGE, writeMediaFile(deviceSession.getUniqueId(), photo, "jpg"));
                    } finally {
                        photo.release();
                    }
                }

            }

        } else {

            position.set(Position.KEY_TYPE, type);

            int length = buf.readInt();
            if (isPrintable(buf, length)) {
                String data = buf.readSlice(length).toString(StandardCharsets.US_ASCII).trim();
                if (data.startsWith("UUUUww") && data.endsWith("SSS")) {
                    String[] values = data.substring(6, data.length() - 4).split(";");
                    for (int i = 0; i < 8; i++) {
                        position.set("axle" + (i + 1), Double.parseDouble(values[i]));
                    }
                    position.set("loadTruck", Double.parseDouble(values[8]));
                    position.set("loadTrailer", Double.parseDouble(values[9]));
                    position.set("totalTruck", Double.parseDouble(values[10]));
                    position.set("totalTrailer", Double.parseDouble(values[11]));
                } else {
                    position.set(Position.KEY_RESULT, data);
                }
            } else {
                position.set(Position.KEY_RESULT, ByteBufUtil.hexDump(buf.readSlice(length)));
            }
        }
    }

    private long readValue(ByteBuf buf, int length) {
        switch (length) {
            case 1:
                return buf.readUnsignedByte();
            case 2:
                return buf.readUnsignedShort();
            case 4:
                return buf.readUnsignedInt();
            default:
                return buf.readLong();
        }
    }

    private static void register(int id, Set<String> models, BiConsumer<Position, ByteBuf> handler) {
        PARAMETERS.computeIfAbsent(id, key -> new HashMap<>()).put(models, handler);
    }

    static {
        var fmbXXX = Set.of(
                "FMB001", "FMB010", "FMB002", "FMB020", "FMB003", "FMB110", "FMB120", "FMB122", "FMB125", "FMB130",
                "FMB140", "FMU125", "FMB900", "FMB920", "FMB962", "FMB964", "FM3001", "FMB202", "FMB204", "FMB206",
                "FMT100", "MTB100", "FMP100", "MSP500");

        register(1, null, (p, b) -> p.set(Position.PREFIX_IN + 1, b.readUnsignedByte() > 0));
        register(2, null, (p, b) -> p.set(Position.PREFIX_IN + 2, b.readUnsignedByte() > 0));
        register(3, null, (p, b) -> p.set(Position.PREFIX_IN + 3, b.readUnsignedByte() > 0));
        register(4, null, (p, b) -> p.set(Position.PREFIX_IN + 4, b.readUnsignedByte() > 0));
        register(9, fmbXXX, (p, b) -> p.set(Position.PREFIX_ADC + 1, b.readUnsignedShort() * 0.001));
        register(10, fmbXXX, (p, b) -> p.set(Position.PREFIX_ADC + 2, b.readUnsignedShort() * 0.001));
        register(11, fmbXXX, (p, b) -> p.set(Position.KEY_ICCID, String.valueOf(b.readLong())));
        register(12, null, (p, b) -> p.set("fuelUsedGPS", b.readUnsignedInt() * 0.001));
        register(13, null, (p, b) -> p.set("fuelRateGPS", b.readUnsignedShort() * 0.01));
        register(15, null, (p, b) -> p.set("ecoScore", b.readUnsignedShort() * 0.01));
        register(16, null, (p, b) -> p.set(Position.KEY_ODOMETER, b.readUnsignedInt()));
        register(17, null, (p, b) -> p.set("axisX", b.readShort()));
        register(18, null, (p, b) -> p.set("axisY", b.readShort()));
        register(19, null, (p, b) -> p.set("axisZ", b.readShort()));
        register(21, null, (p, b) -> p.set(Position.KEY_RSSI, b.readUnsignedByte()));
        register(24, null, (p, b) -> p.set("gnssSpeed", UnitsConverter.knotsFromKph(b.readUnsignedShort())));
        register(25, null, (p, b) -> p.set("bleTemp1", b.readShort() * 0.01));
        register(26, null, (p, b) -> p.set("bleTemp2", b.readShort() * 0.01));
        register(27, null, (p, b) -> p.set("bleTemp3", b.readShort() * 0.01));
        register(28, null, (p, b) -> p.set("bleTemp4", b.readShort() * 0.01));
        register(61, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_06");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_06");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_06");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_06");
                    break;
                default:
                    break;
            }
        });
        register(62, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_07");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_07");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_07");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_07");
                    break;
                default:
                    break;
            }
        });
        register(63, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_08");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_08");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_08");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_08");
                    break;
                default:
                    break;
            }
        });
        register(64, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_09");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_09");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_09");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_09");
                    break;
                default:
                    break;
            }
        });
        register(65, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_10");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_10");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_10");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_10");
                    break;
                default:
                    break;
            }
        });
        register(66, null, (p, b) -> p.set(Position.KEY_POWER, b.readUnsignedShort() * 0.001));
        register(67, null, (p, b) -> p.set(Position.KEY_BATTERY, b.readUnsignedShort() * 0.001));
        register(68, null, (p, b) -> p.set("batteryCurrent", b.readUnsignedShort() * 0.001));
        register(69, null, (p, b) -> p.set("gnssStatus", b.readUnsignedByte()));
        register(70, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_11");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_11");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_11");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_11");
                    break;
                default:
                    break;
            }
        });
        register(71, fmbXXX, (p, b) -> p.set("tempId" + 4, String.valueOf(b.readLong())));
        register(72, fmbXXX, (p, b) -> p.set(Position.PREFIX_TEMP + 1, b.readShort() * 0.1));
        register(73, fmbXXX, (p, b) -> p.set(Position.PREFIX_TEMP + 2, b.readShort() * 0.1));
        register(74, fmbXXX, (p, b) -> p.set(Position.PREFIX_TEMP + 3, b.readShort() * 0.1));
        register(75, fmbXXX, (p, b) -> p.set(Position.PREFIX_TEMP + 4, b.readShort() * 0.1));
        register(76, fmbXXX, (p, b) -> p.set("tempId" + 1, String.valueOf(b.readLong())));
        register(77, fmbXXX, (p, b) -> p.set("tempId" + 2, String.valueOf(b.readLong())));
        register(78, null, (p, b) -> {
            long driverUniqueId = b.readLong();
            if (driverUniqueId > 0) {
                p.set(Position.KEY_DRIVER_UNIQUE_ID, String.format("%016X", driverUniqueId));
            }
        });
        register(79, fmbXXX, (p, b) -> p.set("tempId" + 3, String.valueOf(b.readLong())));
        register(80, null, (p, b) -> p.set("dataMode", b.readUnsignedByte()));
        register(88, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_12");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_12");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_12");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_12");
                    break;
                default:
                    break;
            }
        });
        register(90, null, (p, b) -> p.set(Position.KEY_DOOR, b.readUnsignedShort()));
        register(91, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_13");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_13");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_13");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_13");
                    break;
                default:
                    break;
            }
        });
        register(92, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_14");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_14");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_14");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_14");
                    break;
                default:
                    break;
            }
        });
        register(93, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_15");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_15");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_15");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_15");
                    break;
                default:
                    break;
            }
        });
        register(94, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_16");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_16");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_16");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_16");
                    break;
                default:
                    break;
            }
        });
        register(95, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_17");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_17");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_17");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_17");
                    break;
                default:
                    break;
            }
        });
        register(96, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_18");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_18");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_18");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_18");
                    break;
                default:
                    break;
            }
        });
        register(97, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_19");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_19");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_19");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_19");
                    break;
                default:
                    break;
            }
        });
        register(98, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_20");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_20");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_20");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_20");
                    break;
                default:
                    break;
            }
        });
        register(99, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_21");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_21");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_21");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_21");
                    break;
                default:
                    break;
            }
        });
        register(113, fmbXXX, (p, b) -> p.set(Position.KEY_BATTERY_LEVEL, b.readUnsignedByte()));
        register(115, fmbXXX, (p, b) -> p.set(Position.KEY_COOLANT_TEMP, b.readShort() * 0.1));
        register(153, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_22");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_22");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_22");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_22");
                    break;
                default:
                    break;
            }
        });
        register(154, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_23");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_23");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_23");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_23");
                    break;
                default:
                    break;
            }
        });
        register(155, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_01");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_01");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_01");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_01");
                    break;
                default:
                    break;
            }
        });
        register(156, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_02");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_02");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_02");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_02");
                    break;
                default:
                    break;
            }
        });
        register(157, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_03");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_03");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_03");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_03");
                    break;
                default:
                    break;
            }
        });
        register(158, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_04");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_04");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_04");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_04");
                    break;
                default:
                    break;
            }
        });
        register(159, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_05");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_05");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_05");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_05");
                    break;
                default:
                    break;
            }
        });
        register(175, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone");
                    break;
                default:
                    break;
            }
        });
        register(179, null, (p, b) -> p.set(Position.PREFIX_OUT + 1, b.readUnsignedByte() > 0));
        register(180, null, (p, b) -> p.set(Position.PREFIX_OUT + 2, b.readUnsignedByte() > 0));
        register(181, null, (p, b) -> p.set(Position.KEY_PDOP, b.readUnsignedShort() * 0.1));
        register(182, null, (p, b) -> p.set(Position.KEY_HDOP, b.readUnsignedShort() * 0.1));
        register(190, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_24");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_24");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_24");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_24");
                    break;
                default:
                    break;
            }
        });
        register(191, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_25");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_25");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_25");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_25");
                    break;
                default:
                    break;
            }
        });
        register(192, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_26");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_26");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_26");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_26");
                    break;
                default:
                    break;
            }
        });
        register(193, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_27");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_27");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_27");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_27");
                    break;
                default:
                    break;
            }
        });
        register(194, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_28");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_28");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_28");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_28");
                    break;
                default:
                    break;
            }
        });
        register(195, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_29");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_29");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_29");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_29");
                    break;
                default:
                    break;
            }
        });
        register(196, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_30");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_30");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_30");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_30");
                    break;
                default:
                    break;
            }
        });
        register(196, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_30");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_30");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_30");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_30");
                    break;
                default:
                    break;
            }
        });
        register(197, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_31");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_31");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_31");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_31");
                    break;
                default:
                    break;
            }
        });
        register(198, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_32");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_32");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_32");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_32");
                    break;
                default:
                    break;
            }
        });
        register(199, null, (p, b) -> p.set(Position.KEY_ODOMETER_TRIP, b.readUnsignedInt()));
        register(200, null, (p, b) -> p.set("sleepMode", b.readUnsignedByte()));
        register(205, null, (p, b) -> p.set("cid", b.readUnsignedShort()));
        register(206, null, (p, b) -> p.set("lac", b.readUnsignedShort()));
        register(208, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_33");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_33");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_33");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_33");
                    break;
                default:
                    break;
            }
        });
        register(209, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_34");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_34");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_34");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_34");
                    break;
                default:
                    break;
            }
        });
        register(216, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_35");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_35");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_35");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_35");
                    break;
                default:
                    break;
            }
        });
        register(217, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_36");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_36");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_36");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_36");
                    break;
                default:
                    break;
            }
        });
        register(218, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_37");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_37");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_37");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_37");
                    break;
                default:
                    break;
            }
        });
        register(219, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_38");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_38");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_38");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_38");
                    break;
                default:
                    break;
            }
        });
        register(220, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_39");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_39");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_39");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_39");
                    break;
                default:
                    break;
            }
        });
        register(221, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_40");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_40");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_40");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_40");
                    break;
                default:
                    break;
            }
        });
        register(222, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_41");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_41");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_41");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_41");
                    break;
                default:
                    break;
            }
        });
        register(223, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_42");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_42");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_42");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_42");
                    break;
                default:
                    break;
            }
        });
        register(224, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_43");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_43");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_43");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_43");
                    break;
                default:
                    break;
            }
        });
        register(225, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_44");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_44");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_44");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_44");
                    break;
                default:
                    break;
            }
        });
        register(226, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_45");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_45");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_45");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_45");
                    break;
                default:
                    break;
            }
        });
        register(227, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_46");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_46");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_46");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_46");
                    break;
                default:
                    break;
            }
        });
        register(228, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_47");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_47");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_47");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_47");
                    break;
                default:
                    break;
            }
        });
        register(229, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_48");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_48");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_48");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_48");
                    break;
                default:
                    break;
            }
        });
        register(230, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_49");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_49");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_49");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_49");
                    break;
                default:
                    break;
            }
        });
        register(231, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_GEOFENCE, "targetLeftAutoZone_50");
                    break;
                case 1:
                    p.set(Position.KEY_GEOFENCE, "targetEnteredAutoZone_50");
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, "overSpeedingEnd_50");
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, "overSpeedingStart_50");
                    break;
                default:
                    break;
            }
        });
        register(236, null, (p, b) -> {
            p.set(Position.KEY_ALARM, b.readUnsignedByte() > 0 ? Position.ALARM_GENERAL : null);
        });
        register(238, null, (p, b) -> {
            long userID  = b.readLong();
            if (userID  > 0) {
                p.set("userID", String.format("%016X", userID));
            }
        });
        register(239, null, (p, b) -> p.set(Position.KEY_IGNITION, b.readUnsignedByte() > 0));
        register(240, null, (p, b) -> p.set(Position.KEY_MOTION, b.readUnsignedByte() > 0));
        register(241, null, (p, b) -> p.set(Position.KEY_OPERATOR, b.readUnsignedInt()));
        register(243, null, (p, b) -> p.set("greenDrivingDuration", b.readUnsignedInt()));
        register(246, null, (p, b) -> {
            p.set(Position.KEY_ALARM, b.readUnsignedByte() > 0 ? "towing" : null);
        });
        register(247, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 1:
                    p.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT);
                    break;
                case 2:
                    p.set("crashTrace", "limitedNotCalibrated");
                    break;
                case 3:
                    p.set("crashTrace", "limitedCalibrated");
                    break;
                case 4:
                    p.set("crashTrace", "fullNotCalibrated");
                    break;
                case 5:
                    p.set("crashTrace", "fullCalibrated");
                    break;
                case 6:
                    p.set(Position.KEY_ALARM, Position.ALARM_ACCIDENT + "_uc");
                    break;
                case 7:
                    p.set(Position.KEY_ALARM, "fakeCrashPothole");
                    break;
                case 8:
                    p.set(Position.KEY_ALARM, "fakeCrashSpeedCheck");
                    break;
                default:
                    break;
            }
        });
        register(248, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set("immobilizer", "notConnected");
                    break;
                case 1:
                    p.set("immobilizer", "connected");
                    break;
                case 2:
                    p.set("immobilizer", "authorized");
                    break;
                default:
                    break;
            }
        });
        register(249, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set(Position.KEY_ALARM, "jammingStart");
                    break;
                case 1:
                    p.set(Position.KEY_ALARM, "jammingStop");
                    break;
                default:
                    break;
            }
        });
        register(250, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 0:
                    p.set("trip", "tripStop");
                    break;
                case 1:
                    p.set("trip", "tripStart");
                    break;
                case 2:
                    p.set("trip", "tripBusiness");
                    break;
                case 3:
                    p.set("trip", "tripPrivate");
                    break;
                default:
                    p.set("trip", "tripCustom");
                    break;
            }
        });
        register(251, null, (p, b) -> {
            p.set(Position.KEY_ALARM, b.readUnsignedByte() > 0 ? "idling" : null);
        });
        register(252, null, (p, b) -> {
            p.set(Position.KEY_ALARM, b.readUnsignedByte() > 0 ? "unplugged" : null);
        });
        register(253, null, (p, b) -> {
            switch (b.readUnsignedByte()) {
                case 1:
                    p.set(Position.KEY_ALARM, Position.ALARM_ACCELERATION);
                    break;
                case 2:
                    p.set(Position.KEY_ALARM, Position.ALARM_BRAKING);
                    break;
                case 3:
                    p.set(Position.KEY_ALARM, Position.ALARM_CORNERING);
                    break;
                default:
                    break;
            }
        });
        register(254, null, (p, b) -> p.set("greenDrivingValue", b.readUnsignedInt()));
        register(255, null, (p, b) -> {
            p.set(Position.KEY_ALARM, b.readUnsignedInt() > 0 ? Position.ALARM_OVERSPEED : null);
        });
    }

    private void decodeGh3000Parameter(Position position, int id, ByteBuf buf, int length) {
        switch (id) {
            case 1:
                position.set(Position.KEY_BATTERY_LEVEL, readValue(buf, length));
                break;
            case 2:
                position.set("usbConnected", readValue(buf, length) == 1);
                break;
            case 5:
                position.set("uptime", readValue(buf, length));
                break;
            case 20:
                position.set(Position.KEY_HDOP, readValue(buf, length) * 0.1);
                break;
            case 21:
                position.set(Position.KEY_VDOP, readValue(buf, length) * 0.1);
                break;
            case 22:
                position.set(Position.KEY_PDOP, readValue(buf, length) * 0.1);
                break;
            case 67:
                position.set(Position.KEY_BATTERY, readValue(buf, length) * 0.001);
                break;
            case 221:
                position.set("button", readValue(buf, length));
                break;
            case 222:
                if (readValue(buf, length) == 1) {
                    position.set(Position.KEY_ALARM, Position.ALARM_SOS);
                }
                break;
            case 240:
                position.set(Position.KEY_MOTION, readValue(buf, length) == 1);
                break;
            case 244:
                position.set(Position.KEY_ROAMING, readValue(buf, length) == 1);
                break;
            default:
                position.set(Position.PREFIX_IO + id, readValue(buf, length));
                break;
        }
    }

    private void decodeParameter(Position position, int id, ByteBuf buf, int length, int codec, String model) {
        if (codec == CODEC_GH3000) {
            decodeGh3000Parameter(position, id, buf, length);
        } else {
            int index = buf.readerIndex();
            boolean decoded = false;
            for (var entry : PARAMETERS.getOrDefault(id, new HashMap<>()).entrySet()) {
                if (entry.getKey() == null || model != null && entry.getKey().contains(model)) {
                    entry.getValue().accept(position, buf);
                    decoded = true;
                    break;
                }
            }
            if (decoded) {
                buf.readerIndex(index + length);
            } else {
                position.set(Position.PREFIX_IO + id, readValue(buf, length));
            }
        }
    }

    private void decodeCell(
            Position position, Network network, String mncKey, String lacKey, String cidKey, String rssiKey) {
        if (position.hasAttribute(mncKey) && position.hasAttribute(lacKey) && position.hasAttribute(cidKey)) {
            CellTower cellTower = CellTower.from(
                    getConfig().getInteger(Keys.GEOLOCATION_MCC),
                    ((Number) position.getAttributes().remove(mncKey)).intValue(),
                    ((Number) position.getAttributes().remove(lacKey)).intValue(),
                    ((Number) position.getAttributes().remove(cidKey)).longValue());
            cellTower.setSignalStrength(((Number) position.getAttributes().remove(rssiKey)).intValue());
            network.addCellTower(cellTower);
        }
    }

    private void decodeNetwork(Position position, String model) {
        if ("TAT100".equals(model)) {
            Network network = new Network();
            decodeCell(position, network, "io1200", "io287", "io288", "io289");
            decodeCell(position, network, "io1201", "io290", "io291", "io292");
            decodeCell(position, network, "io1202", "io293", "io294", "io295");
            decodeCell(position, network, "io1203", "io296", "io297", "io298");
            if (network.getCellTowers() != null) {
                position.setNetwork(network);
            }
        } else {
            Integer cid = (Integer) position.getAttributes().remove("cid");
            Integer lac = (Integer) position.getAttributes().remove("lac");
            if (cid != null && lac != null) {
                CellTower cellTower = CellTower.fromLacCid(getConfig(), lac, cid);
                long operator = position.getInteger(Position.KEY_OPERATOR);
                if (operator >= 1000) {
                    cellTower.setOperator(operator);
                }
                position.setNetwork(new Network(cellTower));
            }
        }
    }

    private int readExtByte(ByteBuf buf, int codec, int... codecs) {
        boolean ext = false;
        for (int c : codecs) {
            if (codec == c) {
                ext = true;
                break;
            }
        }
        if (ext) {
            return buf.readUnsignedShort();
        } else {
            return buf.readUnsignedByte();
        }
    }

    private void decodeLocation(Position position, ByteBuf buf, int codec, String model) {

        int globalMask = 0x0f;

        if (codec == CODEC_GH3000) {

            long time = buf.readUnsignedInt() & 0x3fffffff;
            time += 1167609600; // 2007-01-01 00:00:00

            globalMask = buf.readUnsignedByte();
            if (BitUtil.check(globalMask, 0)) {

                position.setTime(new Date(time * 1000));

                int locationMask = buf.readUnsignedByte();

                if (BitUtil.check(locationMask, 0)) {
                    position.setLatitude(buf.readFloat());
                    position.setLongitude(buf.readFloat());
                }

                if (BitUtil.check(locationMask, 1)) {
                    position.setAltitude(buf.readUnsignedShort());
                }

                if (BitUtil.check(locationMask, 2)) {
                    position.setCourse(buf.readUnsignedByte() * 360.0 / 256);
                }

                if (BitUtil.check(locationMask, 3)) {
                    position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte()));
                }

                if (BitUtil.check(locationMask, 4)) {
                    position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
                }

                if (BitUtil.check(locationMask, 5)) {
                    CellTower cellTower = CellTower.fromLacCid(
                            getConfig(), buf.readUnsignedShort(), buf.readUnsignedShort());

                    if (BitUtil.check(locationMask, 6)) {
                        cellTower.setSignalStrength((int) buf.readUnsignedByte());
                    }

                    if (BitUtil.check(locationMask, 7)) {
                        cellTower.setOperator(buf.readUnsignedInt());
                    }

                    position.setNetwork(new Network(cellTower));

                } else {
                    if (BitUtil.check(locationMask, 6)) {
                        position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                    }
                    if (BitUtil.check(locationMask, 7)) {
                        position.set(Position.KEY_OPERATOR, buf.readUnsignedInt());
                    }
                }

            } else {

                getLastLocation(position, new Date(time * 1000));

            }

        } else {

            position.setTime(new Date(buf.readLong()));

            position.set("priority", buf.readUnsignedByte());

            position.setLongitude(buf.readInt() / 10000000.0);
            position.setLatitude(buf.readInt() / 10000000.0);
            position.setAltitude(buf.readShort());
            position.setCourse(buf.readUnsignedShort());

            int satellites = buf.readUnsignedByte();
            position.set(Position.KEY_SATELLITES, satellites);

            position.setValid(satellites != 0);

            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedShort()));

            position.set(Position.KEY_EVENT, readExtByte(buf, codec, CODEC_8_EXT, CODEC_16));
            if (codec == CODEC_16) {
                buf.readUnsignedByte(); // generation type
            }

            readExtByte(buf, codec, CODEC_8_EXT); // total IO data records

        }

        // Read 1 byte data
        if (BitUtil.check(globalMask, 1)) {
            int cnt = readExtByte(buf, codec, CODEC_8_EXT);
            for (int j = 0; j < cnt; j++) {
                decodeParameter(position, readExtByte(buf, codec, CODEC_8_EXT, CODEC_16), buf, 1, codec, model);
            }
        }

        // Read 2 byte data
        if (BitUtil.check(globalMask, 2)) {
            int cnt = readExtByte(buf, codec, CODEC_8_EXT);
            for (int j = 0; j < cnt; j++) {
                decodeParameter(position, readExtByte(buf, codec, CODEC_8_EXT, CODEC_16), buf, 2, codec, model);
            }
        }

        // Read 4 byte data
        if (BitUtil.check(globalMask, 3)) {
            int cnt = readExtByte(buf, codec, CODEC_8_EXT);
            for (int j = 0; j < cnt; j++) {
                decodeParameter(position, readExtByte(buf, codec, CODEC_8_EXT, CODEC_16), buf, 4, codec, model);
            }
        }

        // Read 8 byte data
        if (codec == CODEC_8 || codec == CODEC_8_EXT || codec == CODEC_16) {
            int cnt = readExtByte(buf, codec, CODEC_8_EXT);
            for (int j = 0; j < cnt; j++) {
                decodeParameter(position, readExtByte(buf, codec, CODEC_8_EXT, CODEC_16), buf, 8, codec, model);
            }
        }

        // Read 16 byte data
        if (extended) {
            int cnt = readExtByte(buf, codec, CODEC_8_EXT);
            for (int j = 0; j < cnt; j++) {
                int id = readExtByte(buf, codec, CODEC_8_EXT, CODEC_16);
                position.set(Position.PREFIX_IO + id, ByteBufUtil.hexDump(buf.readSlice(16)));
            }
        }

        // Read X byte data
        if (codec == CODEC_8_EXT) {
            int cnt = buf.readUnsignedShort();
            for (int j = 0; j < cnt; j++) {
                int id = buf.readUnsignedShort();
                int length = buf.readUnsignedShort();
                if (id == 256) {
                    position.set(Position.KEY_VIN,
                            buf.readSlice(length).toString(StandardCharsets.US_ASCII));
                } else if (id == 281) {
                    position.set(Position.KEY_DTCS,
                            buf.readSlice(length).toString(StandardCharsets.US_ASCII).replace(',', ' '));
                } else if (id == 385) {
                    ByteBuf data = buf.readSlice(length);
                    data.readUnsignedByte(); // data part
                    int index = 1;
                    while (data.isReadable()) {
                        int flags = data.readUnsignedByte();
                        if (BitUtil.from(flags, 4) > 0) {
                            position.set("beacon" + index + "Uuid", ByteBufUtil.hexDump(data.readSlice(16)));
                            position.set("beacon" + index + "Major", data.readUnsignedShort());
                            position.set("beacon" + index + "Minor", data.readUnsignedShort());
                        } else {
                            position.set("beacon" + index + "Namespace", ByteBufUtil.hexDump(data.readSlice(10)));
                            position.set("beacon" + index + "Instance", ByteBufUtil.hexDump(data.readSlice(6)));
                        }
                        position.set("beacon" + index + "Rssi", (int) data.readByte());
                        if (BitUtil.check(flags, 1)) {
                            position.set("beacon" + index + "Battery", data.readUnsignedShort() * 0.01);
                        }
                        if (BitUtil.check(flags, 2)) {
                            position.set("beacon" + index + "Temp", data.readUnsignedShort());
                        }
                        index += 1;
                    }
                } else {
                    position.set(Position.PREFIX_IO + id, ByteBufUtil.hexDump(buf.readSlice(length)));
                }
            }
        }

        decodeNetwork(position, model);

    }

    private List<Position> parseData(
            Channel channel, SocketAddress remoteAddress, ByteBuf buf, int locationPacketId, String... imei) {
        List<Position> positions = new LinkedList<>();

        if (!connectionless) {
            buf.readUnsignedInt(); // data length
        }

        int codec = buf.readUnsignedByte();
        int count = buf.readUnsignedByte();

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }
        String model = getCacheManager().getObject(Device.class, deviceSession.getDeviceId()).getModel();

        for (int i = 0; i < count; i++) {
            Position position = new Position(getProtocolName());

            position.setDeviceId(deviceSession.getDeviceId());
            position.setValid(true);

            if (codec == CODEC_13) {
                buf.readUnsignedByte(); // type
                int length = buf.readInt() - 4;
                getLastLocation(position, new Date(buf.readUnsignedInt() * 1000));
                if (isPrintable(buf, length)) {
                    position.set(Position.KEY_RESULT,
                            buf.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                } else {
                    position.set(Position.KEY_RESULT,
                            ByteBufUtil.hexDump(buf.readSlice(length)));
                }
            } else if (codec == CODEC_12) {
                decodeSerial(channel, remoteAddress, deviceSession, position, buf);
            } else {
                decodeLocation(position, buf, codec, model);
            }

            if (!position.getOutdated() || !position.getAttributes().isEmpty()) {
                positions.add(position);
            }
        }

        if (channel != null && codec != CODEC_12 && codec != CODEC_13) {
            ByteBuf response = Unpooled.buffer();
            if (connectionless) {
                response.writeShort(5);
                response.writeShort(0);
                response.writeByte(0x01);
                response.writeByte(locationPacketId);
                response.writeByte(count);
            } else {
                response.writeInt(count);
            }
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }

        return positions.isEmpty() ? null : positions;
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        if (connectionless) {
            return decodeUdp(channel, remoteAddress, buf);
        } else {
            return decodeTcp(channel, remoteAddress, buf);
        }
    }

    private Object decodeTcp(Channel channel, SocketAddress remoteAddress, ByteBuf buf) throws Exception {

        if (buf.getUnsignedShort(0) > 0) {
            parseIdentification(channel, remoteAddress, buf);
        } else {
            buf.skipBytes(4);
            return parseData(channel, remoteAddress, buf, 0);
        }

        return null;
    }

    private Object decodeUdp(Channel channel, SocketAddress remoteAddress, ByteBuf buf) throws Exception {

        buf.readUnsignedShort(); // length
        buf.readUnsignedShort(); // packet id
        buf.readUnsignedByte(); // packet type
        int locationPacketId = buf.readUnsignedByte();
        String imei = buf.readSlice(buf.readUnsignedShort()).toString(StandardCharsets.US_ASCII);

        return parseData(channel, remoteAddress, buf, locationPacketId, imei);

    }

}
