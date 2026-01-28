package com.grabx.app.grabx.ui.sidebar;

public class SidebarItem {

    public final String key;
    private final String title;

    public SidebarItem(String key, String title) {
        this.key = key;
        this.title = title;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }
}