package codes.castled.allium.economy;

import codes.castled.allium.kit.*;
import codes.castled.allium.mail.*;
import codes.castled.allium.storage.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.function.*;

/** Startup only, before player admission. Never mutates playerdata or delivers items. */
public final class EconomyRecovery {
    private EconomyRecovery(){}
    public static void sweep(ConnectionSource source,EconomyReservations holds,Path directory,
                             Predicate<UUID> online,Consumer<String> warning)throws SQLException {
        var ids=new ArrayList<EconomyReservations.Identity>();
        // Materialize first: no borrowed connection or write transaction during native file I/O.
        try(var c=source.connection();var s=c.createStatement();var r=s.executeQuery("SELECT workflow_kind,workflow_id FROM "+EconomySchema.RESERVATIONS+" WHERE state='HELD' ORDER BY workflow_kind,workflow_id")){
            while(r.next())ids.add(new EconomyReservations.Identity(EconomyReservations.Kind.valueOf(r.getString(1)),UUID.fromString(r.getString(2))));
        }
        var kits=new KitRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);
        var mail=new MailRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);
        for(var identity:ids){
            try{
                var row=holds.find(identity.kind(),identity.id()).orElseThrow();if(online.test(row.account()))continue;
                Path file=directory.resolve(row.account()+".dat");
                if(identity.kind()==EconomyReservations.Kind.KIT){
                    String raw=PlayerDataReceiptProof.read(file,PlayerDataReceiptProof.KIT_RECEIPTS);
                    var entries=KitReceipts.parse(raw);
                    if(entries.stream().anyMatch(e->e instanceof KitReceipts.Unknown))throw new SQLException("ambiguous kit receipt");
                    var delivery=kits.findDelivery(row.id()).orElseThrow(()->new SQLException("missing kit intent"));
                    var receipt=entries.stream().filter(e->e instanceof KitReceipts.Claimed k&&k.deliveryId().equals(row.id())).map(e->(KitReceipts.Claimed)e).findFirst();
                    if(receipt.isPresent()){
                        if(!receipt.get().token().equals(delivery.claimToken())||!receipt.get().kitName().equals(delivery.kitName()))throw new SQLException("conflicting kit receipt");
                        require(holds.capture(row.kind(),row.id(),c->kits.finishClaim(c,row.id(),delivery.claimToken(),delivery.claimOwner(),System.currentTimeMillis())));
                    }else require(holds.release(row.kind(),row.id(),c->kits.releaseClaim(c,row.id(),delivery.claimToken(),delivery.claimOwner())));
                }else{
                    String raw=PlayerDataReceiptProof.read(file,PlayerDataReceiptProof.MAIL_RECEIPTS);
                    var entries=MailReceipts.parse(raw);
                    if(entries.stream().anyMatch(e->e instanceof MailReceipts.Unknown))throw new SQLException("ambiguous mail receipt");
                    var intent=mail.find(row.id()).orElseThrow(()->new SQLException("missing mail intent"));
                    if(!intent.sender().equals(row.account())||intent.state()!=MailState.PENDING)throw new SQLException("conflicting mail intent");
                    if(entries.stream().anyMatch(e->e instanceof MailReceipts.Sent sent&&sent.mail().equals(row.id())))
                        require(holds.capture(row.kind(),row.id(),c->mail.activate(c,row.id())));
                    else require(holds.release(row.kind(),row.id(),c->mail.cancelPending(c,row.id())));
                }
            }catch(Exception error){warning.accept("Offline reservation "+identity.kind()+"/"+identity.id()+" retained: "+error.getMessage());}
        }
    }
    private static void require(EconomyResult.Mutation result)throws SQLException{
        if(!(result instanceof EconomyResult.Committed))throw new SQLException("recovery did not confirm commit: "+result.getClass().getSimpleName());
    }
}
