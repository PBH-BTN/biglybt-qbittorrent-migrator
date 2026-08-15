package com.ghostchu.peerbanhelper.downloaderplug.biglybt.qbmigrator.downloader;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public final class QBittorrentFileInfo {
    @SerializedName("index")
    private Integer index;

    @SerializedName("name")
    private String name;

    @SerializedName("size")
    private Long size;

    @SerializedName("progress")
    private Double progress;

    /**
     * 0 = do not download, 1 = normal, 6 = high, 7 = maximal.
     */
    @SerializedName("priority")
    private Integer priority;

    @SerializedName("is_seed")
    private Boolean isSeed;

    @SerializedName("piece_range")
    private int[] pieceRange;

    @SerializedName("availability")
    private Double availability;
}
