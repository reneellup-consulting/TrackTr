package com.tracktr.model;

import com.tracktr.storage.StorageName;

@StorageName("rl_posshare_devices")
public class PosShareDevices {

        private long posShareId;
        private long deviceId;

        public long getPosShareId() {
            return posShareId;
        }

        public void setPosShareId(long posShareId) {
            this.posShareId = posShareId;
        }

        public long getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(long deviceId) {
            this.deviceId = deviceId;
        }
}
