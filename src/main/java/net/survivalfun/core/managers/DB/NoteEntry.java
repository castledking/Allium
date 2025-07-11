package net.survivalfun.core.managers.DB;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

public class NoteEntry {
    private final int id;
    private final UUID playerUuid;
    private final UUID staffUuid;
    private final String note;
    private final Timestamp createdAt;

    public NoteEntry(int id, UUID playerUuid, UUID staffUuid, String note, Timestamp createdAt) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.note = note;
        this.createdAt = createdAt;
    }

    public NoteEntry(int id, UUID playerUuid, UUID staffUuid, String note, LocalDateTime createdAt) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.note = note;
        this.createdAt = Timestamp.valueOf(createdAt);
    }

    public int getId() { return id; }
    public UUID getPlayerUuid() { return playerUuid; }
    public UUID getStaffUuid() { return staffUuid; }
    public String getNote() { return note; }
    public Timestamp getCreatedAt() { return createdAt; }
}
