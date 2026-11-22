package codes.castled.allium.modguard;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class Wire {

    public static final int MAX_PAYLOAD = 131072;
    public static final int MAX_MODS = 512;

    private Wire() {
    }

    public static String utf8(byte[] b, int max) {
        if (b == null) {
            return "";
        }
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b))
                    .toString();
            if (s.length() > max) {
                return s.substring(0, max);
            }
            return s;
        } catch (CharacterCodingException e) {
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    public static String brand(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        try {
            Reader r = new Reader(b);
            return r.string(256);
        } catch (Exception e) {
            String s = new String(b, StandardCharsets.UTF_8);
            if (s.startsWith("\0")) {
                s = s.substring(1);
            }
            int len = b[0] & 0xFF;
            if (len > 0 && len < b.length) {
                s = new String(b, 1, len, StandardCharsets.UTF_8);
            }
            return Detection.clean(s, 256);
        }
    }

    public static List<Detection.Mod> legacyForge(byte[] b) {
        if (b == null || b.length == 0) {
            return List.of();
        }
        try {
            Reader r = new Reader(b);
            if (r.octet() != 2) {
                return List.of();
            }
            int n = r.varint();
            if (n < 0 || n > MAX_MODS) {
                return List.of();
            }
            List<Detection.Mod> mods = new ArrayList<>();
            for (int i = 0; i < n; ++i) {
                mods.add(new Detection.Mod(r.string(256), "", r.string(128), "FORGE_LEGACY"));
            }
            return List.copyOf(mods);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Optional<List<Detection.Mod>> lunarHandshake(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        try {
            Reader envelope = new Reader(payload);
            String type = "";
            byte[] value = null;
            while (envelope.more()) {
                int tag = envelope.varint();
                if (tag == 0) {
                    return Optional.empty();
                }
                if (tag == 10) {
                    type = envelope.string(256);
                } else if (tag == 18) {
                    value = envelope.field();
                } else {
                    envelope.skip(tag & 7);
                }
            }
            if (!type.equals("type.googleapis.com/lunarclient.apollo.player.v1.PlayerHandshakeMessage") || value == null) {
                return Optional.empty();
            }
            Reader body = new Reader(value);
            List<Detection.Mod> mods = new ArrayList<>();
            while (body.more()) {
                int tag = body.varint();
                if (tag == 0) {
                    break;
                }
                if (tag != 26) {
                    body.skip(tag & 7);
                    continue;
                }
                byte[] raw = body.field();
                if (mods.size() > MAX_MODS) {
                    continue;
                }
                Reader mod = new Reader(raw);
                String id = "";
                String name = "";
                String version = "";
                String kind = "TYPE_UNSPECIFIED";
                while (mod.more()) {
                    int field = mod.varint();
                    if (field == 0) {
                        break;
                    }
                    switch (field) {
                        case 10 -> id = mod.string(256);
                        case 18 -> name = mod.string(128);
                        case 26 -> version = mod.string(128);
                        case 32 -> kind = switch (mod.varint()) {
                            case 1 -> "TYPE_FABRIC_INTERNAL";
                            case 2 -> "TYPE_FABRIC_EXTERNAL";
                            case 3 -> "TYPE_FORGE_INTERNAL";
                            case 4 -> "TYPE_FORGE_EXTERNAL";
                            default -> "TYPE_UNSPECIFIED";
                        };
                        default -> mod.skip(field & 7);
                    }
                }
                if (!id.isBlank()) {
                    mods.add(new Detection.Mod(id, name, version, kind));
                }
            }
            return mods.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(mods));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("payload null");
            }
            if (data.length > MAX_PAYLOAD) {
                throw new IllegalArgumentException("payload too large");
            }
            this.data = data;
        }

        public boolean more() {
            return this.pos < this.data.length;
        }

        public int remaining() {
            return this.data.length - this.pos;
        }

        public int varint() {
            int out = 0;
            for (int i = 0; i < 5; ++i) {
                int v = this.octet();
                if (i == 4 && (v & 0xF0) != 0) {
                    throw new IllegalArgumentException("oversized varint");
                }
                out |= (v & 0x7F) << (7 * i);
                if ((v & 0x80) == 0) {
                    return out;
                }
            }
            throw new IllegalArgumentException("bad varint");
        }

        public int octet() {
            if (!this.more()) {
                throw new IllegalArgumentException("truncated");
            }
            return this.data[this.pos++] & 0xFF;
        }

        public byte[] bytes(int len) {
            if (len < 0 || len > this.remaining()) {
                throw new IllegalArgumentException("bad length");
            }
            byte[] b = Arrays.copyOfRange(this.data, this.pos, this.pos + len);
            this.pos += len;
            return b;
        }

        public byte[] field() {
            return this.bytes(this.varint());
        }

        public String string(int max) {
            byte[] b = this.field();
            if (b.length > max * 4) {
                throw new IllegalArgumentException("string too large");
            }
            return Wire.utf8(b, max);
        }

        public void skip(int type) {
            switch (type) {
                case 0 -> {
                    for (int i = 0; i < 10; ++i) {
                        if ((this.octet() & 0x80) == 0) {
                            return;
                        }
                    }
                    throw new IllegalArgumentException("bad varlong");
                }
                case 1 -> this.bytes(8);
                case 2 -> this.field();
                case 5 -> this.bytes(4);
                default -> throw new IllegalArgumentException("unsupported wire type " + type);
            }
        }
    }
}
