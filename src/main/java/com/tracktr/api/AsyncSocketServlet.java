/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
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
package com.tracktr.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import com.tracktr.api.resource.SessionResource;
import com.tracktr.config.Config;
import com.tracktr.config.Keys;
import com.tracktr.session.ConnectionManager;
import com.tracktr.storage.Storage;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpSession;
import java.time.Duration;

@Singleton
public class AsyncSocketServlet extends JettyWebSocketServlet {

    private final Config config;
    private final ObjectMapper objectMapper;
    private final ConnectionManager connectionManager;
    private final Storage storage;

    @Inject
    public AsyncSocketServlet(
            Config config, ObjectMapper objectMapper, ConnectionManager connectionManager, Storage storage) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.connectionManager = connectionManager;
        this.storage = storage;
    }

    @Override
    public void configure(JettyWebSocketServletFactory factory) {
        factory.setIdleTimeout(Duration.ofMillis(config.getLong(Keys.WEB_TIMEOUT)));
        factory.setCreator((req, resp) -> {
            if (req.getSession() != null) {
                long userId = (Long) ((HttpSession) req.getSession()).getAttribute(SessionResource.USER_ID_KEY);
                return new AsyncSocket(objectMapper, connectionManager, storage, userId);
            } else {
                return null;
            }
        });
    }

}
