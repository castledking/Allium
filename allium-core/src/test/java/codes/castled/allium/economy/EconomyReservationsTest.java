package codes.castled.allium.economy;
import codes.castled.allium.mail.*;
import codes.castled.allium.kit.*;
import codes.castled.allium.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static codes.castled.allium.economy.EconomyResult.*;
import static codes.castled.allium.economy.EconomyReservations.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
class EconomyReservationsTest {
    @TempDir Path path;
    ConnectionSource source;EconomyEngine engine;EconomyReservations holds;MailRepository mail;KitRepository kits;
    UUID payer=UUID.randomUUID(),recipient=UUID.randomUUID();
    @BeforeEach void setup()throws Exception{
        source=()->DriverManager.getConnection("jdbc:sqlite:"+path.resolve("money.db"));
        mail=new MailRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);mail.migrate();
        kits=new KitRepository(source,SqlDialect.SQLITE,System::currentTimeMillis);kits.migrate();
        engine=new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true);holds=engine.reservations();
        assertInstanceOf(Committed.class,engine.execute(engine.request(UUID.randomUUID(),"test","open",EconomyRequest.Kind.OPEN,"test",payer,null,engine.settings().starting())));
    }
    MailRecord gift(UUID id){return MailRecord.pendingGift(id,recipient,payer,"Payer",null,"payload",1,"server",System.currentTimeMillis());}
    Mutation reserve(UUID id,long amount)throws Exception{return holds.reserve(MAIL,id,payer,new Money(amount),c->mail.send(c,gift(id),MailLimits.DEFAULTS,true)==MailRepository.SendResult.SAVED);}
    Found balance(){return (Found)engine.balance(payer);}
    void invariant()throws Exception{try(var c=source.connection();var s=c.createStatement();var r=s.executeQuery("SELECT reserved_minor,(SELECT COALESCE(SUM(amount_minor),0) FROM allium_economy_reservations WHERE state='HELD') FROM allium_economy_accounts")){assertTrue(r.next());assertEquals(r.getLong(1),r.getLong(2));}}
    long count(String table)throws Exception{try(var c=source.connection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}}
    @Test void reserveAndPendingAreAtomicAndOrdinaryWithdrawCannotSpendHeldFunds()throws Exception{
        UUID id=UUID.randomUUID();assertInstanceOf(Committed.class,reserve(id,300));assertEquals(10000,balance().book().minor());assertEquals(300,balance().reserved().minor());assertEquals(MailState.PENDING,mail.find(id).orElseThrow().state());
        var denied=engine.execute(engine.request(UUID.randomUUID(),"test","withdraw",EconomyRequest.Kind.WITHDRAW,"test",payer,null,new Money(9701)));
        assertEquals("INSUFFICIENT_FUNDS",((Rejected)denied).reason());invariant();
    }
    @Test void captureAndReadyAcknowledgementCommitTogetherAndReplayNeverChargesAgain()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);assertInstanceOf(Committed.class,holds.capture(MAIL,id,c->mail.activate(c,id)));
        assertEquals(9700,balance().book().minor());assertEquals(0,balance().reserved().minor());assertEquals(MailState.READY,mail.find(id).orElseThrow().state());assertEquals(1,count("allium_mail_activations"));
        assertTrue(((Committed)holds.capture(MAIL,id,c->{fail("replay must not re-run activation");return false;})).replay());assertEquals(9700,balance().book().minor());invariant();
    }
    @Test void releaseChangesOnlyHeldAndRemovesPendingInSameCommit()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);assertInstanceOf(Committed.class,holds.release(MAIL,id,c->mail.cancelPending(c,id)));
        assertEquals(10000,balance().book().minor());assertEquals(0,balance().reserved().minor());assertTrue(mail.find(id).isEmpty());
        assertTrue(((Committed)holds.release(MAIL,id,c->{fail("no duplicate cancellation");return false;})).replay());assertInstanceOf(Rejected.class,holds.capture(MAIL,id,c->true));invariant();
    }
    @Test void rejectedWorkflowRollsBackHeldAndEveryWorkflowWrite()throws Exception{
        UUID id=UUID.randomUUID();var r=holds.reserve(MAIL,id,payer,new Money(300),c->{mail.send(c,gift(id),MailLimits.DEFAULTS,true);return false;});
        assertEquals("WORKFLOW_REFUSED",((Rejected)r).reason());assertEquals(0,balance().reserved().minor());assertTrue(mail.find(id).isEmpty());assertTrue(holds.find(MAIL,id).isEmpty());invariant();
    }
    @Test void sqlFailureBeforeCommitDoesNotLeaveMoneyOrWorkflowAndStableRetryWorks()throws Exception{
        UUID id=UUID.randomUUID();assertInstanceOf(Unavailable.class,holds.reserve(MAIL,id,payer,new Money(300),c->{mail.send(c,gift(id),MailLimits.DEFAULTS,true);throw new SQLException("storage failure");}));
        assertTrue(mail.find(id).isEmpty());assertTrue(holds.find(MAIL,id).isEmpty());assertEquals(0,balance().reserved().minor());assertInstanceOf(Committed.class,reserve(id,300));invariant();
    }
    @Test void concurrentReservationsAgainstFiniteBalanceCannotOversubscribe()throws Exception{
        assertInstanceOf(Committed.class,engine.execute(engine.request(UUID.randomUUID(),"test","set",EconomyRequest.Kind.ADMIN_SET,"test",payer,null,new Money(100))));
        var pool=Executors.newFixedThreadPool(8);try{
            List<Callable<Mutation>> tasks=new ArrayList<>();for(int i=0;i<20;i++){UUID id=UUID.randomUUID();tasks.add(()->reserve(id,30));}
            long committed=0;for(var f:pool.invokeAll(tasks))if(f.get() instanceof Committed)committed++;
            assertEquals(3,committed);assertEquals(90,balance().reserved().minor());assertEquals(3,count("allium_mail"));invariant();
        }finally{pool.shutdownNow();}
    }
    @Test void captureAndReleaseRaceAcrossIndependentOwnersHasOneWinner()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);var other=new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true).reservations();
        var pool=Executors.newFixedThreadPool(2);try{
            var a=pool.submit(()->holds.capture(MAIL,id,c->mail.activate(c,id)));var b=pool.submit(()->other.release(MAIL,id,c->mail.cancelPending(c,id)));
            int winners=(a.get() instanceof Committed?1:0)+(b.get() instanceof Committed?1:0);assertEquals(1,winners);assertEquals(0,balance().reserved().minor());assertTrue(balance().book().minor()==9700||balance().book().minor()==10000);invariant();
        }finally{pool.shutdownNow();}
    }
    EconomyReservations fault(boolean after)throws Exception{
        return new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true,(c,id)->{
            String kind;try(var s=c.prepareStatement("SELECT kind FROM allium_economy_operations WHERE operation_id=?")){s.setString(1,id.toString());try(var r=s.executeQuery()){r.next();kind=r.getString(1);}}
            if(kind.equals("CAPTURE")&&!after)throw new SQLException("before commit");
            try(var s=c.createStatement()){s.execute("COMMIT");}if(kind.equals("CAPTURE"))throw new SQLException("lost acknowledgement");
        }).reservations();
    }
    @Test void crashBeforeCaptureCommitRecoversHeldAndOriginalIdOnce()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);assertInstanceOf(Unknown.class,fault(false).capture(MAIL,id,c->mail.activate(c,id)));assertEquals(10000,balance().book().minor());assertEquals(300,balance().reserved().minor());assertEquals(MailState.PENDING,mail.find(id).orElseThrow().state());
        var restarted=new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true).reservations();assertInstanceOf(Committed.class,restarted.capture(MAIL,id,c->mail.activate(c,id)));assertTrue(((Committed)restarted.capture(MAIL,id,c->true)).replay());assertEquals(9700,balance().book().minor());invariant();
    }
    @Test void lostCaptureAcknowledgementReplaysFrozenReceiptAfterRestart()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);assertInstanceOf(Unknown.class,fault(true).capture(MAIL,id,c->mail.activate(c,id)));
        assertEquals(9700,balance().book().minor());assertEquals(0,balance().reserved().minor());var restarted=new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true).reservations();
        assertTrue(((Committed)restarted.capture(MAIL,id,c->{fail("already committed");return false;})).replay());assertEquals(9700,balance().book().minor());invariant();
    }
    @Test void textMailMaySpendAndSaveReadyInSingleCommitWithoutVisibleHold()throws Exception{
        UUID id=UUID.randomUUID();var record=MailRecord.text(id,recipient,payer,"Payer","Hello","server",System.currentTimeMillis(),0);
        var r=holds.spend(MAIL,id,payer,new Money(125),EconomyReservations.fingerprint("text payload"),c->mail.send(c,record,MailLimits.DEFAULTS,true)==MailRepository.SendResult.SAVED);
        assertInstanceOf(Committed.class,r);assertEquals(9875,balance().book().minor());assertEquals(0,balance().reserved().minor());assertEquals("CAPTURED",holds.find(MAIL,id).orElseThrow().state());assertEquals(MailState.READY,mail.find(id).orElseThrow().state());invariant();
    }
    @Test void lostTextSpendAcknowledgementReplaysWithoutSecondSendOrDebit()throws Exception {
        UUID id=UUID.randomUUID();var record=MailRecord.text(id,recipient,payer,"Payer","Hello","server",System.currentTimeMillis(),0);String intent=EconomyReservations.fingerprint("stable text");
        var failing=new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true,(c,op)->{try(var statement=c.createStatement()){statement.execute("COMMIT");}throw new SQLException("lost ack");}).reservations();
        assertInstanceOf(Unknown.class,failing.spend(MAIL,id,payer,new Money(125),intent,c->mail.send(c,record,MailLimits.DEFAULTS,true)==MailRepository.SendResult.SAVED));
        assertTrue(((Committed)holds.spend(MAIL,id,payer,new Money(125),intent,c->{fail("no second send");return false;})).replay());assertEquals(9875,balance().book().minor());assertEquals(1,count("allium_mail"));invariant();
    }
    @Test void changedWorkflowPayloadUnderSameKeyIsConflictEvenAtSamePrice()throws Exception{
        UUID id=UUID.randomUUID();String original=EconomyReservations.fingerprint("payload A"),changed=EconomyReservations.fingerprint("payload B");
        assertInstanceOf(Committed.class,holds.reserve(MAIL,id,payer,new Money(300),original,c->true));
        assertEquals("IDEMPOTENCY_CONFLICT",((Rejected)holds.reserve(MAIL,id,payer,new Money(300),changed,c->{fail("conflict must not enter workflow");return true;})).reason());assertEquals(300,balance().reserved().minor());invariant();
    }
    @Test void paidMarkerPersistsAfterCaptureAndMailDeletionSoDisabledEconomyCannotBypassPayment()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);holds.capture(MAIL,id,c->mail.activate(c,id));assertTrue(mail.paid(id));
        try(var c=source.connection();var s=c.createStatement()){s.execute("DELETE FROM allium_mail");}assertTrue(mail.paid(id));invariant();
    }
    @Test void kitCaptureCommitsCooldownReceiptAndChargeTogether()throws Exception{
        UUID id=UUID.randomUUID(),token=UUID.randomUUID();var delivery=new KitDeliveryRecord(id,payer,"paid",token,"server","minecraft:diamond 1",1,60000,true,false,System.currentTimeMillis());
        assertInstanceOf(Committed.class,holds.reserve(KIT,id,payer,new Money(250),c->kits.beginClaim(c,delivery,System.currentTimeMillis()) instanceof KitRepository.BeginOutcome.Claiming));
        assertInstanceOf(Committed.class,holds.capture(KIT,id,c->kits.finishClaim(c,id,token,"server",System.currentTimeMillis())));
        assertTrue(kits.claimAcknowledged(id,token));assertTrue(kits.usage(payer,"paid").orElseThrow().oneTimeClaimed());assertEquals(9750,balance().book().minor());assertTrue(kits.findDelivery(id).isEmpty());invariant();
    }
    @Test void unprovenTerminalWorkflowRetainsHoldAndRetriesSameIdAfterEvidenceReturns()throws Exception {
        UUID id=UUID.randomUUID();reserve(id,300);
        assertInstanceOf(Unavailable.class,holds.capture(MAIL,id,c->false));assertEquals("HELD",holds.find(MAIL,id).orElseThrow().state());assertEquals(10000,balance().book().minor());assertEquals(300,balance().reserved().minor());
        assertInstanceOf(Committed.class,holds.capture(MAIL,id,c->mail.activate(c,id)));assertEquals(9700,balance().book().minor());assertEquals(0,balance().reserved().minor());invariant();
    }
    @Test void missingHoldAndCorruptHeldInvariantFailClosed()throws Exception{
        UUID id=UUID.randomUUID();assertInstanceOf(Rejected.class,holds.capture(KIT,id,c->true));
        try(var c=source.connection();var s=c.createStatement()){s.execute("UPDATE allium_economy_accounts SET reserved_minor=1");}
        assertThrows(SQLException.class,()->new EconomyEngine(source,EconomySettings.defaults(),(c,a,b)->true));
    }
    @Test void captureReplayUsesFrozenWorkflowAfterSettingsChange()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);holds.capture(MAIL,id,c->mail.activate(c,id));
        var old=engine.settings();engine.installSettings(new EconomySettings(old.starting(),old.minimum(),old.maximum(),new Money(2),old.offlinePay(),old.excludesIgnore(),old.defaultAcceptance()));
        assertTrue(((Committed)holds.capture(MAIL,id,c->{fail("frozen replay");return false;})).replay());assertEquals(9700,balance().book().minor());invariant();
    }
    void snapshot(String key,String value)throws Exception{
        try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(path.resolve(payer+".dat"))))){
            out.writeByte(10);out.writeUTF("");out.writeByte(10);out.writeUTF("BukkitValues");
            if(value!=null){out.writeByte(8);out.writeUTF(key);out.writeUTF(value);}out.writeByte(0);out.writeByte(0);
        }
    }
    void sweep()throws Exception{EconomyRecovery.sweep(source,holds,path,id->false,s->{});}
    @Test void offlineStartupCapturesSavedSenderReceiptExactlyOnce()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);snapshot(PlayerDataReceiptProof.MAIL_RECEIPTS,"S/"+id);sweep();sweep();
        assertEquals("CAPTURED",holds.find(MAIL,id).orElseThrow().state());assertEquals(MailState.READY,mail.find(id).orElseThrow().state());assertEquals(9700,balance().book().minor());invariant();
    }
    @Test void offlineStartupReleasesOnlyValidAbsence()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);sweep();assertEquals("HELD",holds.find(MAIL,id).orElseThrow().state());
        snapshot(PlayerDataReceiptProof.MAIL_RECEIPTS,null);sweep();assertEquals("RELEASED",holds.find(MAIL,id).orElseThrow().state());assertEquals(10000,balance().book().minor());invariant();
    }
    @Test void offlineStartupRetainsUnknownAndSkipsOnlinePlayers()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);snapshot(PlayerDataReceiptProof.MAIL_RECEIPTS,"P/"+id);sweep();assertEquals("HELD",holds.find(MAIL,id).orElseThrow().state());
        snapshot(PlayerDataReceiptProof.MAIL_RECEIPTS,"S/"+id);EconomyRecovery.sweep(source,holds,path,p->true,s->fail(s));assertEquals("HELD",holds.find(MAIL,id).orElseThrow().state());
    }
    @Test void offlineStartupFinishesKitWithMatchingTokenAndUsage()throws Exception{
        UUID id=UUID.randomUUID(),token=UUID.randomUUID();var d=new KitDeliveryRecord(id,payer,"paid",token,"server","minecraft:diamond 1",1,60000,true,false,System.currentTimeMillis());
        holds.reserve(KIT,id,payer,new Money(250),c->kits.beginClaim(c,d,System.currentTimeMillis()) instanceof KitRepository.BeginOutcome.Claiming);
        snapshot(PlayerDataReceiptProof.KIT_RECEIPTS,"K/"+id+"/"+token+"/paid");sweep();sweep();
        assertTrue(kits.claimAcknowledged(id,token));assertEquals(9750,balance().book().minor());assertEquals(1,kits.usage(payer,"paid").orElseThrow().claimCount());invariant();
    }
    @Test void operatorReleaseRequiresReasonAndPreservesWorkflowAndAudit()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);assertThrows(IllegalArgumentException.class,()->holds.resolve(MAIL,id,false,"CONSOLE"," "));
        assertInstanceOf(Committed.class,holds.resolve(MAIL,id,false,"CONSOLE","inventory reconciled ticket 42"));
        assertTrue(holds.operatorResolved(MAIL,id));assertTrue(mail.find(id).isPresent());assertEquals(1,count("allium_economy_reservation_audit"));
        assertInstanceOf(Rejected.class,holds.resolve(MAIL,id,true,"CONSOLE","changed mind"));assertEquals(10000,balance().book().minor());invariant();
    }
    @Test void operatorCapturePreservesWorkflowAndDebitsOnce()throws Exception{
        UUID id=UUID.randomUUID();reserve(id,300);assertInstanceOf(Committed.class,holds.resolve(MAIL,id,true,"CONSOLE","confirmed delivery"));
        assertTrue(mail.find(id).isPresent());assertEquals("CAPTURED",holds.find(MAIL,id).orElseThrow().state());assertEquals(9700,balance().book().minor());assertEquals(1,count("allium_economy_reservation_audit"));invariant();
    }

}
