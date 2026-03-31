/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package com.tracktr.handler.events;

import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tracktr.config.Config;
import com.tracktr.config.Keys;
import com.tracktr.helper.model.AttributeUtil;
import com.tracktr.helper.model.PositionUtil;
import com.tracktr.model.Device;
import com.tracktr.model.Event;
import com.tracktr.model.Geofence;
import com.tracktr.model.Position;
import com.tracktr.session.cache.CacheManager;
import com.tracktr.session.state.OverspeedProcessor;
import com.tracktr.session.state.OverspeedState;
import com.tracktr.storage.Storage;
import com.tracktr.storage.StorageException;
import com.tracktr.storage.query.Columns;
import com.tracktr.storage.query.Condition;
import com.tracktr.storage.query.Request;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;

@Singleton
@ChannelHandler.Sharable
public class OverspeedEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverspeedEventHandler.class);

    private final CacheManager cacheManager;
    private final Storage storage;

    private final long minimalDuration;
    private final boolean preferLowest;

    @Inject
    public OverspeedEventHandler(
            Config config, CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        minimalDuration = config.getLong(Keys.EVENT_OVERSPEED_MINIMAL_DURATION) * 1000;
        preferLowest = config.getBoolean(Keys.EVENT_OVERSPEED_PREFER_LOWEST);
    }

    @Override
    protected Map<Event, Position> analyzePosition(Position position) {

        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null) {
            return null;
        }
        if (!PositionUtil.isLatest(cacheManager, position) || !position.getValid()) {
            return null;
        }

        double speedLimit = AttributeUtil.lookup(cacheManager, Keys.EVENT_OVERSPEED_LIMIT, deviceId);

        double positionSpeedLimit = position.getDouble(Position.KEY_SPEED_LIMIT);
        if (positionSpeedLimit > 0) {
            speedLimit = positionSpeedLimit;
        }

        double geofenceSpeedLimit = 0;
        long overspeedGeofenceId = 0;

        if (device.getGeofenceIds() != null) {
            for (long geofenceId : device.getGeofenceIds()) {
                Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
                if (geofence != null) {
                    double currentSpeedLimit = geofence.getDouble(Keys.EVENT_OVERSPEED_LIMIT.getKey());
                    if (currentSpeedLimit > 0 && geofenceSpeedLimit == 0
                            || preferLowest && currentSpeedLimit < geofenceSpeedLimit
                            || !preferLowest && currentSpeedLimit > geofenceSpeedLimit) {
                        geofenceSpeedLimit = currentSpeedLimit;
                        overspeedGeofenceId = geofenceId;
                    }
                }
            }
        }
        if (geofenceSpeedLimit > 0) {
            speedLimit = geofenceSpeedLimit;
        }

        if (speedLimit == 0) {
            return null;
        }

        OverspeedState state = OverspeedState.fromDevice(device);
        OverspeedProcessor.updateState(state, position, speedLimit, minimalDuration, overspeedGeofenceId);
        if (state.isChanged()) {
            state.toDevice(device);
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include("overspeedState", "overspeedTime", "overspeedGeofenceId"),
                        new Condition.Equals("id", device.getId())));
            } catch (StorageException e) {
                LOGGER.warn("Update device overspeed error", e);
            }
        }
        return state.getEvent() != null ? Collections.singletonMap(state.getEvent(), position) : null;
    }

}
