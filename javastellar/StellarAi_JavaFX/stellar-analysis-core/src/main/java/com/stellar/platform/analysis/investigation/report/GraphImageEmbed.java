package com.stellar.platform.analysis.investigation.report;

import java.util.Base64;

/** Embeds an already-rendered graph PNG. Does not plot samples. */
public final class GraphImageEmbed {

    private GraphImageEmbed() {
    }

    public static String pngDataUri(byte[] png) {
        if (png == null || png.length == 0) {
            return "";
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }
}
