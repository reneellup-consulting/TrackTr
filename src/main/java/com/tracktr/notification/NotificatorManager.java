/*
 * Copyright 2018 - 2022 Anton Tananaev (anton@traccar.org)
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
package com.tracktr.notification;

import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tracktr.config.Config;
import com.tracktr.config.Keys;
import com.tracktr.model.Typed;
import com.tracktr.notificators.Notificator;
import com.tracktr.notificators.NotificatorFirebase;
import com.tracktr.notificators.NotificatorMail;
import com.tracktr.notificators.NotificatorNull;
import com.tracktr.notificators.NotificatorPushover;
import com.tracktr.notificators.NotificatorSms;
import com.tracktr.notificators.NotificatorTelegram;
import com.tracktr.notificators.NotificatorTraccar;
import com.tracktr.notificators.NotificatorWeb;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class NotificatorManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificatorManager.class);

    private static final Map<String, Class<? extends Notificator>> NOTIFICATORS_ALL = Map.of(
            "web", NotificatorWeb.class,
            "mail", NotificatorMail.class,
            "sms", NotificatorSms.class,
            "firebase", NotificatorFirebase.class,
            "traccar", NotificatorTraccar.class,
            "telegram", NotificatorTelegram.class,
            "pushover", NotificatorPushover.class);

    private final Injector injector;

    private final Set<String> types = new HashSet<>();

    @Inject
    public NotificatorManager(Injector injector, Config config) {
        this.injector = injector;
        String types = config.getString(Keys.NOTIFICATOR_TYPES);
        if (types != null) {
            this.types.addAll(Arrays.asList(types.split(",")));
        }
    }

    public Notificator getNotificator(String type) {
        var clazz = NOTIFICATORS_ALL.get(type);
        if (clazz != null) {
            var notificator = injector.getInstance(clazz);
            if (notificator != null) {
        return notificator;
    }
        }
        LOGGER.warn("Failed to get notificator {}", type);
        return new NotificatorNull();
    }

    public Set<Typed> getAllNotificatorTypes() {
        return types.stream().map(Typed::new).collect(Collectors.toUnmodifiableSet());
    }

}
