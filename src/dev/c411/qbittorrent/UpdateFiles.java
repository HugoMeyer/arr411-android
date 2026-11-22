package dev.c411.qbittorrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The names of the downloaded updates in the cache folder, and which of them are worth keeping: the one that is ready, and nothing older than the installed version. */
final class UpdateFiles {
    static final String PART_SUFFIX = ".part";
    private static final String PREFIX = "Arr411-";
    private static final String SUFFIX = ".apk";

    private UpdateFiles() {}

    /** "Arr411-2.3.2.apk" for a version, or null when it is not one. */
    static String apkName(String version) {
        String v = UpdateVersion.normalize(version);
        return v == null ? null : PREFIX + v + SUFFIX;
    }

    /** The name of the file the download is written to before it is verified. */
    static String partName(String version) {
        String name = apkName(version);
        return name == null ? null : name + PART_SUFFIX;
    }

    /** The version a file of {@link #apkName} is for; null for any other name, which includes a download that is not finished. */
    static String versionOf(String fileName) {
        if (fileName == null || !fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX) || fileName.length() <= PREFIX.length() + SUFFIX.length()) return null;
        String version = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
        return version.equals(UpdateVersion.normalize(version)) ? version : null;
    }

    /**
     * The names to delete. A download that is not finished, a file that is not an update and a version that is not newer than {@code current} always go.
     * Of the others only {@code keep} stays, or the newest one when {@code keep} is null.
     */
    static List<String> toDelete(Collection<String> names, String current, String keep) {
        String retain = null;
        if (keep != null) {
            String version = versionOf(keep);
            if (version != null && names.contains(keep) && UpdateVersion.isNewer(version, current)) retain = keep;
        } else {
            for (String name : names) {
                String version = versionOf(name);
                if (version == null || !UpdateVersion.isNewer(version, current)) continue;
                if (retain == null || UpdateVersion.isNewer(version, versionOf(retain))) retain = name;
            }
        }
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (!name.equals(retain)) out.add(name);
        }
        return out;
    }
}
