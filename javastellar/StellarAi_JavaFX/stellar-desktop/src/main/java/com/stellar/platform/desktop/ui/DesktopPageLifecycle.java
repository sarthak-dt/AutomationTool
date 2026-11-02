package com.stellar.platform.desktop.ui;

/** Optional lifecycle for heavyweight desktop pages managed by the main shell. */
public interface DesktopPageLifecycle {
    default void onShow() { }
    default void onHide() { }
    default void dispose() { }
}
