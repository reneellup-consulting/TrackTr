/*
 * Copyright 2012 - 2022 Anton Tananaev (anton@traccar.org)
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
package com.tracktr;

import com.google.inject.Injector;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import com.tracktr.config.Config;
import com.tracktr.config.Keys;
import com.tracktr.handler.ComputedAttributesHandler;
import com.tracktr.handler.CopyAttributesHandler;
import com.tracktr.handler.DefaultDataHandler;
import com.tracktr.handler.DistanceHandler;
import com.tracktr.handler.EngineHoursHandler;
import com.tracktr.handler.FilterHandler;
import com.tracktr.handler.GeocoderHandler;
import com.tracktr.handler.GeolocationHandler;
import com.tracktr.handler.HemisphereHandler;
import com.tracktr.handler.MotionHandler;
import com.tracktr.handler.NetworkMessageHandler;
import com.tracktr.handler.OpenChannelHandler;
import com.tracktr.handler.RemoteAddressHandler;
import com.tracktr.handler.SpeedLimitHandler;
import com.tracktr.handler.StandardLoggingHandler;
import com.tracktr.handler.TimeHandler;
import com.tracktr.handler.events.AlertEventHandler;
import com.tracktr.handler.events.BehaviorEventHandler;
import com.tracktr.handler.events.CommandResultEventHandler;
import com.tracktr.handler.events.DriverEventHandler;
import com.tracktr.handler.events.FuelEventHandler;
import com.tracktr.handler.events.GeofenceEventHandler;
import com.tracktr.handler.events.IgnitionEventHandler;
import com.tracktr.handler.events.MaintenanceEventHandler;
import com.tracktr.handler.events.MediaEventHandler;
import com.tracktr.handler.events.MotionEventHandler;
import com.tracktr.handler.events.OverspeedEventHandler;

import java.util.Map;

public abstract class BasePipelineFactory extends ChannelInitializer<Channel> {

    private final Injector injector;
    private final TrackerConnector connector;
    private final String protocol;
    private int timeout;

    public BasePipelineFactory(TrackerConnector connector, Config config, String protocol) {
        this.injector = Main.getInjector();
        this.connector = connector;
        this.protocol = protocol;
        timeout = config.getInteger(Keys.PROTOCOL_TIMEOUT.withPrefix(protocol));
        if (timeout == 0) {
            timeout = config.getInteger(Keys.SERVER_TIMEOUT);
        }
    }

    protected abstract void addTransportHandlers(PipelineBuilder pipeline);

    protected abstract void addProtocolHandlers(PipelineBuilder pipeline);

    @SafeVarargs
    private void addHandlers(ChannelPipeline pipeline, Class<? extends ChannelHandler>... handlerClasses) {
        for (Class<? extends ChannelHandler> handlerClass : handlerClasses) {
            if (handlerClass != null) {
                pipeline.addLast(injector.getInstance(handlerClass));
            }
        }
    }

    public static <T extends ChannelHandler> T getHandler(ChannelPipeline pipeline, Class<T> clazz) {
        for (Map.Entry<String, ChannelHandler> handlerEntry : pipeline) {
            ChannelHandler handler = handlerEntry.getValue();
            if (handler instanceof WrapperInboundHandler) {
                handler = ((WrapperInboundHandler) handler).getWrappedHandler();
            } else if (handler instanceof WrapperOutboundHandler) {
                handler = ((WrapperOutboundHandler) handler).getWrappedHandler();
            }
            if (clazz.isAssignableFrom(handler.getClass())) {
                return (T) handler;
            }
        }
        return null;
    }

    @Override
    protected void initChannel(Channel channel) {
        final ChannelPipeline pipeline = channel.pipeline();

        addTransportHandlers(pipeline::addLast);

        if (timeout > 0 && !connector.isDatagram()) {
            pipeline.addLast(new IdleStateHandler(timeout, 0, 0));
        }
        pipeline.addLast(new OpenChannelHandler(connector));
        pipeline.addLast(new NetworkMessageHandler());
        pipeline.addLast(new StandardLoggingHandler(protocol));

        addProtocolHandlers(handler -> {
            if (handler instanceof BaseProtocolDecoder || handler instanceof BaseProtocolEncoder) {
                injector.injectMembers(handler);
            } else {
                if (handler instanceof ChannelInboundHandler) {
                    handler = new WrapperInboundHandler((ChannelInboundHandler) handler);
                } else {
                    handler = new WrapperOutboundHandler((ChannelOutboundHandler) handler);
                }
            }
            pipeline.addLast(handler);
        });

        addHandlers(
                pipeline,
                TimeHandler.class,
                GeolocationHandler.class,
                HemisphereHandler.class,
                DistanceHandler.class,
                RemoteAddressHandler.class,
                FilterHandler.class,
                GeocoderHandler.class,
                SpeedLimitHandler.class,
                MotionHandler.class,
                CopyAttributesHandler.class,
                EngineHoursHandler.class,
                ComputedAttributesHandler.class,
                PositionForwardingHandler.class,
                DefaultDataHandler.class,
                MediaEventHandler.class,
                CommandResultEventHandler.class,
                OverspeedEventHandler.class,
                BehaviorEventHandler.class,
                FuelEventHandler.class,
                MotionEventHandler.class,
                GeofenceEventHandler.class,
                AlertEventHandler.class,
                IgnitionEventHandler.class,
                MaintenanceEventHandler.class,
                DriverEventHandler.class,
                MainEventHandler.class);
    }

}
