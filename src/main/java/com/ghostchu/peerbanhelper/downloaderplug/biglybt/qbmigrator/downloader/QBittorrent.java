package com.ghostchu.peerbanhelper.downloaderplug.biglybt.qbmigrator.downloader;

import com.biglybt.core.logging.Logger;
import com.biglybt.core.tag.TagManager;
import com.biglybt.core.tag.TagManagerFactory;
import com.biglybt.core.tag.TagType;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.disk.DiskManagerFileInfo;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pifimpl.local.download.DownloadImpl;
import com.biglybt.pifimpl.local.torrent.TorrentImpl;
import com.github.mizosoft.methanol.FormBodyPublisher;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MutableRequest;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.swing.*;
import java.io.File;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class QBittorrent {
    private static final Gson GSON = new Gson();
    private final String apiEndpoint;
    private final Methanol httpClient;
    private final String username;
    private final String password;
    private LoggerChannel logChannel;

    public QBittorrent(String endpoint, String username, String password) {
        this.apiEndpoint = stripTrailingSlash(endpoint) + "/api/v2";
        this.username = username;
        this.password = password;
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        Methanol.Builder builder = Methanol
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .defaultHeader("Accept-Encoding", "gzip,deflate")
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.of(10, ChronoUnit.SECONDS))
                .headersTimeout(Duration.of(10, ChronoUnit.SECONDS))
                .readTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .requestTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .cookieHandler(cm);
        this.httpClient = builder.build();
    }

    /**
     * An endpoint entered as "http://localhost:8080/" would otherwise become
     * "http://localhost:8080//api/v2", which qBittorrent rejects. Surrounding
     * whitespace is dropped for the same reason.
     */
    private static String stripTrailingSlash(String endpoint) {
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public void migrate(PluginInterface pif) {
        logChannel = pif.getLogger().getChannel("qBittorrent Migrator");
        if (!login()) {
            JOptionPane.showMessageDialog(null, "Failed to login qBittorrent WebUI!");
            return;
        }
        int success = 0;
        int failed = 0;
        for (QBittorrentTorrentMeta qbTorrent : getTorrentsMeta()) {
            try {
                var torrentFile = downloadTorrent(qbTorrent.getHash());
                var torrent = pif.getTorrentManager().createFromBEncodedData(torrentFile);
                var download = pif.getDownloadManager().addDownloadStopped(torrent, null, new File(qbTorrent.getSavePath()));
                if (!qbTorrent.getCategory().isBlank()) {
                    download.setCategory(qbTorrent.getCategory());
                }
                download.setDownloadRateLimitBytesPerSecond(toRateLimit(qbTorrent.getDlLimit()));
                download.setUploadRateLimitBytesPerSecond(toRateLimit(qbTorrent.getUpLimit()));
                TorrentUtils.setDisplayName(((TorrentImpl) torrent).getTorrent(), qbTorrent.getName());
                TagManager tm = TagManagerFactory.getTagManager();
                var tagType = tm.getTagType(TagType.TT_DOWNLOAD_MANUAL);
                if (!qbTorrent.getTags().trim().isBlank()) {
                    for (String s : qbTorrent.getTags().split(",")) {
                        s = s.trim();
                        var tag = tagType.getTag(s, true);
                        if (tag == null) tag = tagType.createTag(s, true);
                        tag.addTaggable(((DownloadImpl) download).getDownload());
                    }
                }
                applyFilePriorities(download, qbTorrent);
                download.recheckData();
                success++;
            } catch (Exception e) {
                e.printStackTrace();
                failed++;
            }
        }
        JOptionPane.showMessageDialog(null, "Migrated " + success + " torrents. (" + failed + " fails)");
    }

    /**
     * qBittorrent reports -1 for "no speed limit" while BiglyBT expects 0 and
     * treats a negative value as a real limit. A missing value is mapped to 0
     * as well, because dl_limit/up_limit are absent from /torrents/info on some
     * qBittorrent versions. Values beyond int range are clamped rather than
     * truncated, since truncation would wrap them into a negative limit.
     */
    private static int toRateLimit(Long qbLimit) {
        if (qbLimit == null || qbLimit < 0) {
            return 0;
        }
        return (int) Math.min(qbLimit, Integer.MAX_VALUE);
    }

    public byte[] downloadTorrent(String hash) {
        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(MutableRequest.GET(apiEndpoint + "/torrents/export?hash=" + hash)
                    , HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Unable to export torrent " + hash + ", HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /**
     * Copies the per-file priorities of a torrent. Without this a torrent where
     * only part of the files were selected is recreated with every file enabled
     * and BiglyBT starts downloading the rest.
     *
     * Failures are logged and swallowed on purpose: a torrent that migrated
     * correctly should not be reported as failed just because its priorities
     * could not be read.
     */
    private void applyFilePriorities(Download download, QBittorrentTorrentMeta qbTorrent) {
        try {
            List<QBittorrentFileInfo> qbFiles = getFilePriorities(qbTorrent.getHash());
            DiskManagerFileInfo[] biglyFiles = download.getDiskManagerFileInfo();
            if (qbFiles.size() != biglyFiles.length) {
                log("File count mismatch for " + qbTorrent.getName() + " (qBittorrent: "
                        + qbFiles.size() + ", BiglyBT: " + biglyFiles.length + "), skipping priorities");
                return;
            }
            for (int i = 0; i < qbFiles.size(); i++) {
                QBittorrentFileInfo qbFile = qbFiles.get(i);
                Integer priority = qbFile.getPriority();
                if (priority == null) {
                    continue;
                }
                Integer declaredIndex = qbFile.getIndex();
                int target = (declaredIndex == null) ? i : declaredIndex;
                if (target < 0 || target >= biglyFiles.length) {
                    log("File index " + target + " out of range for " + qbTorrent.getName()
                            + ", skipping that file");
                    continue;
                }
                // qBittorrent: 0 = do not download, 1 = normal, 6 = high, 7 = maximal
                // BiglyBT: PRIORITY_LOW = -1, PRIORITY_NORMAL = 0, PRIORITY_HIGH = 1
                if (priority == 0) {
                    biglyFiles[target].setSkipped(true);
                } else {
                    biglyFiles[target].setSkipped(false);
                    biglyFiles[target].setNumericPriority(priority >= 6
                            ? DiskManagerFileInfo.PRIORITY_HIGH
                            : DiskManagerFileInfo.PRIORITY_NORMAL);
                }
            }
        } catch (Exception e) {
            log("Could not set file priorities for " + qbTorrent.getName() + ": " + e);
        }
    }

    public List<QBittorrentFileInfo> getFilePriorities(String hash) {
        HttpResponse<String> request;
        try {
            request = httpClient.send(MutableRequest.GET(apiEndpoint + "/torrents/files?hash=" + hash), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (request.statusCode() != 200) {
            throw new IllegalStateException("Unable to retrieve file priorities");
        }
        return GSON.fromJson(request.body(), new TypeToken<List<QBittorrentFileInfo>>() {
        }.getType());
    }

    /**
     * System.out does not reach BiglyBT's log file, so the plugin channel is used.
     */
    private void log(String message) {
        if (logChannel != null) {
            logChannel.log(message);
        }
    }

    public List<QBittorrentTorrentMeta> getTorrentsMeta() {
        HttpResponse<String> request;
        try {
            request = httpClient.send(MutableRequest.GET(apiEndpoint + "/torrents/info"), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (request.statusCode() != 200) {
            throw new IllegalStateException("Unable to retrieve torrents meta");
        }
        return GSON.fromJson(request.body(), new TypeToken<List<QBittorrentTorrentMeta>>() {
        }.getType());
    }

    public boolean login() {
        try {
            HttpResponse<String> request = httpClient
                    .send(MutableRequest.POST(apiEndpoint + "/auth/login",
                                            FormBodyPublisher.newBuilder()
                                                    .query("username", username)
                                                    .query("password", password).build())
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                            , HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // return request.statusCode() == 200;
            return request.statusCode() == 200 && isLoggedIn();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean isLoggedIn() {
        HttpResponse<Void> resp;
        try {
            resp = httpClient.send(MutableRequest.GET(apiEndpoint + "/app/version"), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            return false;
        }
        return resp.statusCode() == 200;
    }


}
