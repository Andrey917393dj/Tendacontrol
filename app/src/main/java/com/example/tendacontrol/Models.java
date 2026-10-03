package com.example.tendacontrol;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {}

    public static class Device {
        public String id;
        public String ip;
        public String mac;
        public String name;
        public String currentText;
        public boolean enabled;
        public boolean selected;
        public boolean frozen;
        public List<String> options = new ArrayList<>();

        public Device copy() {
            Device d = new Device();
            d.id = id; d.ip = ip; d.mac = mac; d.name = name; d.currentText = currentText;
            d.enabled = enabled; d.selected = selected; d.frozen = frozen;
            d.options = new ArrayList<>(options);
            return d;
        }

        public String key() {
            if (mac != null && !mac.isEmpty()) return mac.toLowerCase(java.util.Locale.ROOT);
            if (id != null && !id.isEmpty()) return id;
            return ip;
        }
    }
}
