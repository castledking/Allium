package codes.castled.allium.economy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Immutable authenticated intent. Admission freezes UUIDs and effective settings. */
public record EconomyRequest(UUID id, String origin, String key, Kind kind, String actor,
                             UUID source, UUID recipient, Money amount, EconomySettings settings) {
    public enum Kind { OPEN, PAY, DEPOSIT, WITHDRAW, ADMIN_SET, RESERVE, CAPTURE, RELEASE, SPEND }
    public EconomyRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(source);
        Objects.requireNonNull(amount); Objects.requireNonNull(settings);
        if (origin == null || origin.isBlank() || origin.length()>128 || key == null || key.isBlank() || key.length()>256
                || actor == null || actor.isBlank() || actor.length()>128) throw new IllegalArgumentException("bounded operation identity required");
        if (kind==Kind.PAY && (recipient==null || recipient.equals(source))) throw new IllegalArgumentException("distinct payment recipient required");
        if (kind!=Kind.PAY && recipient!=null) throw new IllegalArgumentException("unexpected recipient");
        if (kind!=Kind.ADMIN_SET && kind!=Kind.OPEN) amount.positive();
        if (kind==Kind.OPEN && !amount.equals(settings.starting())) throw new IllegalArgumentException("OPEN grant must match admitted settings");
    }
    public boolean reservation(){return kind==Kind.RESERVE||kind==Kind.CAPTURE||kind==Kind.RELEASE||kind==Kind.SPEND;}
    byte[] payload() {
        try {
            var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
            data.writeInt(1); data.writeUTF(origin); data.writeUTF(key); data.writeUTF(kind.name());
            data.writeUTF(actor); data.writeUTF(source.toString()); data.writeUTF(recipient==null?"":recipient.toString());
            data.writeLong(amount.minor()); data.writeUTF(reservation()?"workflow-v1":settings.fingerprint()); data.flush();return bytes.toByteArray();
        } catch(IOException impossible){throw new AssertionError(impossible);}
    }
}
