package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.core.ModuleManager;
import codes.castled.allium.module.ModerationModule;
import codes.castled.allium.moderation.ModerationService;
import codes.castled.allium.command.BukkitCommandContext;
import codes.castled.allium.command.spec.CoreCommandSpecs;
import codes.castled.allium.naming.PlayerNameService;
import codes.castled.allium.scheduler.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatServiceTest {
    PluginStart plugin;ChatRepository repository;ChatService service;
    YamlConfiguration config;Player alice,bob;World world;ModerationService moderation;
    final ArrayDeque<Runnable> jobs=new ArrayDeque<>(),callbacks=new ArrayDeque<>(),delayed=new ArrayDeque<>();
    final Map<Player,Set<String>> grants=new HashMap<>();
    final Map<Player,List<String>> received=new HashMap<>();
    ChatRelay relay;BooleanSupplier tick;
    @BeforeEach void setup()throws Exception{
        plugin=mock(PluginStart.class);when(plugin.getMessageManager()).thenReturn(mock(codes.castled.allium.message.MessageManager.class));config=new YamlConfiguration();config.set("chat.deletion_resend.clear_lines",100);config.set("chat.replay-packets-per-tick",64);when(plugin.getConfig()).thenReturn(config);
        when(plugin.logger()).thenReturn(mock(codes.castled.allium.util.AlliumLogger.class));
        var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        var names=mock(PlayerNameService.class);when(plugin.getPlayerNames()).thenReturn(names);when(names.resolve(anyString())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        var scheduler=mock(TaskScheduler.class);when(plugin.getScheduler()).thenReturn(scheduler);
        when(scheduler.runOnServer(any())).thenAnswer(call->{callbacks.add(call.getArgument(0));return TaskHandle.NONE;});when(scheduler.runOnServerLater(any(),anyLong())).thenAnswer(call->{delayed.add(call.getArgument(0));return TaskHandle.NONE;});
        when(scheduler.repeatOnServer(any(),anyLong(),anyLong())).thenAnswer(call->{tick=call.getArgument(0);return TaskHandle.NONE;});
        world=mock(World.class);alice=player("Alice");bob=player("Bob");doReturn(List.of(alice,bob)).when(server).getOnlinePlayers();
        var modules=mock(ModuleManager.class);when(plugin.getModuleManager()).thenReturn(modules);var mod=mock(ModerationModule.class);when(modules.getModule("moderation")).thenReturn(mod);when(mod.isEnabled()).thenReturn(true);moderation=mock(ModerationService.class);when(mod.moderation()).thenReturn(moderation);when(moderation.isMuteStateReady(any())).thenReturn(true);
        repository=mock(ChatRepository.class);when(repository.load(any())).thenAnswer(call->new ChatRepository.State(call.getArgument(0),null,"",Set.of(),0));
        relay=mock(ChatRelay.class);service=new ChatService(plugin,repository,jobs::add,relay);service.enable();flush();
    }
    Player player(String name){
        var p=mock(Player.class);when(p.getName()).thenReturn(name);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.isOnline()).thenReturn(true);when(p.getWorld()).thenReturn(world);when(p.getLocation()).thenReturn(new Location(world,0,64,0));when(p.getDisplayName()).thenReturn(name);
        var permissions=new HashSet<>(Set.of("allium.channel.join.default.read","allium.channel.join.default.write","allium.channel.join.local.write"));grants.put(p,permissions);when(p.hasPermission(anyString())).thenAnswer(call->permissions.contains(call.getArgument(0)));when(p.canSee(any(Player.class))).thenReturn(true);
        List<String> packets=new ArrayList<>();received.put(p,packets);var spigot=mock(Player.Spigot.class);when(p.spigot()).thenReturn(spigot);
        doAnswer(call->{packets.add(net.md_5.bungee.chat.ComponentSerializer.toString((net.md_5.bungee.api.chat.BaseComponent[])call.getRawArguments()[1]));return null;}).when(spigot).sendMessage(eq(net.md_5.bungee.api.ChatMessageType.SYSTEM),any(net.md_5.bungee.api.chat.BaseComponent[].class));return p;
    }
    void flush(){int budget=1000;while((!jobs.isEmpty()||!callbacks.isEmpty())&&--budget>0){if(!jobs.isEmpty())jobs.remove().run();if(!callbacks.isEmpty())callbacks.remove().run();}assertTrue(budget>0);}
    void command(Player p,String action,String label,String...args){var context=mock(BukkitCommandContext.class);when(context.sender()).thenReturn(p);when(context.label()).thenReturn(label);when(context.args()).thenReturn(args);when(context.spec()).thenReturn(CoreCommandSpecs.CHAT_SPECS.stream().filter(s->s.canonicalName().equals(action)).findFirst().orElseThrow());service.dispatch(context);}
    List<ChatHistory.Message> history(Player p){return service.history().visible(p.getUniqueId(),System.currentTimeMillis());}
    void say(Player p,String body){service.send(p,null,body,null);}
    @Test void channelAcknowledgementUsesColoredLanguageOnlyAfterCommit(){
        command(alice,"channel","g");verify(plugin.getMessageManager(),never()).send(eq(alice),eq(codes.castled.allium.message.Messages.CHAT_CHANNEL_SAVED),any(String[].class));flush();
        verify(plugin.getMessageManager()).send(alice,codes.castled.allium.message.Messages.CHAT_CHANNEL_SAVED,"{channel}","default");
    }
    @Test void nicknameSaveUsesDedicatedLanguageKeyAfterCommit()throws Exception{
        var saved=new ChatRepository.State(alice.getUniqueId(),"Red","default",Set.of("default"),1);when(repository.load(alice.getUniqueId())).thenReturn(saved);
        command(alice,"nick","nick","Red");verify(plugin.getMessageManager(),never()).send(eq(alice),eq(codes.castled.allium.message.Messages.NICK_SET),any(String[].class));flush();
        verify(plugin.getMessageManager()).send(alice,codes.castled.allium.message.Messages.NICK_SET,"{nick}","Red");
    }
    @Test void deletionFeedbackUsesLanguagePipeline(){
        var messages=mock(codes.castled.allium.message.MessageManager.class);when(plugin.getMessageManager()).thenReturn(messages);
        say(alice,"remove me");grants.get(alice).add("allium.delmsg");command(alice,"delmsg","delmsg",history(bob).get(0).id());
        verify(messages).send(eq(alice),argThat((codes.castled.allium.message.Messages m)->m.getKey().equals("chat.delete.success")),any(String[].class));
    }
    @Test void permissionLossBeforeServerDeliveryBlocksStaffShortcut(){grants.get(alice).add("allium.staffchat");var event=new AsyncPlayerChatEvent(true,alice,"#secret",new HashSet<>(Set.of(alice,bob)));service.legacyChat(event);grants.get(alice).remove("allium.staffchat");flush();assertTrue(event.isCancelled());assertTrue(history(bob).isEmpty());verifyNoInteractions(relay);}
    @Test void mutedAfterNativeAdmissionDoesNotBroadcastOrRelay(){var event=new AsyncPlayerChatEvent(true,alice,"blocked",new HashSet<>(Set.of(alice,bob)));service.legacyChat(event);when(moderation.isMuted(alice.getUniqueId())).thenReturn(true);flush();assertTrue(history(bob).isEmpty());verifyNoInteractions(relay);}
    @Test void nativeAudienceRestrictionSurvivesFormatting(){var event=new AsyncPlayerChatEvent(true,alice,"only bob",new HashSet<>(Set.of(bob)));service.legacyChat(event);flush();assertTrue(history(alice).isEmpty());assertEquals(1,history(bob).size());}
    @Test void staffMessagesNeverEnterUnsubscribedRecipientsHistory(){grants.get(alice).add("allium.staffchat");command(alice,"channel","a");flush();say(alice,"secret");assertEquals("staff-chat",history(alice).get(0).channel());assertTrue(history(bob).isEmpty());}
    @Test void staffAliasesSendOnceAndShoutNeedsItsPermission(){
        grants.get(alice).add("allium.staffchat");
        command(alice,"channel","ac","to staff");command(alice,"channel","amsg","again");
        assertEquals(List.of("staff-chat","staff-chat"),history(alice).stream().map(ChatHistory.Message::channel).toList());assertTrue(history(bob).isEmpty());
        command(alice,"channel","shout","hello all");
        assertTrue(history(bob).isEmpty());verify(plugin.getMessageManager()).send(alice,codes.castled.allium.message.Messages.HIDE_NO_PERMISSION,"{cmd}","shout");
        grants.get(alice).add(ChatService.SHOUT);command(alice,"channel","shout","hello all");command(alice,"channel","g","and again");
        assertEquals(2,history(bob).size());assertEquals("default",history(bob).get(0).channel());
    }
    @Test void actionReachesEveryoneAsDeletableChat(){
        command(alice,"action","me","waves");
        assertEquals("action",history(bob).get(0).channel());
        String plain=net.md_5.bungee.api.chat.BaseComponent.toPlainText(net.md_5.bungee.chat.ComponentSerializer.parse(received.get(bob).get(0)));assertEquals("* Alice waves",plain);
        command(alice,"action","me");assertEquals(1,history(bob).size(),"no message: the usage card");
    }
    @Test void localAudienceUsesWorldAndRadius(){when(bob.getLocation()).thenReturn(new Location(world,200,64,0));service.send(alice,"local","near only",null);assertEquals(1,history(alice).size());assertTrue(history(bob).isEmpty());}
    @Test void deletionRebuildsOnlyOriginalRecipientHistoryAndSkipsDeletedCopies(){say(alice,"first");String id=history(bob).get(0).id();say(alice,"second");grants.get(alice).add("allium.delmsg");command(alice,"delmsg","delmsg",id);for(int i=0;i<8;i++)tick.getAsBoolean();assertTrue(service.history().find(id).orElseThrow().deleted().get());assertEquals(1,history(bob).size());String finalPacket=received.get(bob).get(received.get(bob).size()-1);assertTrue(finalPacket.contains("second"));assertFalse(finalPacket.contains("first"));verify(relay).delete(id);}
    @Test void deletionAndLiveArrivalDuringReplayDoNotResurrectOrLoseMessages(){say(alice,"first");String one=history(bob).get(0).id();say(alice,"second");String two=history(bob).get(1).id();grants.get(alice).add("allium.delmsg");command(alice,"delmsg","delmsg",one);tick.getAsBoolean();say(alice,"live");command(alice,"delmsg","delmsg",two);for(int i=0;i<20;i++)tick.getAsBoolean();assertEquals(1,history(bob).size());assertTrue(received.get(bob).get(received.get(bob).size()-1).contains("live"));}
    @Test void removedPrivateChannelDoesNotFallBackToPublic(){grants.get(alice).add("allium.staffchat");command(alice,"channel","a");flush();config.set("chat.channels.staff-channel","other-staff");service.reload();say(alice,"must not become public");assertTrue(history(bob).isEmpty());}
    @Test void failedNicknameCommitDoesNotChangePublishedName()throws Exception{doThrow(new java.sql.SQLException("unavailable")).when(repository).nickname(any(),any());command(alice,"nick","nick","Red");flush();assertEquals("Alice",service.placeholder(alice,false));verify(repository).nickname(alice.getUniqueId(),"Red");}
    @Test void nicknameAcknowledgementWaitsForCommit()throws Exception{var stored=new ChatRepository.State(alice.getUniqueId(),"Red","default",Set.of("default"),1);when(repository.load(alice.getUniqueId())).thenReturn(stored);command(alice,"nick","nick","Red");assertEquals("Alice",service.placeholder(alice,false));flush();assertEquals("Red",service.placeholder(alice,false));}
    @Test void incomingDiscordDeduplicatesAndNeverUsesAnUnrelatedChannelAudience(){config.set("chat.channels.list.staff-chat.discord-channel-id","123456789");service.reload();service.inbound("99","123456789","discord","secret");service.inbound("99","123456789","discord","secret");flush();assertTrue(history(bob).isEmpty());assertTrue(history(alice).isEmpty());}
    @Test void paperDisplayNormalizationDoesNotCauseRepeatedUpdates()throws Exception{
        var stored=new ChatRepository.State(alice.getUniqueId(),"Red","default",Set.of("default"),1);when(repository.load(alice.getUniqueId())).thenReturn(stored);when(alice.getDisplayName()).thenReturn("Red\u00a7r");command(alice,"nick","nick","Red");flush();for(int i=0;i<5;i++)tick.getAsBoolean();verify(alice,times(1)).setDisplayName("Red");
    }
    @Test void moduleDisablePreventsCommandsAndLeavesNativeChatToItsOwner(){config.set("modules.chat",false);service.reload();var event=new AsyncPlayerChatEvent(true,alice,"vanilla",new HashSet<>(Set.of(alice,bob)));service.legacyChat(event);assertFalse(event.isCancelled());command(alice,"nick","nick","Red");assertTrue(jobs.isEmpty());assertTrue(history(bob).isEmpty());}
    @Test void failedLoadRetriesWithoutBroadcastingOrLosingIdentity()throws Exception{
        var restored=new ChatRepository.State(alice.getUniqueId(),null,"",Set.of(),0);
        when(repository.load(alice.getUniqueId())).thenThrow(new java.sql.SQLException("busy")).thenReturn(restored);
        service.quit(new PlayerQuitEvent(alice,null));service.joined(new PlayerJoinEvent(alice,null));flush();assertFalse(service.ready(alice.getUniqueId()));say(alice,"not admitted");assertTrue(history(bob).isEmpty());assertFalse(delayed.isEmpty());delayed.remove().run();flush();assertTrue(service.ready(alice.getUniqueId()));
    }

    @Test void interactiveDelayedDeletedPacketDisablesOriginalFallback()throws Exception{interactiveGuard(true);}
    @Test void interactiveLivePacketRetainsItsOriginalOwner()throws Exception{interactiveGuard(false);}
    void interactiveGuard(boolean deleted)throws Exception{
        var peer=mock(org.bukkit.plugin.Plugin.class);when(peer.isEnabled()).thenReturn(true);var manager=plugin.getServer().getPluginManager();when(manager.getPlugin("InteractiveChat")).thenReturn(peer);
        var install=ChatService.class.getDeclaredMethod("installInteractiveGuard");install.setAccessible(true);install.invoke(service);
        var executor=org.mockito.ArgumentCaptor.forClass(org.bukkit.plugin.EventExecutor.class);
        verify(manager).registerEvent(eq(com.loohp.interactivechat.api.events.PreChatPacketSendEvent.class),eq(service),eq(org.bukkit.event.EventPriority.HIGHEST),executor.capture(),eq(plugin),eq(false));
        say(alice,"delayed");String id=history(bob).get(0).id();if(deleted)service.history().delete(id);
        var gson=net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();var component=gson.deserialize(ChatIdentity.tag("{\"text\":\"delayed\"}",ChatIdentity.ID+id)).compact();
        var event=new com.loohp.interactivechat.api.events.PreChatPacketSendEvent(component);executor.getValue().execute(service,event);
        assertEquals(deleted,event.isCancelled());assertEquals(!deleted,event.sendOriginalIfCancelled());
    }
    @AfterEach void close(){service.close();}
}
