package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;

interface AppServiceAware {
    void setAppService(DesktopAppService appService, MainShellController shell);
}
