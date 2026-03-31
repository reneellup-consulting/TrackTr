package com.tracktr;

import io.netty.channel.Channel;
import com.tracktr.config.Config;
import com.tracktr.database.CommandsManager;
import com.tracktr.database.MediaManager;
import com.tracktr.database.StatisticsManager;
import com.tracktr.model.Device;
import com.tracktr.session.ConnectionManager;
import com.tracktr.session.DeviceSession;
import com.tracktr.session.cache.CacheManager;

import java.net.SocketAddress;
import java.util.HashSet;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BaseTest {

    protected <T extends BaseProtocolDecoder> T inject(T decoder) throws Exception {
        var config = new Config();
        decoder.setConfig(config);
        var device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        decoder.setCacheManager(cacheManager);
        var connectionManager = mock(ConnectionManager.class);
        var uniqueIdsProvided = new HashSet<Boolean>();
        when(connectionManager.getDeviceSession(any(), any(), any(), any())).thenAnswer(invocation -> {
            var mock = new DeviceSession(1L, "", mock(Protocol.class), mock(Channel.class), mock(SocketAddress.class));
            if (uniqueIdsProvided.isEmpty()) {
                if (invocation.getArguments().length > 3) {
                    uniqueIdsProvided.add(true);
                    return mock;
        }
                return null;
            } else {
                return mock;
        }
        });
        decoder.setConnectionManager(connectionManager);
        decoder.setStatisticsManager(mock(StatisticsManager.class));
        decoder.setMediaManager(mock(MediaManager.class));
        decoder.setCommandsManager(mock(CommandsManager.class));
        return decoder;
        }

    protected <T extends BaseFrameDecoder> T inject(T decoder) throws Exception {
        return decoder;
    }

    protected <T extends BaseProtocolEncoder> T inject(T encoder) throws Exception {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        when(device.getUniqueId()).thenReturn("123456789012345");
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(mock(Config.class));
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        encoder.setCacheManager(cacheManager);
        return encoder;
    }

}
