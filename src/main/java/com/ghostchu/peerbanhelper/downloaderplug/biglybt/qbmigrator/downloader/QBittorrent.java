package com.ghostchu.peerbanhelper.downloaderplug.biglybt.qbmigrator.downloader;

import com.biglybt.core.logging.Logger;
import com.biglybt.core.tag.TagManager;
import com.biglybt.core.tag.TagManagerFactory;
import com.biglybt.core.tag.TagType;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pifimpl.local.download.DownloadImpl;
import com.biglybt.pifimpl.local.torrent.TorrentImpl;
import com.github.mizosoft.methanol.FormBodyPublisher;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MutableRequest;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class QBittorrent {
    private static final Gson GSON = new Gson();
    private final String apiEndpoint;
    private final Methanol httpClient;
    private final String username;
    private final String password;

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
                download.setDownloadRateLimitBytesPerSecond(qbTorrent.getDlLimit().intValue());
                download.setUploadRateLimitBytesPerSecond(qbTorrent.getUpLimit().intValue());
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
                download.recheckData();
                success++;
            } catch (Exception e) {
                e.printStackTrace();
                failed++;
            }
        }
        JOptionPane.showMessageDialog(null, "Migrated " + success + " torrents. (" + failed + " fails)");
    }

    public byte[] downloadTorrent(String hash) throws IOException {
        File file = Files.createTempFile("bbt-pbh-qbmigrator", ".torrent").toFile();
        if (!file.exists()) file.createNewFile();
        file.deleteOnExit();
        try {
            var resp = httpClient.send(MutableRequest.GET(apiEndpoint + "/torrents/export?hash=" + hash)
                    , HttpResponse.BodyHandlers.ofByteArray());
            return resp.body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
