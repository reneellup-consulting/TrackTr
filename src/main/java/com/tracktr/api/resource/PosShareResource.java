/*
 * Copyright 2023 Renee L. Llup (renee.llup@hypersolutionsph.com)
 *
 * This code is confidential and is a proprietary code of Hyper Solutions Philippines.
 * You shall not disclose any part of this code and shall use it only in accordance
 * with the terms of the license agreement you entered into with Hyper Solutions Philippines.
 */
package com.tracktr.api.resource;

import com.tracktr.api.ExtendedObjectResource;
import com.tracktr.api.limited.LimitedService;
import com.tracktr.config.Config;
import com.tracktr.config.Keys;
import com.tracktr.helper.LogAction;
import com.tracktr.mail.MailManager;
import com.tracktr.model.PosShares;
import com.tracktr.model.User;
import com.tracktr.model.Permission;
import com.tracktr.model.Device;
import com.tracktr.model.PosShareDevices;
import com.tracktr.notification.TextTemplateFormatter;
import com.tracktr.session.ConnectionManager;
import com.tracktr.session.cache.CacheManager;
import com.tracktr.storage.StorageException;
import com.tracktr.storage.query.Columns;
import com.tracktr.storage.query.Condition;
import com.tracktr.storage.query.Request;
import org.eclipse.jetty.util.URIUtil;

import javax.inject.Inject;
import javax.mail.MessagingException;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.UUID;

@Path("posshare")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PosShareResource extends ExtendedObjectResource<PosShares> {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private LimitedService limitedService;

    @Inject
    private Config config;

    @Inject
    private MailManager mailManager;

    @Inject
    private TextTemplateFormatter textTemplateFormatter;

    public Config getConfig() {
        return config;
    }

    public PosShareResource() {
        super(PosShares.class);
    }

    @POST
    public Response add(PosShares entity) throws StorageException, MessagingException {
        permissionsService.checkAdmin(getUserId());

        permissionsService.checkEdit(getUserId(), entity, true);

        // Check if limited user is already in the database in none, create a new limited user
        User limitedUser = limitedService.checkLimitedUser(getUserId());

        if (entity.getGroupId() == null) {
            entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id", "groupId", "url",
                    "deviceIdsArray", "deviceUuid"))));
            storage.addPermission(new Permission(User.class, getUserId(), PosShares.class, entity.getId()));
            entity.setDeviceUuId(UUID.randomUUID());
            // Retrieve system urlpath
            Config config1 = getConfig();
            String urlPath = "";
            if (config1.hasKey(Keys.WEB_URL)) {
                urlPath = config1.getString(Keys.WEB_URL);
            } else {
                String address;
                try {
                    address = config.getString(Keys.WEB_ADDRESS, InetAddress.getLocalHost().getHostAddress());
                } catch (UnknownHostException e) {
                    address = "localhost";
                }
                urlPath = URIUtil.newURI("http", address, config.getInteger(Keys.WEB_PORT),
                        "/shared/limited/" + entity.getDeviceUuId(), "");
            }
            entity.setUrl(urlPath);
            // Update the PosShare entity with the deviceUuid and URL
            storage.updateObject(entity, new Request(new Columns.Exclude("id", "groupId", "deviceIdsArray"),
                    new Condition.Equals("id", entity.getId())));
            // Check if selected devices are already added to the limited user devices
            storage.addPermission(new Permission(User.class, limitedUser.getId(), Device.class, entity.getDeviceId()));
            // Add the PosShare.DeviceId to the PosShareDevices table
            PosShareDevices posShareDevices1 = new PosShareDevices();
            posShareDevices1.setPosShareId(entity.getId());
            posShareDevices1.setDeviceId(entity.getDeviceId());
            storage.addObject(posShareDevices1, new Request(new Columns.All()));
            // Add the PosShare.DeviceIds to the PosShareDevices table except the device
            // specified in the PosShare.DeviceId
            if (entity.getDeviceIdsArray().length > 0) {
                long[] myArray = entity.getDeviceIdsArray();

                for (long id : myArray) {
                    if (id == entity.getDeviceId()) {
                        continue;
                    }
                    PosShareDevices posShareDevices = new PosShareDevices();
                    posShareDevices.setPosShareId(entity.getId());
                    posShareDevices.setDeviceId(id);
                    try {
                        storage.addObject(posShareDevices, new Request(new Columns.All()));
                        // Check if selected devices are already added to the limited user devices
                        storage.addPermission(new Permission(User.class, limitedUser.getId(), Device.class,
                                posShareDevices.getDeviceId()));
                    } catch (StorageException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } else {
            entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
            // Loop through the group devices and add them to the limited user devices
            storage.getObjects(Device.class, new Request(new Columns.Exclude("id", "groupId")))
                    .forEach(device -> {
                try {
                    storage.addPermission(new Permission(User.class, limitedUser.getId(), Device.class,
                            device.getId()));
                } catch (StorageException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // Email the intended PosShare email address
        if (limitedUser != null) {
            limitedUser.setEmail(entity.getEmail());
            var velocityContext = textTemplateFormatter.preparePosShareContext(permissionsService.getServer(), entity);
            var fullMessage = textTemplateFormatter.formatMessage(velocityContext, "positionShare",
                    "full");
            var subject = fullMessage.getSubject();
            var body = fullMessage.getBody();
            mailManager.sendMessage(limitedUser, fullMessage.getSubject(), fullMessage.getBody());
        }

        return Response.ok(entity).build();
    }

    // Update PosShare
    @PUT
    public Response update(PosShares entity) throws StorageException, MessagingException {
        permissionsService.checkAdmin(getUserId());
        permissionsService.checkEdit(getUserId(), entity, false);

        // Check if limited user is already in the database in none, create a new limited user
        User limitedUser = limitedService.checkLimitedUser(getUserId());

        if (entity.getGroupId() == null) {
            // Update the PosShare entity with the deviceUuid and URL
            storage.updateObject(entity, new Request(new Columns.Exclude("id", "groupId", "deviceIdsArray",
                    "url", "deviceUuId"), new Condition.Equals("id", entity.getId())));
            // Check if selected devices are already added to the limited user devices
            storage.addPermission(new Permission(User.class, limitedUser.getId(), Device.class, entity.getDeviceId()));
            // Delete all PosShareDevices of the update PosShareId
            storage.removeObject(PosShareDevices.class, new Request(new Condition.Equals("posShareId",
                    entity.getId())));
            // Add the PosShare.DeviceId to the PosShareDevices table
            PosShareDevices posShareDevices1 = new PosShareDevices();
            posShareDevices1.setPosShareId(entity.getId());
            posShareDevices1.setDeviceId(entity.getDeviceId());
            storage.addObject(posShareDevices1, new Request(new Columns.All()));
            // Add the PosShare.DeviceIds to the PosShareDevices table except the device specified in
            // the PosShare.DeviceId
            if (entity.getDeviceIdsArray().length > 0) {
                long[] myArray = entity.getDeviceIdsArray();

                for (long id : myArray) {
                    if (id == entity.getDeviceId()) {
                        continue;
                    }
                    PosShareDevices posShareDevices = new PosShareDevices();
                    posShareDevices.setPosShareId(entity.getId());
                    posShareDevices.setDeviceId(id);
                    try {
                        storage.addObject(posShareDevices, new Request(new Columns.All()));
                        // Check if selected devices are already added to the limited user devices
                        storage.addPermission(new Permission(User.class, limitedUser.getId(), Device.class,
                                posShareDevices.getDeviceId()));
                    } catch (StorageException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } else {
            // Update the PosShare entity with the deviceUuid and URL
            storage.updateObject(entity, new Request(new Columns.Exclude("id", "deviceIdsArray", "url",
                    "deviceUuId"), new Condition.Equals("id", entity.getId())));
            // Loop through the group devices and add them to the limited user devices
            storage.getObjects(Device.class, new Request(new Columns.Exclude("id", "groupId")))
                    .forEach(device -> {
                try {
                    storage.addPermission(new Permission(User.class, limitedUser.getId(), Device.class,
                            device.getId()));
                } catch (StorageException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // Email the update to the intended PosShare email address
        if (limitedUser != null) {
            limitedUser.setEmail(entity.getEmail());
            var velocityContext = textTemplateFormatter.preparePosShareContext(permissionsService.getServer(), entity);
            var fullMessage = textTemplateFormatter.formatMessage(velocityContext, "positionShare",
                    "full");
            var subject = fullMessage.getSubject();
            var body = fullMessage.getBody();
            mailManager.sendMessage(limitedUser, fullMessage.getSubject(), fullMessage.getBody());
        }

        var updated = storage.getObject(baseClass, new Request(new Columns.Exclude("deviceIdsArray"),
                new Condition.Equals("id", entity.getId())));
        return Response.ok(updated).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        permissionsService.checkEdit(getUserId(), baseClass, false);
        permissionsService.checkPermission(baseClass, getUserId(), id);

        storage.removeObject(baseClass, new Request(new Condition.Equals("id", id)));
        cacheManager.invalidate(baseClass, id);

        // Delete all PosShareDevices of the deleted PosShareId
        storage.removeObject(PosShareDevices.class, new Request(new Condition.Equals("posShareId", id)));

        LogAction.remove(getUserId(), baseClass, id);

        return Response.noContent().build();
    }

    @GET
    @Path("shared")
    public Collection<PosShares> get(@QueryParam("posShareId") long posShareId) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        var conditions = new LinkedList<Condition>();
        if (posShareId == 0) {
            conditions.add(new Condition.Permission(User.class, getUserId(), PosShares.class));
        } else {
            permissionsService.checkPermission(PosShares.class, getUserId(), posShareId);
            conditions.add(new Condition.Equals("id", posShareId));
            conditions.add(new Condition.Permission(User.class, getUserId(), PosShares.class).excludeGroups());
        }

        return storage.getObjects(baseClass, new Request(new Columns.Exclude("deviceIdsArray"),
                Condition.merge(conditions)));
    }
}
