/*
 * Copyright 2017 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package com.tracktr.notificators;

import com.tracktr.database.StatisticsManager;
import com.tracktr.model.Event;
import com.tracktr.model.Position;
import com.tracktr.model.User;
import com.tracktr.notification.MessageException;
import com.tracktr.notification.NotificationFormatter;
import com.tracktr.sms.SmsManager;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class NotificatorSms implements Notificator {

    private final SmsManager smsManager;
    private final NotificationFormatter notificationFormatter;
    private final StatisticsManager statisticsManager;

    @Inject
    public NotificatorSms(
            SmsManager smsManager, NotificationFormatter notificationFormatter, StatisticsManager statisticsManager) {
        this.smsManager = smsManager;
        this.notificationFormatter = notificationFormatter;
        this.statisticsManager = statisticsManager;
    }

    @Override
    public void send(User user, Event event, Position position) throws MessageException, InterruptedException {
        if (user.getPhone() != null) {
            var shortMessage = notificationFormatter.formatMessage(user, event, position, "short");
            statisticsManager.registerSms();
            smsManager.sendMessage(user.getPhone(), shortMessage.getBody(), false);
        }
    }

}
