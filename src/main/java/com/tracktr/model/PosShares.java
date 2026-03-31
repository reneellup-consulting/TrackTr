/*
 * Copyright 2023 Renee L. Llup (renee.llup@hypersolutionsph.com)
 *
 * This code is confidential and is a proprietary code of Hyper Solutions Philippines.
 * You shall not disclose any part of this code and shall use it only in accordance
 * with the terms of the license agreement you entered into with Hyper Solutions Philippines.
 */
package com.tracktr.model;

import com.tracktr.storage.StorageName;

import java.util.Date;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Arrays;

@StorageName("rl_posshares")
public class PosShares extends BaseModel {

    private boolean active;
    private Integer groupId;
    private long deviceId;
    private Date expireOn;
    private boolean deleteAfterExpiration;
    private String email;
    private String url;
    private String deviceIds;

    private UUID deviceUuId;

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Integer getGroupId() {
        return groupId;
    }

    public void setGroupId(Integer groupId) {
        this.groupId = groupId;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public Date getExpireOn() {
        return expireOn;
    }

    public void setExpireOn(Date expireOn) {
        this.expireOn = expireOn;
    }

    public boolean getDeleteAfterExpiration() {
        return deleteAfterExpiration;
    }

    public void setDeleteAfterExpiration(boolean deleteAfterExpiration) {
        this.deleteAfterExpiration = deleteAfterExpiration;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDeviceIds() {
        return deviceIds;
    }

    public void setDeviceIds(String deviceIds) {
        this.deviceIds = deviceIds;
    }

    public  long[] getDeviceIdsArray() {
        if (deviceIds == null) {
            return new long[0];
        }

        String input = deviceIds.trim();

        if (input.length() == 0) {
            return new long[0];
        }
        // Extract the array string
        String arrayString = input.substring(input.indexOf("[") + 1, input.lastIndexOf("]")).trim();

        // Split the string into individual elements
        String[] stringArray = arrayString.split(",");

        // Create a new integer array and convert the elements
        long[] longArray = new long[stringArray.length + 1];
        for (int i = 0; i < stringArray.length; i++) {
            longArray[i] = Long.parseLong(stringArray[i].trim());
        }

        longArray[stringArray.length] = deviceId;

        // Create a LinkedHashSet to store the unique elements in sorted order
        LinkedHashSet<Long> uniqueElements = new LinkedHashSet<>();

        // Add the sorted elements to the LinkedHashSet (duplicates will be automatically removed)
        for (long element : longArray) {
            uniqueElements.add(element);
        }

        // Create a new array to store the sorted unique elements
        long[] sortedUniqueArray = new long[uniqueElements.size()];

        // Copy the elements from the LinkedHashSet to the new array
        int index = 0;
        for (long element : uniqueElements) {
            sortedUniqueArray[index] = element;
            index++;
        }

        Arrays.sort(sortedUniqueArray);

        // Return the sorted array without duplicates
        return sortedUniqueArray;
    }

    public UUID getDeviceUuId() {
        return deviceUuId;
    }

    public void setDeviceUuId(UUID deviceUuId) {
        this.deviceUuId = deviceUuId;
    }

    public void checkExpiration() throws SecurityException {
        if (getExpireOn() != null && System.currentTimeMillis() > getExpireOn().getTime()) {
            throw new SecurityException(getClass().getSimpleName() + " has expired");
        }
    }

}
