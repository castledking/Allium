package codes.castled.allium.economy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Immutable authenticated intent. Admission freezes UUIDs and effective settings. */
public record EconomyRequest(UUID id, String origin, String key, Kind kind, String actor,
                             UUID source, UUID recipient, Money amount, EconomySettings settings,
                             UUID taxAccount, Money taxAmount) {
    public enum Kind { OPEN, OPEN_SYSTEM, PAY, TRANSFER, DEPOSIT, WITHDRAW, ADMIN_SET, RESERVE, CAPTURE, RELEASE, SPEND }
    public EconomyRequest(UUID id, String origin, String key, Kind kind, String actor,
                          UUID source, UUID recipient, Money amount, EconomySettings settings) {
        this(id, origin, key, kind, actor, source, recipient, amount, settings, null, new Money(0));
    }
    public EconomyRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(source);
        Objects.requireNonNull(amount); Objects.requireNonNull(settings); Objects.requireNonNull(taxAmount);
        if (origin == null || origin.isBlank() || origin.length()>128 || key == null || key.isBlank() || key.length()>256
                || actor == null || actor.isBlank() || actor.length()>128) throw new IllegalArgumentException("bounded operation identity required");
        boolean twoParty = kind==Kind.PAY || kind==Kind.TRANSFER;
        if (twoParty && (recipient==null || recipient.equals(source))) throw new IllegalArgumentException("distinct transfer recipient required");
        if (!twoParty && recipient!=null) throw new IllegalArgumentException("unexpected recipient");
        if (kind!=Kind.ADMIN_SET && kind!=Kind.OPEN && kind!=Kind.OPEN_SYSTEM) amount.positive();
        if (kind==Kind.OPEN && !amount.equals(settings.starting())) throw new IllegalArgumentException("OPEN grant must match admitted settings");
        if (kind==Kind.OPEN_SYSTEM && amount.minor()!=0) throw new IllegalArgumentException("OPEN_SYSTEM grant must be zero");
        if (taxAmount.minor()<0) throw new IllegalArgumentException("tax amount must not be negative");
        if (taxAmount.minor()>0) {
            if (kind!=Kind.PAY && kind!=Kind.CAPTURE) throw new IllegalArgumentException("tax leg is only valid on PAY or CAPTURE");
            if (taxAccount==null || taxAccount.equals(source) || taxAccount.equals(recipient)) throw new IllegalArgumentException("distinct tax account required");
            if (taxAmount.minor()>=amount.minor()) throw new IllegalArgumentException("tax must be less than gross amount");
        } else if (taxAccount!=null) {
            throw new IllegalArgumentException("tax account requires positive tax amount");
        }
    }
    public boolean reservation(){return kind==Kind.RESERVE||kind==Kind.CAPTURE||kind==Kind.RELEASE||kind==Kind.SPEND;}
    byte[] payload() {
        try {
            var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);
            boolean taxed = taxAmount.minor() > 0;
            data.writeInt(taxed ? 2 : 1); data.writeUTF(origin); data.writeUTF(key); data.writeUTF(kind.name());
            data.writeUTF(actor); data.writeUTF(source.toString()); data.writeUTF(recipient==null?"":recipient.toString());
            data.writeLong(amount.minor()); data.writeUTF(reservation()?"workflow-v1":settings.fingerprint());
            if (taxed) {
                data.writeUTF(taxAccount.toString());
                data.writeLong(taxAmount.minor());
            }
            data.flush();return bytes.toByteArray();
        } catch(IOException impossible){throw new AssertionError(impossible);}
    }
}
