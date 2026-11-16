package codes.castled.allium.chat;

import codes.castled.allium.storage.*;
import java.sql.*;
import java.util.*;

/** Storage formats v1: nicknames are raw inert formatting text; channel IDs are canonical CSV. */
public final class ChatRepository {
    public record State(UUID uuid,String nickname,String writeChannel,Set<String> reads,long version){
        public State{reads=Set.copyOf(reads);}
    }
    private final ConnectionSource source;
    private final SqlDialect dialect;
    public ChatRepository(ConnectionSource source,SqlDialect dialect){this.source=source;this.dialect=dialect;}
    public void migrate()throws SQLException{
        String nick="CREATE TABLE IF NOT EXISTS allium_nicknames (uuid CHAR(36) NOT NULL PRIMARY KEY, raw_text VARCHAR(512) NULL, format_version INT NOT NULL, version BIGINT NOT NULL, updated_at BIGINT NOT NULL)";
        String channels="CREATE TABLE IF NOT EXISTS allium_chat_preferences (uuid CHAR(36) NOT NULL PRIMARY KEY, write_channel VARCHAR(32) NOT NULL, read_channels TEXT NOT NULL, version BIGINT NOT NULL, updated_at BIGINT NOT NULL)";
        new SchemaMigrator(source,dialect,System::currentTimeMillis).migrate("chat",List.of(Migration.of(1,"nickname and channel preferences",List.of(nick,channels),List.of(nick,channels))));
    }
    public State load(UUID id)throws SQLException{
        try(var c=source.connection()){
            String nick=null,write="";Set<String> reads=Set.of();long version=0;
            try(var q=c.prepareStatement("SELECT raw_text,format_version,version FROM allium_nicknames WHERE uuid=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){if(r.next()){if(r.getInt(2)!=1)throw new SQLException("unsupported nickname format");nick=r.getString(1);version=r.getLong(3);}}}
            try(var q=c.prepareStatement("SELECT write_channel,read_channels FROM allium_chat_preferences WHERE uuid=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){if(r.next()){write=r.getString(1);reads=decodeReads(r.getString(2));}}}
            return new State(id,nick,write,reads,version);
        }
    }
    public void nickname(UUID id,String raw)throws SQLException{
        if(raw!=null&&raw.length()>512)throw new IllegalArgumentException("nickname too long");
        String sql=dialect==SqlDialect.SQLITE?"INSERT INTO allium_nicknames VALUES(?,?,1,1,?) ON CONFLICT(uuid) DO UPDATE SET raw_text=excluded.raw_text,version=allium_nicknames.version+1,updated_at=excluded.updated_at":"INSERT INTO allium_nicknames VALUES(?,?,1,1,?) ON DUPLICATE KEY UPDATE raw_text=VALUES(raw_text),version=version+1,updated_at=VALUES(updated_at)";
        try(var c=source.connection();var q=c.prepareStatement(sql)){q.setString(1,id.toString());q.setString(2,raw);q.setLong(3,System.currentTimeMillis());q.executeUpdate();}
    }
    public void channels(UUID id,String write,Set<String> reads)throws SQLException{
        channel(write);String csv=encodeReads(reads);
        String sql=dialect==SqlDialect.SQLITE?"INSERT INTO allium_chat_preferences VALUES(?,?,?,1,?) ON CONFLICT(uuid) DO UPDATE SET write_channel=excluded.write_channel,read_channels=excluded.read_channels,version=allium_chat_preferences.version+1,updated_at=excluded.updated_at":"INSERT INTO allium_chat_preferences VALUES(?,?,?,1,?) ON DUPLICATE KEY UPDATE write_channel=VALUES(write_channel),read_channels=VALUES(read_channels),version=version+1,updated_at=VALUES(updated_at)";
        try(var c=source.connection();var q=c.prepareStatement(sql)){q.setString(1,id.toString());q.setString(2,write);q.setString(3,csv);q.setLong(4,System.currentTimeMillis());q.executeUpdate();}
    }
    public static String channel(String name){if(name==null||!name.matches("[a-z0-9_-]{1,32}"))throw new IllegalArgumentException("invalid channel ID");return name;}
    public static String encodeReads(Set<String> reads){if(reads.size()>64)throw new IllegalArgumentException("too many channels");return String.join(",",reads.stream().map(ChatRepository::channel).sorted().toList());}
    public static Set<String> decodeReads(String csv){if(csv.isEmpty())return Set.of();Set<String> out=new LinkedHashSet<>();for(String name:csv.split(",",-1))out.add(channel(name));encodeReads(out);return Set.copyOf(out);}
}
