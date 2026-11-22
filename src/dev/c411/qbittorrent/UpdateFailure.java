package dev.c411.qbittorrent;

import java.io.IOException;

/** A failure of the update that the user is told about: its message is a French sentence, with no path and no address in it. */
final class UpdateFailure extends IOException {
    /** True when the file or the answer is not what the release announced: the version is then not downloaded again by itself. */
    final boolean integrity;

    UpdateFailure(String message, boolean integrity) {
        super(message);
        this.integrity = integrity;
    }
}
