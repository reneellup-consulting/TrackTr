/*
 * Copyright 2023 Renee L. Llup (renee.llup@hypersolutionsph.com)
 *
 * This code is confidential and is a proprietary code of Hyper Solutions Philippines.
 * You shall not disclose any part of this code and shall use it only in accordance
 * with the terms of the license agreement you entered into with Hyper Solutions Philippines.
 */
package com.tracktr.api.limited;

import com.google.inject.servlet.RequestScoped;
import com.tracktr.api.security.ServiceAccountUser;
import com.tracktr.helper.LogAction;
import com.tracktr.model.Server;
import com.tracktr.model.User;
import com.tracktr.storage.Storage;
import com.tracktr.storage.StorageException;
import com.tracktr.storage.query.Columns;
import com.tracktr.storage.query.Condition;
import com.tracktr.storage.query.Request;

import javax.inject.Inject;

@RequestScoped
public class LimitedService {

    private final Storage storage;

    private Server server;

    private User user;

    @Inject
    public LimitedService(Storage storage) {
        this.storage = storage;
    }

    public User getUser(long userId) throws StorageException {
        if (user == null && userId > 0) {
            if (userId == ServiceAccountUser.ID) {
                user = new ServiceAccountUser();
            } else {
                user = storage.getObject(
                        User.class, new Request(new Columns.All(), new Condition.Equals("id", userId)));
            }
        }
        return user;
    }

    public User checkLimitedUser(long userId) throws StorageException, SecurityException {
        if (!getUser(userId).getAdministrator()) {
            throw new SecurityException("Administrator access required");
        }
        // Check if limited user is already in the database
        User limitedUser = storage.getObject(
                User.class, new Request(new Columns.All(), new Condition.Equals("name", "limited")));
        if (limitedUser == null) {
            limitedUser = new User();
            limitedUser.setName("limited");
            limitedUser.setEmail("limited");
            limitedUser.setAdministrator(false);
            limitedUser.setPassword("1234");
            limitedUser.setDisabled(false);
            limitedUser.setReadonly(true);
            limitedUser.setLimitCommands(true);
            limitedUser.setFixedEmail(true);
            limitedUser.setId(storage.addObject(limitedUser, new Request(new Columns.Exclude("id", "poiLayer",
                    "attributes"))));
            storage.updateObject(limitedUser, new Request(
                    new Columns.Include("hashedPassword", "salt"),
                    new Condition.Equals("id", limitedUser.getId())));
            LogAction.create(userId, limitedUser);
        }

        return limitedUser;
    }
}
